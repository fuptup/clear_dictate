#include "clear_dictate/TextInferenceWorkerSession.h"

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <exception>
#include <new>
#include <string>
#include <utility>
#include <vector>

namespace clear_dictate
{
    namespace
    {
        void SecureClear(std::string& sensitiveText) noexcept
        {
            volatile char* sensitiveBytes = sensitiveText.empty() ? nullptr : sensitiveText.data();
            for (std::size_t byteIndex = 0; byteIndex < sensitiveText.size(); ++byteIndex)
            {
                sensitiveBytes[byteIndex] = '\0';
            }
            sensitiveText.clear();
        }

        void SecureClear(std::vector<std::uint8_t>& sensitiveBytes) noexcept
        {
            volatile std::uint8_t* writableBytes = sensitiveBytes.empty() ? nullptr : sensitiveBytes.data();
            for (std::size_t byteIndex = 0; byteIndex < sensitiveBytes.size(); ++byteIndex)
            {
                writableBytes[byteIndex] = 0;
            }
            sensitiveBytes.clear();
        }

        class SensitiveStringScrubber final
        {
        public:
            explicit SensitiveStringScrubber(std::string& sensitiveText) noexcept
                : sensitiveText_(sensitiveText)
            {
            }

            ~SensitiveStringScrubber()
            {
                SecureClear(sensitiveText_);
            }

        private:
            std::string& sensitiveText_;
        };

        class SensitiveBytesScrubber final
        {
        public:
            explicit SensitiveBytesScrubber(std::vector<std::uint8_t>& sensitiveBytes) noexcept
                : sensitiveBytes_(sensitiveBytes)
            {
            }

            ~SensitiveBytesScrubber()
            {
                SecureClear(sensitiveBytes_);
            }

        private:
            std::vector<std::uint8_t>& sensitiveBytes_;
        };
    }

    WorkerSessionException::WorkerSessionException(WorkerSessionFailure failure)
        : std::runtime_error("Worker session failure."),
          failure_(failure)
    {
    }

    WorkerSessionFailure WorkerSessionException::Failure() const noexcept
    {
        return failure_;
    }

    TextInferenceWorkerSession::TextInferenceWorkerSession(
        TextGenerationBackendLoader& backendLoader,
        FrameWriter frameWriter,
        std::function<void()> fatalFailureHandler)
        : backendLoader_(backendLoader),
          frameWriter_(std::move(frameWriter)),
          fatalFailureHandler_(std::move(fatalFailureHandler))
    {
        if (!frameWriter_)
        {
            throw std::invalid_argument("A worker frame writer is required.");
        }

        generationThread_ = std::thread(&TextInferenceWorkerSession::GenerationLoop, this);
    }

    TextInferenceWorkerSession::~TextInferenceWorkerSession()
    {
        Close();
    }

    void TextInferenceWorkerSession::Handle(const WorkerProtocolFrame& frame)
    {
        std::lock_guard<std::mutex> commandLock(commandMutex_);
        WorkerSessionState currentState;
        {
            std::lock_guard<std::mutex> lock(stateMutex_);
            currentState = state_;
        }

        switch (currentState)
        {
            case WorkerSessionState::AwaitingHello:
                HandleHello(frame);
                return;

            case WorkerSessionState::AwaitingModelLoad:
                HandleModelLoad(frame);
                return;

            case WorkerSessionState::Idle:
                HandleIdleCommand(frame);
                return;

            case WorkerSessionState::OperationActive:
                HandleActiveCommand(frame);
                return;

            case WorkerSessionState::Closed:
            case WorkerSessionState::Failed:
                Reject(WorkerSessionFailure::IllegalState);
        }
    }

    WorkerSessionState TextInferenceWorkerSession::State() const
    {
        std::lock_guard<std::mutex> lock(stateMutex_);
        return state_;
    }

    void TextInferenceWorkerSession::Close() noexcept
    {
        TextGenerationBackend* backendToClose = nullptr;
        {
            std::lock_guard<std::mutex> lock(stateMutex_);
            stopping_ = true;
            cancellationRequested_ = true;
            backendToClose = backend_.get();
        }

        if (backendToClose != nullptr)
        {
            backendToClose->Close();
        }

        pendingRequestChanged_.notify_all();
        if (generationThread_.joinable())
        {
            generationThread_.join();
        }

        std::lock_guard<std::mutex> lock(stateMutex_);
        backend_.reset();
        if (state_ != WorkerSessionState::Failed)
        {
            state_ = WorkerSessionState::Closed;
        }
    }

