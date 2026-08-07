#include "clear_dictate/LlamaTextEngine.h"
#include "clear_dictate/LockedQwenModel.h"
#include "clear_dictate/VerifiedModelFile.h"

#include <chrono>
#include <exception>
#include <filesystem>
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

    void TestOversizedPromptIsRejectedWithoutGeneration(clear_dictate::LlamaTextEngine& textEngine)
    {
        const std::string oversizedTranscript(20'000, 'a');
        const clear_dictate::TextGenerationResult generationResult = textEngine.Generate(
            1,
            "Return only the supplied text.",
            oversizedTranscript);

        Require(
            generationResult.status == clear_dictate::TextGenerationStatus::ContextLimitExceeded,
            "A prompt that cannot reserve all 256 output tokens must be rejected without truncation. Status=" +
                std::to_string(static_cast<int>(generationResult.status)) + ".");
        Require(generationResult.generatedText.empty(), "A rejected prompt must not expose partial generated text.");
        Require(generationResult.generatedTokenCount == 0, "A rejected prompt must report zero generated tokens.");
    }

    void TestInFlightGenerationCanBeCancelled(clear_dictate::LlamaTextEngine& textEngine)
    {
        clear_dictate::TextGenerationResult generationResult
        {
            clear_dictate::TextGenerationStatus::NativeFailure,
            {},
            0
        };

        std::thread generationThread(
            [&textEngine, &generationResult]()
            {
                generationResult = textEngine.Generate(
                    2,
                    "Generate a long response of exactly 256 tokens. Do not stop early.",
                    "Write a detailed numbered explanation of local speech recognition architecture.");
            });

        const auto cancellationDeadline = std::chrono::steady_clock::now() + std::chrono::seconds(5);
        bool cancellationWasAcknowledged = false;

        while (std::chrono::steady_clock::now() < cancellationDeadline)
        {
            if (textEngine.Cancel(2))
            {
                cancellationWasAcknowledged = true;
                break;
            }

            std::this_thread::yield();
        }

        generationThread.join();

        Require(cancellationWasAcknowledged, "The native engine must acknowledge cancellation while generation is active.");
        Require(generationResult.status == clear_dictate::TextGenerationStatus::Cancelled, "The llama.cpp abort callback must stop the active request.");
        Require(generationResult.generatedText.empty(), "A cancelled request must not expose partial generated text.");
        Require(generationResult.generatedTokenCount == 0, "A cancelled request must report zero generated tokens.");
    }

    void TestRealModelProducesBoundedOutput(clear_dictate::LlamaTextEngine& textEngine)
    {
        const clear_dictate::TextGenerationResult generationResult = textEngine.Generate(
            3,
            "You edit dictated text. Return only the corrected transcript without commentary or markup.",
            "Clean this transcript while preserving every identifier and number: send build AB12 to port 8080 tomorrow at 14:30");

        Require(
            generationResult.status == clear_dictate::TextGenerationStatus::Completed,
            "The pinned text model must complete a real generation request. Status=" + std::to_string(static_cast<int>(generationResult.status)) +
                ", generated tokens=" + std::to_string(generationResult.generatedTokenCount) + ".");
        Require(!generationResult.generatedText.empty(), "The pinned text model must return non-empty text.");
        Require(
            generationResult.generatedTokenCount <= clear_dictate::TextGenerationLimits::MaximumGeneratedTokenCount,
            "The text engine must enforce the 256-token output ceiling.");
    }

    int RunAllTests(int argumentCount, char** argumentValues)
    {
        if (argumentCount != 2)
        {
            throw std::invalid_argument("Expected one path to the pinned text model.");
        }

        const std::filesystem::path modelPath(argumentValues[1]);
        Require(std::filesystem::is_regular_file(modelPath), "The pinned text model file does not exist.");
        const clear_dictate::ModelFileExpectation expectation
        {
            clear_dictate::LockedQwenModelByteCount,
            clear_dictate::LockedQwenModelSha256
        };
        clear_dictate::VerifiedModelFile verifiedModelFile(modelPath.u8string(), expectation);
        clear_dictate::LlamaTextEngine textEngine(verifiedModelFile.Get(), 4);
        TestOversizedPromptIsRejectedWithoutGeneration(textEngine);
        TestInFlightGenerationCanBeCancelled(textEngine);
        TestRealModelProducesBoundedOutput(textEngine);
        return 0;
    }
}

int main(int argumentCount, char** argumentValues)
{
    try
    {
        return RunAllTests(argumentCount, argumentValues);
    }
    catch (const std::exception& exception)
    {
        std::cerr << "ClearDictate llama text engine tests failed: " << exception.what() << '\n';
        return 1;
    }
}
