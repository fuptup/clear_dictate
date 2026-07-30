#include "clear_dictate/LlamaTextEngine.h"

#include "ggml-backend.h"
#include "llama.h"

#include <algorithm>
#include <atomic>
#include <climits>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <mutex>
#include <new>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

namespace clear_dictate
{
    namespace
    {
        // llama.cpp treats this as a borrowed, null-terminated offload-device list.
        // Static storage keeps the borrowed pointer valid for the model lifetime.
        ggml_backend_dev_t NoOffloadDevices[] = { nullptr };

        void SuppressLlamaLog(ggml_log_level, const char*, void*) noexcept
        {
            // ClearDictate never forwards upstream logs because they may contain dictated text.
        }

        /**
         * Initializes llama.cpp exactly once and keeps its process-global backend alive
         * until normal process shutdown.
         */
        class LlamaBackendLifetime final
        {
        public:
            LlamaBackendLifetime()
            {
                llama_log_set(SuppressLlamaLog, nullptr);
                llama_backend_init();
            }

            ~LlamaBackendLifetime()
            {
                llama_backend_free();
            }

            LlamaBackendLifetime(const LlamaBackendLifetime&) = delete;
            LlamaBackendLifetime& operator=(const LlamaBackendLifetime&) = delete;
        };

        void EnsureLlamaBackendIsInitialized()
        {
            static LlamaBackendLifetime backendLifetime;
            static_cast<void>(backendLifetime);
        }

        struct LlamaModelDeleter final
        {
            void operator()(llama_model* model) const noexcept
            {
                llama_model_free(model);
            }
        };

        struct LlamaContextDeleter final
        {
            void operator()(llama_context* context) const noexcept
            {
                llama_free(context);
            }
        };

        struct LlamaSamplerDeleter final
        {
            void operator()(llama_sampler* sampler) const noexcept
            {
                llama_sampler_free(sampler);
            }
        };

        using LlamaModelHandle = std::unique_ptr<llama_model, LlamaModelDeleter>;
        using LlamaContextHandle = std::unique_ptr<llama_context, LlamaContextDeleter>;
        using LlamaSamplerHandle = std::unique_ptr<llama_sampler, LlamaSamplerDeleter>;

        /**
         * Owns the arrays allocated by llama_batch_init.
         */
        class LlamaBatchLease final
        {
        public:
            LlamaBatchLease()
                : batch_(llama_batch_init(TextGenerationLimits::MaximumBatchTokenCount, 0, 1))
            {
                if (batch_.token == nullptr || batch_.pos == nullptr || batch_.n_seq_id == nullptr || batch_.seq_id == nullptr || batch_.logits == nullptr)
                {
                    llama_batch_free(batch_);
                    throw std::runtime_error("Failed to allocate the local text model batch.");
                }
            }

            ~LlamaBatchLease()
            {
                llama_batch_free(batch_);
            }

            llama_batch& Get() noexcept
            {
                return batch_;
            }

            LlamaBatchLease(const LlamaBatchLease&) = delete;
            LlamaBatchLease& operator=(const LlamaBatchLease&) = delete;

        private:
            llama_batch batch_;
        };

        /**
         * Clears all key-value cache metadata and buffers both before and after a request.
         * The post-request clear also runs after cancellation or an exception.
         */
        class LlamaMemoryLease final
        {
        public:
            explicit LlamaMemoryLease(llama_context* context)
                : memory_(llama_get_memory(context))
            {
                llama_memory_clear(memory_, true);
            }

            ~LlamaMemoryLease()
            {
                llama_memory_clear(memory_, true);
            }

            LlamaMemoryLease(const LlamaMemoryLease&) = delete;
            LlamaMemoryLease& operator=(const LlamaMemoryLease&) = delete;

        private:
            llama_memory_t memory_;
        };

        /**
         * Guarantees that the cancellation controller returns to an idle state.
         */
        class ActiveRequestLease final
        {
        public:
            ActiveRequestLease(RequestCancellationController& cancellationController, std::uint64_t requestIdentifier)
                : cancellationController_(cancellationController),
                  requestIdentifier_(requestIdentifier)
            {
                cancellationController_.BeginRequest(requestIdentifier_);
            }

            ~ActiveRequestLease()
            {
                if (!wasFinalized_)
                {
                    cancellationController_.EndRequest(requestIdentifier_);
                }
            }