    void TextInferenceWorkerSession::HandleHello(const WorkerProtocolFrame& frame)
    {
        if (frame.scope != WorkerFrameScope::Control || frame.type != WorkerMessageType::Hello)
        {
            Reject(WorkerSessionFailure::IllegalMessage);
        }

        {
            std::lock_guard<std::mutex> lock(stateMutex_);
            if (state_ != WorkerSessionState::AwaitingHello)
            {
                Reject(WorkerSessionFailure::IllegalState);
            }
            state_ = WorkerSessionState::AwaitingModelLoad;
        }

        WriteFrame(WorkerProtocolFrame::Control(WorkerMessageType::Ready, {}));
    }

    void TextInferenceWorkerSession::HandleModelLoad(const WorkerProtocolFrame& frame)
    {
        if (frame.scope != WorkerFrameScope::Control || frame.type != WorkerMessageType::LoadModels)
        {
            Reject(WorkerSessionFailure::IllegalMessage);
        }

        const TextModelLoadRequest request = DecodeTextModelLoadRequest(frame.payload);
        std::unique_ptr<TextGenerationBackend> loadedBackend;

        try
        {
            loadedBackend = backendLoader_.Load(request);
            if (!loadedBackend)
            {
                throw std::runtime_error("The model loader returned no backend.");
            }
        }
        catch (const std::bad_alloc&)
        {
            throw;
        }
        catch (...)
        {
            {
                std::lock_guard<std::mutex> lock(stateMutex_);
                state_ = WorkerSessionState::Failed;
            }

            WriteFrame(WorkerProtocolFrame::Control(
                WorkerMessageType::ControlError,
                EncodeFixedError(static_cast<std::uint32_t>(WorkerControlError::ModelLoadFailed))));
            return;
        }

        {
            std::lock_guard<std::mutex> lock(stateMutex_);
            if (state_ != WorkerSessionState::AwaitingModelLoad)
            {
                loadedBackend->Close();
                Reject(WorkerSessionFailure::IllegalState);
            }

            backend_ = std::move(loadedBackend);
            state_ = WorkerSessionState::Idle;
        }

        WriteFrame(WorkerProtocolFrame::Control(WorkerMessageType::ModelsLoaded, {}));
    }

    void TextInferenceWorkerSession::HandleIdleCommand(const WorkerProtocolFrame& frame)
    {
        if (frame.scope == WorkerFrameScope::Operation &&
            frame.type == WorkerMessageType::Cancel &&
            MatchesLastTerminalIdentity(frame))
        {
            return;
        }

        if (frame.scope == WorkerFrameScope::Control && frame.type == WorkerMessageType::Shutdown)
        {
            Close();
            return;
        }

        if (frame.scope == WorkerFrameScope::Operation && frame.type == WorkerMessageType::PolishTranscript)
        {
            BeginPolish(frame);
            return;
        }

        Reject(WorkerSessionFailure::IllegalMessage);
    }

    void TextInferenceWorkerSession::HandleActiveCommand(const WorkerProtocolFrame& frame)
    {
        if (frame.scope == WorkerFrameScope::Operation &&
            frame.type == WorkerMessageType::Cancel &&
            MatchesLastTerminalIdentity(frame))
        {
            return;
        }

        if (frame.scope != WorkerFrameScope::Operation || frame.type != WorkerMessageType::Cancel)
        {
            Reject(WorkerSessionFailure::IllegalMessage);
        }

        RequestCancellation(frame);
    }

    void TextInferenceWorkerSession::BeginPolish(const WorkerProtocolFrame& frame)
    {
        TextPolishPrompt prompt = DecodeTextPolishPrompt(frame.payload);

        {
            std::lock_guard<std::mutex> lock(stateMutex_);
            if (state_ != WorkerSessionState::Idle || !backend_)
            {
                Reject(WorkerSessionFailure::IllegalState);
            }

            activeIdentity_ = frame.identity;
            pendingRequest_ = PendingPolishRequest
            {
                frame.identity,
                std::move(prompt)
            };
            generationStarted_ = false;
            cancellationRequested_ = false;
            cancellationAcknowledged_ = false;
            state_ = WorkerSessionState::OperationActive;
        }

        pendingRequestChanged_.notify_one();
    }

