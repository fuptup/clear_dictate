#include "clear_dictate/TextGenerationPolicy.h"

#include <stdexcept>

namespace clear_dictate
{
    PromptBudgetDecision EvaluatePromptBudget(std::int32_t promptTokenCount) noexcept
    {
        if (promptTokenCount < 0)
        {
            return PromptBudgetDecision::Rejected;
        }

        const std::int32_t maximumPromptTokenCount =
            TextGenerationLimits::ContextTokenCount - TextGenerationLimits::MaximumGeneratedTokenCount;

        return promptTokenCount <= maximumPromptTokenCount
            ? PromptBudgetDecision::Accepted
            : PromptBudgetDecision::Rejected;
    }

    void RequestCancellationController::BeginRequest(std::uint64_t requestIdentifier)
    {
        if (requestIdentifier == NoRequest || requestIdentifier > MaximumRequestIdentifier)
        {
            throw std::invalid_argument("A native inference request identifier must be between 1 and 2^63 - 1.");
        }

        std::uint64_t expectedRequestState = NoRequest;
        if (!requestState_.compare_exchange_strong(
                expectedRequestState,
                requestIdentifier,
                std::memory_order_acq_rel,
                std::memory_order_acquire))
        {
            throw std::logic_error("Only one native inference request may execute at a time.");
        }
    }

    bool RequestCancellationController::CancelRequest(std::uint64_t requestIdentifier) noexcept
    {
        if (requestIdentifier == NoRequest || requestIdentifier > MaximumRequestIdentifier)
        {
            return false;
        }

        std::uint64_t expectedRequestState = requestIdentifier;
        const std::uint64_t cancelledRequestState = requestIdentifier | CancellationRequestedMask;

        if (requestState_.compare_exchange_strong(
                expectedRequestState,
                cancelledRequestState,
                std::memory_order_acq_rel,
                std::memory_order_acquire))
        {
            return true;
        }

        return expectedRequestState == cancelledRequestState;
    }

    bool RequestCancellationController::CancelActiveRequest() noexcept
    {
        std::uint64_t currentRequestState = requestState_.load(std::memory_order_acquire);

        while (currentRequestState != NoRequest)
        {
            if ((currentRequestState & CancellationRequestedMask) != 0)
            {
                return true;
            }

            const std::uint64_t cancelledRequestState = currentRequestState | CancellationRequestedMask;
            if (requestState_.compare_exchange_weak(
                    currentRequestState,
                    cancelledRequestState,
                    std::memory_order_acq_rel,
                    std::memory_order_acquire))
            {
                return true;
            }
        }

        return false;
    }

    RequestFinalization RequestCancellationController::FinishRequest(std::uint64_t requestIdentifier) noexcept
    {
        std::uint64_t expectedRequestState = requestIdentifier;
        if (requestState_.compare_exchange_strong(
                expectedRequestState,
                NoRequest,
                std::memory_order_acq_rel,
                std::memory_order_acquire))
        {
            return RequestFinalization::Completed;
        }

        expectedRequestState = requestIdentifier | CancellationRequestedMask;
        if (requestState_.compare_exchange_strong(
                expectedRequestState,
                NoRequest,
                std::memory_order_acq_rel,
                std::memory_order_acquire))
        {
            return RequestFinalization::Cancelled;
        }

        return RequestFinalization::NotActive;
    }

    void RequestCancellationController::EndRequest(std::uint64_t requestIdentifier) noexcept
    {
        std::uint64_t expectedRequestState = requestIdentifier;
        if (requestState_.compare_exchange_strong(
                expectedRequestState,
                NoRequest,
                std::memory_order_acq_rel,
                std::memory_order_acquire))
        {
            return;
        }

        expectedRequestState = requestIdentifier | CancellationRequestedMask;
        requestState_.compare_exchange_strong(
            expectedRequestState,
            NoRequest,
            std::memory_order_acq_rel,
            std::memory_order_acquire);
    }

    bool RequestCancellationController::ShouldAbort() const noexcept
    {
        return (requestState_.load(std::memory_order_acquire) & CancellationRequestedMask) != 0;
    }
}
