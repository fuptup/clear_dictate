#pragma once

#include "clear_dictate/TextGenerationPolicy.h"

#include <cstdint>
#include <filesystem>
#include <memory>
#include <string>

namespace clear_dictate
{
    enum class TextGenerationStatus
    {
        Completed,
        Cancelled,
        ContextLimitExceeded,
        OutputLimitReached,
        Busy,
        Closing,
        NativeFailure
    };

    /**
     * Result returned by the local text model boundary.
     *
     * Native error details are intentionally not included because upstream diagnostics
     * can contain prompt fragments. The worker records only the status and token count.
     */
    struct TextGenerationResult final
    {
        TextGenerationStatus status;
        std::string generatedText;
        std::int32_t generatedTokenCount;
    };

    /**
     * Owns one llama.cpp model and context for the worker's lifetime.
     *
     * Generation calls are serialized because the context is stateful. Cancellation
     * remains lock-free so another worker thread can interrupt a long decode operation.
     */
    class LlamaTextEngine final
    {
    public:
        LlamaTextEngine(const std::filesystem::path& modelPath, std::int32_t inferenceThreadCount);
        ~LlamaTextEngine();

        LlamaTextEngine(const LlamaTextEngine&) = delete;
        LlamaTextEngine& operator=(const LlamaTextEngine&) = delete;
        LlamaTextEngine(LlamaTextEngine&&) = delete;
        LlamaTextEngine& operator=(LlamaTextEngine&&) = delete;

        TextGenerationResult Generate(std::uint64_t requestIdentifier, const std::string& systemInstruction, const std::string& userInstruction);
        bool Cancel(std::uint64_t requestIdentifier) noexcept;
        void Close() noexcept;

    private:
        class Implementation;
        std::shared_ptr<Implementation> implementation_;
    };
}
