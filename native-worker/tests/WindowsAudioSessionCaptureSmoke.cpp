#include "clear_dictate/WindowsAudioSessionCapture.h"

#include <array>
#include <chrono>
#include <exception>
#include <iostream>
#include <stdexcept>
#include <string>
#include <thread>

namespace
{
    void ScrubSamples(std::array<float, 160>& samples) noexcept
    {
        volatile float* writableSamples = samples.data();
        for (std::size_t sampleIndex = 0; sampleIndex < samples.size(); ++sampleIndex)
        {
            writableSamples[sampleIndex] = 0.0F;
        }
    }

    int RunSmokeTest(int argumentCount, char** arguments)
    {
        if (argumentCount != 2 || std::string(arguments[1]) != "--allow-live-microphone-capture")
        {
            std::cerr << "Refusing to open the microphone without --allow-live-microphone-capture.\n";
            return 2;
        }

        clear_dictate::BoundedAudioBlockQueue queue(512, 160);
        clear_dictate::WindowsAudioSessionCapture capture(queue);
        const clear_dictate::WindowsCaptureError startError = capture.Start(L"");
        if (startError != clear_dictate::WindowsCaptureError::None)
        {
            std::cerr << "Microphone capture could not start; fixed error category "
                      << static_cast<int>(startError) << ".\n";
            return 1;
        }

        std::cout << "LIVE MICROPHONE CAPTURE ACTIVE FOR TWO SECONDS. AUDIO IS NOT SAVED.\n";
        std::this_thread::sleep_for(std::chrono::seconds(2));
        capture.StopAndJoinProducer();

        std::array<float, 160> consumerBuffer {};
        std::size_t copiedSampleCount = 0;
        std::uint64_t drainedFrameCount = 0;
        while (queue.TryPop(consumerBuffer.data(), consumerBuffer.size(), copiedSampleCount))
        {
            drainedFrameCount += copiedSampleCount;
            ScrubSamples(consumerBuffer);
        }
        queue.DiscardAndScrub();

        if (capture.State() != clear_dictate::WindowsCaptureState::Stopped)
        {
            std::cerr << "Microphone capture ended with fixed error category "
                      << static_cast<int>(capture.Error()) << ".\n";
            return 1;
        }
        if (drainedFrameCount == 0 || drainedFrameCount != capture.AcceptedFrameCount())
        {
            std::cerr << "Microphone capture produced an inconsistent frame count.\n";
            return 1;
        }

        std::cout << "Captured and immediately scrubbed " << drainedFrameCount << " frames.\n";
        return 0;
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
        std::cerr << "ClearDictate live microphone smoke test failed: " << exception.what() << '\n';
        return 1;
    }
}
