#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>

namespace clear_dictate
{
    /**
     * Fixed resource limits for local transcript polishing.
     *
     * The output allowance is reserved before inference begins. ClearDictate rejects
     * oversized prompts instead of silently discarding dictation or instructions.
     */
    struct TextGenerationLimits final
    {
        static constexpr std::int32_t ContextTokenCount = 2048;
        static constexpr std::int32_t MaximumGeneratedTokenCount = 256;
        static constexpr std::int32_t MaximumBatchTokenCount = 512;
        static constexpr std::int32_t MaximumInferenceThreadCount = 64;
        static constexpr std::size_t MaximumRequestByteCount = 64 * 1024;
    };

    enum class PromptBudgetDecision
    {
        Accepted,
        Rejected
    };

    enum class RequestFinalization
    {
        Completed,
        Cancelled,
        NotActive
    };

    /**
     * Determines whether a tokenized prompt leaves the full, fixed output allowance.
     */
    PromptBudgetDecision EvaluatePromptBudget(std::int32_t promptTokenCount) noexcept;

    /**
     * Coordinates request-specific cancellation without putting a lock in the model's
     * abort callback. Request identifiers must be non-zero and unique for the worker's
     * lifetime, preventing a delayed cancellation from targeting a later operation.
     */
    class RequestCancellationController final
    {
    public:
        static constexpr std::uint64_t MaximumRequestIdentifier = (std::uint64_t { 1 } << 63) - 1;

        void BeginRequest(std::uint64_t requestIdentifier);
        bool CancelRequest(std::uint64_t requestIdentifier) noexcept;
        bool CancelActiveRequest() noexcept;
        RequestFinalization FinishRequest(std::uint64_t requestIdentifier) noexcept;
        void EndRequest(std::uint64_t requestIdentifier) noexcept;
        bool ShouldAbort() const noexcept;

    private:
        static constexpr std::uint64_t NoRequest = 0;
        static constexpr std::uint64_t CancellationRequestedMask = std::uint64_t { 1 } << 63;
        static constexpr std::uint64_t RequestIdentifierMask = ~CancellationRequestedMask;

        // One atomic word makes request publication and cancellation state indivisible.
        std::atomic<std::uint64_t> requestState_ { NoRequest };
    };
}
