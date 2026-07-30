#pragma once

#include "clear_dictate/TextGenerationBackend.h"
#include "clear_dictate/TextGenerationPolicy.h"

#include <cstdint>
#include <cstdio>
#include <functional>
#include <memory>
#include <string>

namespace clear_dictate
{
    /**
     * Owns one llama.cpp model and context for the worker's lifetime.
     *
     * Generation calls are serialized because the context is stateful. Cancellation
     * remains lock-free so another worker thread can interrupt a long decode operation.
     */
    class LlamaTextEngine final : public TextGenerationBackend
    {
    public:
        LlamaTextEngine(std::FILE* verifiedModelFile, std::int32_t inferenceThreadCount);
        ~LlamaTextEngine();

        LlamaTextEngine(const LlamaTextEngine&) = delete;
        LlamaTextEngine& operator=(const LlamaTextEngine&) = delete;
        LlamaTextEngine(LlamaTextEngine&&) = delete;
        LlamaTextEngine& operator=(LlamaTextEngine&&) = delete;

        TextGenerationResult Generate(std::uint64_t requestIdentifier, const std::string& systemInstruction, const std::string& userInstruction);
        TextGenerationResult Generate(
            std::uint64_t requestIdentifier,
            const std::string& systemInstruction,
            const std::string& userInstruction,
            const std::function<bool()>& cancellationRequestedAtStart) override;
        bool Cancel(std::uint64_t requestIdentifier) noexcept override;
        void Close() noexcept override;

    private:
        class Implementation;
        std::shared_ptr<Implementation> implementation_;
    };
}