            TextGenerationResult Finalize(TextGenerationResult result) noexcept
            {
                const RequestFinalization finalization = cancellationController_.FinishRequest(requestIdentifier_);
                wasFinalized_ = true;

                if (finalization == RequestFinalization::Cancelled)
                {
                    return { TextGenerationStatus::Cancelled, {}, 0 };
                }

                if (finalization == RequestFinalization::NotActive)
                {
                    return { TextGenerationStatus::NativeFailure, {}, 0 };
                }

                return result;
            }

            ActiveRequestLease(const ActiveRequestLease&) = delete;
            ActiveRequestLease& operator=(const ActiveRequestLease&) = delete;

        private:
            RequestCancellationController& cancellationController_;
            std::uint64_t requestIdentifier_;
            bool wasFinalized_ = false;
        };

        bool RequestBytesFitBound(const std::string& systemInstruction, const std::string& userInstruction) noexcept
        {
            return systemInstruction.size() <= TextGenerationLimits::MaximumRequestByteCount &&
                userInstruction.size() <= TextGenerationLimits::MaximumRequestByteCount - systemInstruction.size();
        }

        std::vector<llama_token> Tokenize(const llama_vocab* vocabulary, const std::string& text)
        {
            if (text.size() > static_cast<std::size_t>(INT32_MAX))
            {
                throw std::length_error("The local text model prompt exceeds the supported byte count.");
            }

            const std::int32_t requiredTokenCount = llama_tokenize(
                vocabulary,
                text.data(),
                static_cast<std::int32_t>(text.size()),
                nullptr,
                0,
                true,
                true);

            if (requiredTokenCount == INT32_MIN)
            {
                throw std::runtime_error("The local text model could not measure the prompt.");
            }

            const std::int32_t tokenCount = requiredTokenCount < 0 ? -requiredTokenCount : requiredTokenCount;
            std::vector<llama_token> tokens(static_cast<std::size_t>(tokenCount));
            const std::int32_t actualTokenCount = llama_tokenize(
                vocabulary,
                text.data(),
                static_cast<std::int32_t>(text.size()),
                tokens.data(),
                tokenCount,
                true,
                true);

            if (actualTokenCount < 0 || actualTokenCount != tokenCount)
            {
                throw std::runtime_error("The local text model could not tokenize the prompt.");
            }

            return tokens;
        }

        std::string ApplyChatTemplate(const llama_model* model, const std::string& systemInstruction, const std::string& userInstruction)
        {
            const char* chatTemplate = llama_model_chat_template(model, nullptr);
            if (chatTemplate == nullptr)
            {
                throw std::runtime_error("The local text model does not provide a supported chat template.");
            }

            const llama_chat_message messages[] =
            {
                { "system", systemInstruction.c_str() },
                { "user", userInstruction.c_str() }
            };

            const std::int32_t requiredByteCount = llama_chat_apply_template(chatTemplate, messages, 2, true, nullptr, 0);
            if (requiredByteCount <= 0)
            {
                throw std::runtime_error("The local text model could not format the request.");
            }

            if (requiredByteCount == INT32_MAX)
            {
                throw std::length_error("The formatted local text model request is too large.");
            }

            std::vector<char> formattedPrompt(static_cast<std::size_t>(requiredByteCount) + 1);
            const std::int32_t actualByteCount = llama_chat_apply_template(
                chatTemplate,
                messages,
                2,
                true,
                formattedPrompt.data(),
                static_cast<std::int32_t>(formattedPrompt.size()));

            if (actualByteCount < 0 || actualByteCount > requiredByteCount)
            {
                throw std::runtime_error("The local text model produced an unstable formatted request size.");
            }

            return std::string(formattedPrompt.data(), static_cast<std::size_t>(actualByteCount));
        }

        void PopulateBatch(
            llama_batch& batch,
            const llama_token* tokens,
            std::int32_t tokenCount,
            std::int32_t firstPosition,
            bool requestLogitsForLastToken)
        {
            batch.n_tokens = tokenCount;

            for (std::int32_t tokenIndex = 0; tokenIndex < tokenCount; ++tokenIndex)
            {
                batch.token[tokenIndex] = tokens[tokenIndex];
                batch.pos[tokenIndex] = firstPosition + tokenIndex;
                batch.n_seq_id[tokenIndex] = 1;
                batch.seq_id[tokenIndex][0] = 0;
                batch.logits[tokenIndex] = requestLogitsForLastToken && tokenIndex == tokenCount - 1 ? 1 : 0;
            }
        }

