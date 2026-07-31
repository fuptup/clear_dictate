#include "clear_dictate/WindowsAudioSessionCapture.h"

#include <atomic>
#include <chrono>
#include <exception>
#include <iostream>
#include <stdexcept>
#include <string>
#include <thread>

namespace
{
    constexpr auto MaximumCancelSignalDuration = std::chrono::milliseconds(250);

    int RunSmokeTest(int argumentCount, char** arguments)
    {
        if (argumentCount != 2 || std::string(arguments[1]) != "--allow-live-microphone-cancel-overlap")
        {
            std::cerr << "Refusing to open the microphone without --allow-live-microphone-cancel-overlap.\n";
            return 2;
        }

        clear_dictate::BoundedAudioBlockQueue queue(512, 160);
        clear_dictate::WindowsAudioSessionCapture capture(queue);
        if (capture.Start(L"") != clear_dictate::WindowsCaptureError::None)
        {
            throw std::runtime_error("The live microphone could not start.");
        }

        std::cout << "LIVE MICROPHONE CANCEL-OVERLAP CHECK ACTIVE. AUDIO IS NOT SAVED.\n";
        std::this_thread::sleep_for(std::chrono::milliseconds(250));

        std::atomic<bool> joinAttemptStarted { false };
        std::thread producerJoiner(
            [&capture, &joinAttemptStarted]()
            {
                joinAttemptStarted.store(true, std::memory_order_release);
                capture.JoinProducer();
            });

        while (!joinAttemptStarted.load(std::memory_order_acquire))
        {
            std::this_thread::yield();
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(50));

        const auto cancelSignalStart = std::chrono::steady_clock::now();
        capture.RequestCancel();
        const auto cancelSignalDuration = std::chrono::steady_clock::now() - cancelSignalStart;
        producerJoiner.join();
        queue.DiscardAndScrub();

        if (cancelSignalDuration > MaximumCancelSignalDuration)
        {
            throw std::runtime_error("The nonblocking cancellation signal waited behind producer joining.");
        }
        if (capture.State() != clear_dictate::WindowsCaptureState::Cancelled)
        {
            throw std::runtime_error("The overlapping cancellation did not become terminal.");
        }

        std::cout << "Cancellation returned without waiting for producer join and all queued audio was scrubbed.\n";
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
        std::cerr << "ClearDictate live microphone cancel-overlap smoke failed: " << exception.what() << '\n';
        return 1;
    }
}
