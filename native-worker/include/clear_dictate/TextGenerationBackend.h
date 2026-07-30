#pragma once

#include <cstdint>
#include <functional>
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
     * Carries only the generated text and fixed diagnostic categories across the
     * native inference boundary. Upstream library error text is deliberately excluded.
     */
    struct TextGenerationResult final
    {
        TextGenerationStatus status;
        std::string generatedText;
        std::int32_t generatedTokenCount;
    };

    /**
     * Abstracts the stateful text model so worker sequencing can be tested without
     * loading a multi-hundred-megabyte model.
     */
    class TextGenerationBackend
    {
    public:
        virtual ~TextGenerationBackend() = default;

        virtual TextGenerationResult Generate(
            std::uint64_t requestIdentifier,
            const std::string& systemInstruction,
            const std::string& userInstruction,
            const std::function<bool()>& cancellationRequestedAtStart) = 0;
        virtual bool Cancel(std::uint64_t requestIdentifier) noexcept = 0;
        virtual void Close() noexcept = 0;
    };
}
