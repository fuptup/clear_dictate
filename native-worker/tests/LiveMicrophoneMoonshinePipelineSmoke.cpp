#include "clear_dictate/BoundedAudioBlockQueue.h"
#include "clear_dictate/MoonshineSpeechEngine.h"
#include "clear_dictate/WindowsAudioSessionCapture.h"

#include <array>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <exception>
#include <iostream>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

namespace
{
    constexpr std::int32_t SpeechSampleRate = 16000;

    void ScrubSamples(std::array<float, 160>& samples) noexcept
    {
        volatile float* writableSamples = samples.data();
        for (std::size_t sampleIndex = 0; sampleIndex < samples.size(); ++sampleIndex)
        {
            writableSamples[sampleIndex] = 0.0F;
        }
    }

    void ScrubText(std::string& text) noexcept
    {
        volatile char* writableText = text.empty() ? nullptr : text.data();
        for (std::size_t characterIndex = 0; characterIndex < text.size(); ++characterIndex)
        {
            writableText[characterIndex] = '\0';
        }
        text.clear();
    }

    class SensitiveTextScrubber final
    {
    public:
        explicit SensitiveTextScrubber(std::string& text) noexcept
            : text_(text)
        {
        }

        ~SensitiveTextScrubber()
        {
            ScrubText(text_);
        }

    private:
        std::string& text_;
    };

    std::size_t TranscribeAndScrubChangedLines(clear_dictate::MoonshineSpeechEngine& speechEngine)
    {
        std::vector<clear_dictate::TranscriptDelta> deltas = speechEngine.TranscribeChangedLines(false);
        for (clear_dictate::TranscriptDelta& delta : deltas)
        {
            ScrubText(delta.text);
        }
        return deltas.size();
    }

    std::uint64_t DrainQueuedAudio(
        clear_dictate::BoundedAudioBlockQueue& queue,
        clear_dictate::MoonshineSpeechEngine& speechEngine,
        std::array<float, 160>& consumerBuffer)
    {
        std::uint64_t drainedFrameCount = 0;
        std::size_t copiedSampleCount = 0;
        while (queue.TryPop(consumerBuffer.data(), consumerBuffer.size(), copiedSampleCount))
        {
            try
            {
                speechEngine.AddAudio(consumerBuffer.data(), copiedSampleCount, SpeechSampleRate);
            }
            catch (...)
            {
                ScrubSamples(consumerBuffer);
                throw;
            }
            drainedFrameCount += copiedSampleCount;
            ScrubSamples(consumerBuffer);
        }
        return drainedFrameCount;
    }

    int RunSmokeTest(int argumentCount, char** arguments)
    {
        if (argumentCount < 3 ||
            argumentCount > 4 ||
            std::string(arguments[1]) != "--allow-live-microphone-moonshine-pipeline" ||
            (argumentCount == 4 && std::string(arguments[3]) != "--print-transcript"))
        {
            std::cerr
                << "Usage: clear_dictate_live_microphone_moonshine_pipeline_smoke "
                << "--allow-live-microphone-moonshine-pipeline <model-directory> [--print-transcript]\n";
            return 2;
        }

        const bool printTranscript = argumentCount == 4;
        clear_dictate::BoundedAudioBlockQueue queue(512, 160);
        clear_dictate::MoonshineSpeechEngine speechEngine(arguments[2]);
        clear_dictate::WindowsAudioSessionCapture capture(queue);
        std::array<float, 160> consumerBuffer {};
        bool speechStreamStarted = false;

        try
        {
            speechEngine.Start();
            speechStreamStarted = true;

            const clear_dictate::WindowsCaptureError startError = capture.Start(L"");
            if (startError != clear_dictate::WindowsCaptureError::None)
            {
                throw std::runtime_error("The live microphone could not start.");
            }

            std::cout << "LIVE MICROPHONE MOONSHINE PIPELINE ACTIVE FOR THREE SECONDS. AUDIO IS NOT SAVED.\n";
            const auto captureDeadline = std::chrono::steady_clock::now() + std::chrono::seconds(3);
            std::jthread captureDeadlineStopper(
                [&capture, captureDeadline](std::stop_token stopToken)
                {
                    while (std::chrono::steady_clock::now() < captureDeadline)
                    {
                        if (stopToken.stop_requested())
                        {
                            return;
                        }
                        std::this_thread::sleep_for(std::chrono::milliseconds(10));
                    }
                    if (!stopToken.stop_requested())
                    {
                        capture.StopAndJoinProducer();
                    }
                });
            std::uint64_t drainedFrameCount = 0;
            std::size_t partialDeltaCount = 0;

            while (true)
            {
                const clear_dictate::WindowsCaptureActivity activity = capture.WaitForActivity(100);
                if (activity == clear_dictate::WindowsCaptureActivity::WaitFailed)
                {
                    throw std::runtime_error("The live microphone activity wait failed.");
                }
                if (activity == clear_dictate::WindowsCaptureActivity::Terminal)
                {
                    break;
                }

                const std::uint64_t newlyDrainedFrameCount = DrainQueuedAudio(queue, speechEngine, consumerBuffer);
                drainedFrameCount += newlyDrainedFrameCount;
                if (newlyDrainedFrameCount != 0)
                {
                    partialDeltaCount += TranscribeAndScrubChangedLines(speechEngine);
                }
            }

            captureDeadlineStopper.request_stop();
            captureDeadlineStopper.join();
            drainedFrameCount += DrainQueuedAudio(queue, speechEngine, consumerBuffer);
            if (capture.State() != clear_dictate::WindowsCaptureState::Stopped)
            {
                throw std::runtime_error("The live microphone ended with a fixed capture error.");
            }
            if (drainedFrameCount == 0 || drainedFrameCount != capture.AcceptedFrameCount())
            {
                throw std::runtime_error("The Moonshine consumer did not receive every accepted microphone frame.");
            }

            std::string finalTranscript = speechEngine.StopAndFinish();
            SensitiveTextScrubber finalTranscriptScrubber(finalTranscript);
            speechStreamStarted = false;
            queue.DiscardAndScrub();

            std::cout << "Processed " << drainedFrameCount << " frames and "
                      << partialDeltaCount << " streaming transcript changes.\n";
            std::cout << "Final transcript contains " << finalTranscript.size() << " UTF-8 bytes.\n";
            if (printTranscript)
            {
                std::cout << "Final transcript: " << finalTranscript << '\n';
            }
            return 0;
        }
        catch (...)
        {
            capture.CancelAndJoinProducer();
            ScrubSamples(consumerBuffer);
            queue.DiscardAndScrub();
            if (speechStreamStarted)
            {
                try
                {
                    speechEngine.CancelAndDiscard();
                }
                catch (...)
                {
                }
            }
            throw;
        }
    }
}

int main(int argumentCount, char** arguments)
{
    try
    {
        return RunSmokeTest(argumentCount, arguments);
    }
    catch (const std::exception& exception)
    {
        std::cerr << "ClearDictate live microphone Moonshine pipeline smoke failed: " << exception.what() << '\n';
        return 1;
    }
}