        std::string Detokenize(const llama_vocab* vocabulary, const std::vector<llama_token>& tokens)
        {
            if (tokens.empty())
            {
                return {};
            }

            const std::int32_t requiredByteCount = llama_detokenize(
                vocabulary,
                tokens.data(),
                static_cast<std::int32_t>(tokens.size()),
                nullptr,
                0,
                true,
                false);

            if (requiredByteCount == INT32_MIN)
            {
                throw std::runtime_error("The local text model could not measure generated text.");
            }

            const std::int32_t byteCount = requiredByteCount < 0 ? -requiredByteCount : requiredByteCount;
            std::vector<char> generatedText(static_cast<std::size_t>(byteCount));
            const std::int32_t actualByteCount = llama_detokenize(
                vocabulary,
                tokens.data(),
                static_cast<std::int32_t>(tokens.size()),
                generatedText.data(),
                byteCount,
                true,
                false);

            if (actualByteCount < 0 || actualByteCount != byteCount)
            {
                throw std::runtime_error("The local text model could not decode generated text.");
            }

            return std::string(generatedText.data(), static_cast<std::size_t>(actualByteCount));
        }
    }

    class LlamaTextEngine::Implementation final
    {
    public:
        Implementation(const std::filesystem::path& modelPath, std::int32_t inferenceThreadCount)
            : cancellationController_(),
              model_(LoadModel(modelPath, inferenceThreadCount)),
              vocabulary_(llama_model_get_vocab(model_.get())),
              context_(CreateContext(model_.get(), cancellationController_, inferenceThreadCount))
        {
            if (vocabulary_ == nullptr)
            {
                throw std::runtime_error("The local text model has no vocabulary.");
            }
        }

        TextGenerationResult Generate(std::uint64_t requestIdentifier, const std::string& systemInstruction, const std::string& userInstruction)
        {
            if (isClosing_.load(std::memory_order_acquire))
            {
                return { TextGenerationStatus::Closing, {}, 0 };
            }

            std::unique_lock<std::mutex> generationLock(generationMutex_, std::try_to_lock);
            if (!generationLock.owns_lock())
            {
                return { TextGenerationStatus::Busy, {}, 0 };
            }

            if (isClosing_.load(std::memory_order_acquire))
            {
                return { TextGenerationStatus::Closing, {}, 0 };
            }

            try
            {
                ActiveRequestLease activeRequest(cancellationController_, requestIdentifier);
                TextGenerationResult generationResult
                {
                    TextGenerationStatus::NativeFailure,
                    {},
                    0
                };

                try
                {
                    LlamaMemoryLease memoryLease(context_.get());

                    if (isClosing_.load(std::memory_order_acquire))
                    {
                        cancellationController_.CancelRequest(requestIdentifier);
                    }

                    if (cancellationController_.ShouldAbort())
                    {
                        generationResult = { TextGenerationStatus::Cancelled, {}, 0 };
                    }
                    else if (!RequestBytesFitBound(systemInstruction, userInstruction))
                    {
                        generationResult = { TextGenerationStatus::ContextLimitExceeded, {}, 0 };
                    }
                    else
                    {
                        const std::string formattedPrompt = ApplyChatTemplate(model_.get(), systemInstruction, userInstruction);

                        if (cancellationController_.ShouldAbort())
                        {
                            generationResult = { TextGenerationStatus::Cancelled, {}, 0 };
                        }
                        else
                        {
                            const std::vector<llama_token> promptTokens = Tokenize(vocabulary_, formattedPrompt);

                            if (cancellationController_.ShouldAbort())
                            {
                                generationResult = { TextGenerationStatus::Cancelled, {}, 0 };
                            }
                            else if (EvaluatePromptBudget(static_cast<std::int32_t>(promptTokens.size())) == PromptBudgetDecision::Rejected)
                            {
                                generationResult = { TextGenerationStatus::ContextLimitExceeded, {}, 0 };
                            }
                            else
                            {
                                generationResult = GenerateFromTokens(promptTokens);
                            }
                        }
                    }
                }
                catch (const std::bad_alloc&)
                {
                    throw;
                }
                catch (const std::exception&)
                {
                    generationResult = { TextGenerationStatus::NativeFailure, {}, 0 };
                }

                return activeRequest.Finalize(std::move(generationResult));
            }
            catch (const std::bad_alloc&)
            {
                throw;
            }
            catch (const std::exception&)
            {
                return { TextGenerationStatus::NativeFailure, {}, 0 };
            }
        }