    void TextInferenceWorkerSession::RequestCancellation(const WorkerProtocolFrame& frame)
    {
        bool cancellationAccepted = false;

        {
            std::lock_guard<std::mutex> lock(stateMutex_);
            if (state_ != WorkerSessionState::OperationActive || !activeIdentity_)
            {
                Reject(WorkerSessionFailure::IllegalState);
            }

            if (!IdentitiesMatch(*activeIdentity_, frame.identity))
            {
                Reject(WorkerSessionFailure::OperationIdentityMismatch);
            }

            if (cancellationAcknowledged_)
            {
                return;
            }

            cancellationRequested_ = true;
            cancellationAccepted = !generationStarted_ || backend_->Cancel(frame.identity.workerRequestToken);
            cancellationAcknowledged_ = cancellationAccepted;
        }

        // Handle and terminal publication share commandMutex_, so this accepted
        // acknowledgement is externally ordered before OPERATION_CANCELLED without
        // holding the state mutex across a potentially blocking pipe write.
        if (cancellationAccepted)
        {
            WriteFrame(WorkerProtocolFrame::Operation(
                WorkerMessageType::CancellationAcknowledged,
                frame.identity,
                {}));
        }
    }

    void TextInferenceWorkerSession::GenerationLoop() noexcept
    {
        try
        {
            while (true)
            {
                std::optional<PendingPolishRequest> request;
                {
                    std::unique_lock<std::mutex> lock(stateMutex_);
                    pendingRequestChanged_.wait(lock, [this]() { return stopping_ || pendingRequest_.has_value(); });

                    if (stopping_ && !pendingRequest_)
                    {
                        return;
                    }

                    request = std::move(pendingRequest_);
                    pendingRequest_.reset();
                }

                const WorkerOperationIdentity requestIdentity = request->identity;
                try
                {
                    RunPolish(std::move(*request));
                }
                catch (const std::bad_alloc&)
                {
                    throw;
                }
                catch (...)
                {
                    PublishGenerationResult(
                        requestIdentity,
                        { TextGenerationStatus::NativeFailure, {}, 0 });
                }
            }
        }
        catch (...)
        {
            {
                std::lock_guard<std::mutex> lock(stateMutex_);
                state_ = WorkerSessionState::Failed;
                stopping_ = true;
            }

            if (fatalFailureHandler_)
            {
                fatalFailureHandler_();
            }
        }
    }

    void TextInferenceWorkerSession::RunPolish(PendingPolishRequest request)
    {
        SensitiveStringScrubber systemInstructionScrubber(request.prompt.systemInstruction);
        SensitiveStringScrubber userInstructionScrubber(request.prompt.userInstruction);
        bool cancelledBeforeGeneration = false;
        {
            std::lock_guard<std::mutex> lock(stateMutex_);
            if (state_ != WorkerSessionState::OperationActive ||
                !activeIdentity_ ||
                !IdentitiesMatch(*activeIdentity_, request.identity))
            {
                cancelledBeforeGeneration = true;
            }
            else
            {
                generationStarted_ = true;
                cancelledBeforeGeneration = stopping_ || cancellationRequested_;
            }
        }

        if (cancelledBeforeGeneration)
        {
            PublishGenerationResult(
                request.identity,
                { TextGenerationStatus::Cancelled, {}, 0 });
            return;
        }

        {
            std::lock_guard<std::mutex> lock(stateMutex_);
            cancelledBeforeGeneration = stopping_ || cancellationRequested_;
        }

        if (cancelledBeforeGeneration)
        {
            PublishGenerationResult(
                request.identity,
                { TextGenerationStatus::Cancelled, {}, 0 });
            return;
        }

        TextGenerationResult result = backend_->Generate(
            request.identity.workerRequestToken,
            request.prompt.systemInstruction,
            request.prompt.userInstruction,
            [this, identity = request.identity]()
            {
                std::lock_guard<std::mutex> lock(stateMutex_);
                if (state_ != WorkerSessionState::OperationActive ||
                    !activeIdentity_ ||
                    !IdentitiesMatch(*activeIdentity_, identity))
                {
                    return true;
                }

                return stopping_ || cancellationRequested_;
            });

        PublishGenerationResult(request.identity, std::move(result));
    }

