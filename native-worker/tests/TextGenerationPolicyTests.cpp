#include "clear_dictate/TextGenerationPolicy.h"

#include <atomic>
#include <exception>
#include <iostream>
#include <stdexcept>
#include <string>
#include <thread>

namespace
{
    void Require(bool condition, const std::string& failureMessage)
    {
        if (!condition)
        {
            throw std::runtime_error(failureMessage);
        }
    }

    void TestPromptAtExactBudgetIsAccepted()
    {
        const clear_dictate::PromptBudgetDecision decision =
            clear_dictate::EvaluatePromptBudget(clear_dictate::TextGenerationLimits::ContextTokenCount - clear_dictate::TextGenerationLimits::MaximumGeneratedTokenCount);

        Require(decision == clear_dictate::PromptBudgetDecision::Accepted, "The exact prompt-plus-output context budget must be accepted.");
    }

    void TestPromptAboveBudgetIsRejected()
    {
        const clear_dictate::PromptBudgetDecision decision =
            clear_dictate::EvaluatePromptBudget(clear_dictate::TextGenerationLimits::ContextTokenCount - clear_dictate::TextGenerationLimits::MaximumGeneratedTokenCount + 1);

        Require(decision == clear_dictate::PromptBudgetDecision::Rejected, "A prompt that leaves fewer than 256 output tokens must be rejected without truncation.");
    }

    void TestInvalidPromptCountIsRejected()
    {
        Require(
            clear_dictate::EvaluatePromptBudget(-1) == clear_dictate::PromptBudgetDecision::Rejected,
            "A negative token count must be rejected.");
    }

    void TestCancellationOnlyTargetsTheActiveRequest()
    {
        clear_dictate::RequestCancellationController cancellationController;
        cancellationController.BeginRequest(41);

        Require(!cancellationController.CancelRequest(40), "A stale cancellation must not affect the active request.");
        Require(!cancellationController.ShouldAbort(), "A stale cancellation must leave the active request running.");
        Require(cancellationController.CancelRequest(41), "The active request cancellation must be acknowledged.");
        Require(cancellationController.ShouldAbort(), "The active request must observe its cancellation.");
    }

    void TestEndingARequestClearsItsCancellation()
    {
        clear_dictate::RequestCancellationController cancellationController;
        cancellationController.BeginRequest(51);
        cancellationController.CancelRequest(51);
        cancellationController.EndRequest(51);
        cancellationController.BeginRequest(52);

        Require(!cancellationController.ShouldAbort(), "Cancellation state must not leak into the next request.");
    }

    void TestMismatchedEndCannotClearTheActiveRequest()
    {
        clear_dictate::RequestCancellationController cancellationController;
        cancellationController.BeginRequest(61);
        cancellationController.EndRequest(60);

        Require(cancellationController.CancelRequest(61), "A mismatched end must not clear the active request.");
    }

    void TestIdentifierBitsReservedForCancellationAreRejected()
    {
        clear_dictate::RequestCancellationController cancellationController;
        bool rejectedReservedIdentifier = false;

        try
        {
            cancellationController.BeginRequest(clear_dictate::RequestCancellationController::MaximumRequestIdentifier + 1);
        }
        catch (const std::invalid_argument&)
        {
            rejectedReservedIdentifier = true;
        }

        Require(rejectedReservedIdentifier, "The cancellation bit must never be accepted as part of a request identifier.");
    }

    void TestCompletionLinearizesAgainstCancellation()
    {
        clear_dictate::RequestCancellationController completionFirstController;
        completionFirstController.BeginRequest(71);

        Require(
            completionFirstController.FinishRequest(71) == clear_dictate::RequestFinalization::Completed,
            "A completion that wins the state transition must be reported as completed.");
        Require(!completionFirstController.CancelRequest(71), "Cancellation after completion is unpublished must be rejected.");

        clear_dictate::RequestCancellationController cancellationFirstController;
        cancellationFirstController.BeginRequest(72);
        Require(cancellationFirstController.CancelRequest(72), "Cancellation must be acknowledged for the active request.");
        Require(
            cancellationFirstController.FinishRequest(72) == clear_dictate::RequestFinalization::Cancelled,
            "An acknowledged cancellation must win over completion.");
    }

    void TestConcurrentCompletionAndCancellationHaveOneConsistentWinner()
    {
        constexpr std::uint64_t FirstRequestIdentifier = 1000;
        constexpr std::uint64_t IterationCount = 1000;
        clear_dictate::RequestCancellationController cancellationController;

        for (std::uint64_t iteration = 0; iteration < IterationCount; ++iteration)
        {
            const std::uint64_t requestIdentifier = FirstRequestIdentifier + iteration;
            cancellationController.BeginRequest(requestIdentifier);
            std::atomic<bool> startRace { false };
            bool cancellationWasAcknowledged = false;
            clear_dictate::RequestFinalization finalization = clear_dictate::RequestFinalization::NotActive;

            std::thread cancellationThread(
                [&]()
                {
                    while (!startRace.load(std::memory_order_acquire))
                    {
                        std::this_thread::yield();
                    }

                    cancellationWasAcknowledged = cancellationController.CancelRequest(requestIdentifier);
                });

            std::thread completionThread(
                [&]()
                {
                    while (!startRace.load(std::memory_order_acquire))
                    {
                        std::this_thread::yield();
                    }

                    finalization = cancellationController.FinishRequest(requestIdentifier);
                });

            startRace.store(true, std::memory_order_release);
            cancellationThread.join();
            completionThread.join();

            if (cancellationWasAcknowledged)
            {
                Require(finalization == clear_dictate::RequestFinalization::Cancelled, "Acknowledged cancellation and completion must not both win.");
            }
            else
            {
                Require(finalization == clear_dictate::RequestFinalization::Completed, "Rejected cancellation requires completion to have won.");
            }
        }
    }

    int RunAllTests()
    {
        TestPromptAtExactBudgetIsAccepted();
        TestPromptAboveBudgetIsRejected();
        TestInvalidPromptCountIsRejected();
        TestCancellationOnlyTargetsTheActiveRequest();
        TestEndingARequestClearsItsCancellation();
        TestMismatchedEndCannotClearTheActiveRequest();
        TestIdentifierBitsReservedForCancellationAreRejected();
        TestCompletionLinearizesAgainstCancellation();
        TestConcurrentCompletionAndCancellationHaveOneConsistentWinner();
        return 0;
    }
}

int main()
{
    try
    {
        return RunAllTests();
    }
    catch (const std::exception& exception)
    {
        std::cerr << "ClearDictate native policy tests failed: " << exception.what() << '\n';
        return 1;
    }
}