        bool Cancel(std::uint64_t requestIdentifier) noexcept
        {
            if (isClosing_.load(std::memory_order_acquire))
            {
                return false;
            }

            return cancellationController_.CancelRequest(requestIdentifier);
        }

        void CloseAndWait() noexcept
        {
            isClosing_.store(true, std::memory_order_release);
            cancellationController_.CancelActiveRequest();
            std::scoped_lock generationLock(generationMutex_);
        }

    private:
        static LlamaModelHandle LoadModel(const std::filesystem::path& modelPath, std::int32_t inferenceThreadCount)
        {
            if (inferenceThreadCount <= 0 || inferenceThreadCount > TextGenerationLimits::MaximumInferenceThreadCount)
            {
                throw std::invalid_argument("The local text model thread count must be between 1 and 64.");
            }

            EnsureLlamaBackendIsInitialized();
            ggml_backend_dev_t cpuDevice = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU);
            if (cpuDevice == nullptr)
            {
                throw std::runtime_error("The local text model CPU backend is unavailable.");
            }

            const std::string utf8ModelPath = modelPath.u8string();

            llama_model_params modelParameters = llama_model_default_params();
            modelParameters.devices = NoOffloadDevices;
            modelParameters.n_gpu_layers = 0;
            modelParameters.split_mode = LLAMA_SPLIT_MODE_NONE;
            modelParameters.main_gpu = -1;
            modelParameters.check_tensors = true;

            LlamaModelHandle model(llama_model_load_from_file(utf8ModelPath.c_str(), modelParameters));
            if (!model)
            {
                throw std::runtime_error("The local text model could not be loaded.");
            }

            return model;
        }

        static bool ShouldAbortDecode(void* controller) noexcept
        {
            return static_cast<RequestCancellationController*>(controller)->ShouldAbort();
        }

        static LlamaContextHandle CreateContext(
            llama_model* model,
            RequestCancellationController& cancellationController,
            std::int32_t inferenceThreadCount)
        {
            llama_context_params contextParameters = llama_context_default_params();
            contextParameters.n_ctx = TextGenerationLimits::ContextTokenCount;
            contextParameters.n_batch = TextGenerationLimits::MaximumBatchTokenCount;
            contextParameters.n_ubatch = TextGenerationLimits::MaximumBatchTokenCount;
            contextParameters.n_seq_max = 1;
            contextParameters.n_threads = inferenceThreadCount;
            contextParameters.n_threads_batch = inferenceThreadCount;
            contextParameters.abort_callback = ShouldAbortDecode;
            contextParameters.abort_callback_data = &cancellationController;
            contextParameters.offload_kqv = false;
            contextParameters.op_offload = false;
            contextParameters.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;
            contextParameters.no_perf = true;

            LlamaContextHandle context(llama_init_from_model(model, contextParameters));
            if (!context)
            {
                throw std::runtime_error("The local text model context could not be created.");
            }

            if (llama_n_ctx_seq(context.get()) != TextGenerationLimits::ContextTokenCount ||
                llama_n_seq_max(context.get()) != 1 ||
                llama_n_batch(context.get()) < TextGenerationLimits::MaximumBatchTokenCount ||
                llama_n_ubatch(context.get()) < TextGenerationLimits::MaximumBatchTokenCount)
            {
                throw std::runtime_error("The local text model context did not honor the required fixed dimensions.");
            }

            return context;
        }