    void TextInferenceWorkerSession::PublishGenerationResult(const WorkerOperationIdentity& identity, TextGenerationResult result)
    {
        std::lock_guard<std::mutex> commandLock(commandMutex_);
        SensitiveStringScrubber resultScrubber(result.generatedText);
        WorkerProtocolFrame terminalFrame = WorkerProtocolFrame::Operation(WorkerMessageType::OperationCancelled, identity, {});

        switch (result.status)
        {
            case TextGenerationStatus::Completed:
                if (result.generatedText.empty() ||
                    result.generatedText.size() > WorkerProtocolCodec::AbsoluteMaximumPayloadBytes)
                {
                    terminalFrame = WorkerProtocolFrame::Operation(
                        WorkerMessageType::Error,
                        identity,
                        EncodeFixedError(static_cast<std::uint32_t>(WorkerOperationError::NativeFailure)));
                }
                else
                {
                    terminalFrame = WorkerProtocolFrame::Operation(
                        WorkerMessageType::PolishedTranscript,
                        identity,
                        { result.generatedText.begin(), result.generatedText.end() });
                }
                break;

            case TextGenerationStatus::Cancelled:
                break;

            case TextGenerationStatus::ContextLimitExceeded:
                terminalFrame = WorkerProtocolFrame::Operation(
                    WorkerMessageType::Error,
                    identity,
                    EncodeFixedError(static_cast<std::uint32_t>(WorkerOperationError::ContextLimitExceeded)));
                break;

            case TextGenerationStatus::OutputLimitReached:
                terminalFrame = WorkerProtocolFrame::Operation(
                    WorkerMessageType::Error,
                    identity,
                    EncodeFixedError(static_cast<std::uint32_t>(WorkerOperationError::OutputLimitReached)));
                break;

            case TextGenerationStatus::Busy:
                terminalFrame = WorkerProtocolFrame::Operation(
                    WorkerMessageType::Error,
                    identity,
                    EncodeFixedError(static_cast<std::uint32_t>(WorkerOperationError::BackendBusy)));
                break;

            case TextGenerationStatus::Closing:
                terminalFrame = WorkerProtocolFrame::Operation(
                    WorkerMessageType::Error,
                    identity,
                    EncodeFixedError(static_cast<std::uint32_t>(WorkerOperationError::BackendClosing)));
                break;

            case TextGenerationStatus::NativeFailure:
                terminalFrame = WorkerProtocolFrame::Operation(
                    WorkerMessageType::Error,
                    identity,
                    EncodeFixedError(static_cast<std::uint32_t>(WorkerOperationError::NativeFailure)));
                break;
        }
        SensitiveBytesScrubber terminalPayloadScrubber(terminalFrame.payload);

        {
            std::lock_guard<std::mutex> lock(stateMutex_);
            if (stopping_ ||
                state_ != WorkerSessionState::OperationActive ||
                !activeIdentity_ ||
                !IdentitiesMatch(*activeIdentity_, identity))
            {
                return;
            }
        }

        WriteFrame(terminalFrame);

        {
            std::lock_guard<std::mutex> lock(stateMutex_);
            lastTerminalIdentity_ = identity;
            activeIdentity_.reset();
            generationStarted_ = false;
            cancellationRequested_ = false;
            cancellationAcknowledged_ = false;
            state_ = WorkerSessionState::Idle;
        }
    }

    void TextInferenceWorkerSession::WriteFrame(const WorkerProtocolFrame& frame)
    {
        frameWriter_(frame);
    }

    [[noreturn]] void TextInferenceWorkerSession::Reject(WorkerSessionFailure failure)
    {
        throw WorkerSessionException(failure);
    }

    bool TextInferenceWorkerSession::IdentitiesMatch(const WorkerOperationIdentity& left, const WorkerOperationIdentity& right) noexcept
    {
        return left.clientSessionIdentifier == right.clientSessionIdentifier &&
            left.operationIdentifier == right.operationIdentifier &&
            left.privacy == right.privacy &&
            left.workerRequestToken == right.workerRequestToken;
    }

    bool TextInferenceWorkerSession::MatchesLastTerminalIdentity(const WorkerProtocolFrame& frame) const noexcept
    {
        std::lock_guard<std::mutex> lock(stateMutex_);
        return lastTerminalIdentity_.has_value() &&
            IdentitiesMatch(*lastTerminalIdentity_, frame.identity);
    }

    std::vector<std::uint8_t> TextInferenceWorkerSession::EncodeFixedError(std::uint32_t category)
    {
        return
        {
            static_cast<std::uint8_t>((category >> 24) & 0xFF),
            static_cast<std::uint8_t>((category >> 16) & 0xFF),
            static_cast<std::uint8_t>((category >> 8) & 0xFF),
            static_cast<std::uint8_t>(category & 0xFF)
        };
    }
}