        TextGenerationResult GenerateFromTokens(const std::vector<llama_token>& promptTokens)
        {
            LlamaBatchLease batchLease;
            llama_batch& batch = batchLease.Get();

            const TextGenerationStatus promptStatus = DecodePrompt(promptTokens, batch);
            if (promptStatus != TextGenerationStatus::Completed)
            {
                return { promptStatus, {}, 0 };
            }

            LlamaSamplerHandle sampler(llama_sampler_init_greedy());
            if (!sampler)
            {
                return { TextGenerationStatus::NativeFailure, {}, 0 };
            }

            std::vector<llama_token> generatedTokens;
            generatedTokens.reserve(TextGenerationLimits::MaximumGeneratedTokenCount);
            bool reachedEndOfGeneration = false;

            while (generatedTokens.size() < static_cast<std::size_t>(TextGenerationLimits::MaximumGeneratedTokenCount))
            {
                if (cancellationController_.ShouldAbort())
                {
                    return { TextGenerationStatus::Cancelled, {}, 0 };
                }

                const llama_token generatedToken = llama_sampler_sample(sampler.get(), context_.get(), -1);
                if (llama_vocab_is_eog(vocabulary_, generatedToken))
                {
                    reachedEndOfGeneration = true;
                    break;
                }

                generatedTokens.push_back(generatedToken);
                if (generatedTokens.size() == static_cast<std::size_t>(TextGenerationLimits::MaximumGeneratedTokenCount))
                {
                    break;
                }

                const std::int32_t generatedTokenPosition =
                    static_cast<std::int32_t>(promptTokens.size() + generatedTokens.size() - 1);
                PopulateBatch(batch, &generatedToken, 1, generatedTokenPosition, true);

                const std::int32_t decodeStatus = llama_decode(context_.get(), batch);
                if (decodeStatus == 2 || cancellationController_.ShouldAbort())
                {
                    return { TextGenerationStatus::Cancelled, {}, 0 };
                }

                if (decodeStatus != 0)
                {
                    return { TextGenerationStatus::NativeFailure, {}, 0 };
                }
            }

            if (!reachedEndOfGeneration)
            {
                return
                {
                    TextGenerationStatus::OutputLimitReached,
                    {},
                    static_cast<std::int32_t>(generatedTokens.size())
                };
            }

            return
            {
                TextGenerationStatus::Completed,
                Detokenize(vocabulary_, generatedTokens),
                static_cast<std::int32_t>(generatedTokens.size())
            };
        }

        TextGenerationStatus DecodePrompt(const std::vector<llama_token>& promptTokens, llama_batch& batch)
        {
            std::size_t firstTokenIndex = 0;

            while (firstTokenIndex < promptTokens.size())
            {
                if (cancellationController_.ShouldAbort())
                {
                    return TextGenerationStatus::Cancelled;
                }

                const std::size_t remainingTokenCount = promptTokens.size() - firstTokenIndex;
                const std::int32_t batchTokenCount = static_cast<std::int32_t>(
                    std::min(remainingTokenCount, static_cast<std::size_t>(TextGenerationLimits::MaximumBatchTokenCount)));
                const bool isFinalPromptBatch = firstTokenIndex + static_cast<std::size_t>(batchTokenCount) == promptTokens.size();

                PopulateBatch(
                    batch,
                    promptTokens.data() + firstTokenIndex,
                    batchTokenCount,
                    static_cast<std::int32_t>(firstTokenIndex),
                    isFinalPromptBatch);

                const std::int32_t decodeStatus = llama_decode(context_.get(), batch);
                if (decodeStatus == 2 || cancellationController_.ShouldAbort())
                {
                    return TextGenerationStatus::Cancelled;
                }

                if (decodeStatus != 0)
                {
                    return TextGenerationStatus::NativeFailure;
                }

                firstTokenIndex += static_cast<std::size_t>(batchTokenCount);
            }

            return TextGenerationStatus::Completed;
        }

        RequestCancellationController cancellationController_;
        std::atomic<bool> isClosing_ { false };
        LlamaModelHandle model_;
        const llama_vocab* vocabulary_;
        LlamaContextHandle context_;
        std::mutex generationMutex_;
    };

    LlamaTextEngine::LlamaTextEngine(const std::filesystem::path& modelPath, std::int32_t inferenceThreadCount)
        : implementation_(std::make_shared<Implementation>(modelPath, inferenceThreadCount))
    {
    }

    LlamaTextEngine::~LlamaTextEngine()
    {
        Close();
    }

    TextGenerationResult LlamaTextEngine::Generate(std::uint64_t requestIdentifier, const std::string& systemInstruction, const std::string& userInstruction)
    {
        const std::shared_ptr<Implementation> implementation = std::atomic_load_explicit(&implementation_, std::memory_order_acquire);
        if (!implementation)
        {
            return { TextGenerationStatus::Closing, {}, 0 };
        }

        return implementation->Generate(requestIdentifier, systemInstruction, userInstruction);
    }

    bool LlamaTextEngine::Cancel(std::uint64_t requestIdentifier) noexcept
    {
        const std::shared_ptr<Implementation> implementation = std::atomic_load_explicit(&implementation_, std::memory_order_acquire);
        return implementation != nullptr && implementation->Cancel(requestIdentifier);
    }

    void LlamaTextEngine::Close() noexcept
    {
        std::shared_ptr<Implementation> implementation = std::atomic_exchange_explicit(
            &implementation_,
            std::shared_ptr<Implementation> {},
            std::memory_order_acq_rel);

        if (implementation)
        {
            implementation->CloseAndWait();
        }
    }
}
