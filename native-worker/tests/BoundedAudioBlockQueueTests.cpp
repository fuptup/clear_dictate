#include "clear_dictate/BoundedAudioBlockQueue.h"

#include <array>
#include <atomic>
#include <chrono>
#include <exception>
#include <iostream>
#include <limits>
#include <stdexcept>
#include <string>
#include <thread>

namespace
{
    using clear_dictate::AudioBlockPushResult;

    void Require(bool condition, const std::string& failureMessage)
    {
        if (!condition)
        {
            throw std::runtime_error(failureMessage);
        }
    }

    void TestPacketsSplitWithoutChangingOrder()
    {
        clear_dictate::BoundedAudioBlockQueue queue(3, 4);
        Require(queue.TotalSampleCapacity() == 12, "The advertised sample capacity changed.");
        Require(queue.IsEmptyAndScrubbedForStart(), "A new queue must be empty and scrubbed.");
        const std::array<float, 7> source = { 1.0F, 2.0F, 3.0F, 4.0F, 5.0F, 6.0F, 7.0F };
        Require(queue.TryPushSamples(source.data(), source.size(), false) == AudioBlockPushResult::Accepted, "The packet should fit.");
        Require(!queue.IsEmptyAndScrubbedForStart(), "Queued microphone samples must prevent session reuse.");

        std::array<float, 4> destination {};
        std::size_t sampleCount = 0;
        Require(queue.TryPop(destination.data(), destination.size(), sampleCount), "The first block is missing.");
        Require(sampleCount == 4 && destination[0] == 1.0F && destination[3] == 4.0F, "The first block changed.");
        Require(queue.TryPop(destination.data(), destination.size(), sampleCount), "The second block is missing.");
        Require(sampleCount == 3 && destination[0] == 5.0F && destination[2] == 7.0F, "The second block changed.");
        Require(!queue.TryPop(destination.data(), destination.size(), sampleCount), "The queue should now be empty.");
        Require(queue.StorageContainsOnlyZeroesForVerification(), "Consumed audio must be scrubbed.");
    }

    void TestOverflowRejectsTheWholePacket()
    {
        clear_dictate::BoundedAudioBlockQueue queue(2, 4);
        const std::array<float, 5> firstPacket = { 1.0F, 2.0F, 3.0F, 4.0F, 5.0F };
        const std::array<float, 1> overflowPacket = { 9.0F };
        Require(queue.TryPushSamples(firstPacket.data(), firstPacket.size(), false) == AudioBlockPushResult::Accepted, "The first packet should fit.");
        Require(queue.TryPushSamples(overflowPacket.data(), overflowPacket.size(), false) == AudioBlockPushResult::Overflow, "Overflow must be explicit.");

        std::array<float, 4> destination {};
        std::size_t sampleCount = 0;
        Require(queue.TryPop(destination.data(), destination.size(), sampleCount), "The first original block is missing.");
        Require(destination[0] == 1.0F, "Overflow partially changed queued audio.");
        Require(queue.TryPop(destination.data(), destination.size(), sampleCount), "The second original block is missing.");
        Require(sampleCount == 1 && destination[0] == 5.0F, "Overflow partially changed the final block.");
    }

    void TestSilentPacketPreservesSampleCount()
    {
        clear_dictate::BoundedAudioBlockQueue queue(2, 4);
        Require(queue.TryPushSamples(nullptr, 6, true) == AudioBlockPushResult::Accepted, "A silent packet should not require a source pointer.");

        std::array<float, 4> destination = { 9.0F, 9.0F, 9.0F, 9.0F };
        std::size_t totalSamples = 0;
        std::size_t sampleCount = 0;
        while (queue.TryPop(destination.data(), destination.size(), sampleCount))
        {
            totalSamples += sampleCount;
            for (std::size_t sampleIndex = 0; sampleIndex < sampleCount; ++sampleIndex)
            {
                Require(destination[sampleIndex] == 0.0F, "Silent audio must contain explicit zero samples.");
            }
        }
        Require(totalSamples == 6, "Silent packet timing must be preserved.");
    }

    void TestDiscardScrubsQueuedAudio()
    {
        clear_dictate::BoundedAudioBlockQueue queue(2, 4);
        const std::array<float, 4> privateAudio = { 0.1F, -0.2F, 0.3F, -0.4F };
        Require(queue.TryPushSamples(privateAudio.data(), privateAudio.size(), false) == AudioBlockPushResult::Accepted, "Private audio should fit.");
        queue.DiscardAndScrub();
        Require(queue.StorageContainsOnlyZeroesForVerification(), "Discard must overwrite queued audio.");
    }

    void TestWraparoundPreservesMultiBlockPackets()
    {
        clear_dictate::BoundedAudioBlockQueue queue(3, 2);
        const std::array<float, 4> firstPacket = { 1.0F, 2.0F, 3.0F, 4.0F };
        const std::array<float, 2> secondPacket = { 5.0F, 6.0F };
        const std::array<float, 4> wrappedPacket = { 7.0F, 8.0F, 9.0F, 10.0F };

        Require(queue.TryPushSamples(firstPacket.data(), firstPacket.size(), false) == AudioBlockPushResult::Accepted, "The first multi-block packet should fit.");
        Require(queue.TryPushSamples(secondPacket.data(), secondPacket.size(), false) == AudioBlockPushResult::Accepted, "The queue should become exactly full.");

        std::array<float, 2> destination {};
        std::size_t sampleCount = 0;
        Require(queue.TryPop(destination.data(), destination.size(), sampleCount), "The first full-capacity block is missing.");
        Require(queue.TryPop(destination.data(), destination.size(), sampleCount), "The second full-capacity block is missing.");
        Require(queue.TryPushSamples(wrappedPacket.data(), wrappedPacket.size(), false) == AudioBlockPushResult::Accepted, "A multi-block packet should reuse wrapped slots.");

        Require(queue.TryPop(destination.data(), destination.size(), sampleCount) && destination[0] == 5.0F, "The pre-wrap block order changed.");
        Require(queue.TryPop(destination.data(), destination.size(), sampleCount) && destination[0] == 7.0F && destination[1] == 8.0F, "The first wrapped block changed.");
        Require(queue.TryPop(destination.data(), destination.size(), sampleCount) && destination[0] == 9.0F && destination[1] == 10.0F, "The second wrapped block changed.");
    }

    void TestFailedPopDoesNotConsumeAndDiscardAllowsReuse()
    {
        clear_dictate::BoundedAudioBlockQueue queue(2, 4);
        const std::array<float, 4> source = { 1.0F, 2.0F, 3.0F, 4.0F };
        Require(queue.TryPushSamples(source.data(), source.size(), false) == AudioBlockPushResult::Accepted, "The source block should fit.");

        std::array<float, 3> undersizedDestination {};
        std::size_t sampleCount = 99;
        Require(!queue.TryPop(undersizedDestination.data(), undersizedDestination.size(), sampleCount), "An undersized destination must fail.");
        Require(sampleCount == 0, "A failed pop must report zero copied samples.");

        std::array<float, 4> destination {};
        Require(queue.TryPop(destination.data(), destination.size(), sampleCount), "A failed pop must not consume the block.");
        Require(sampleCount == source.size() && destination == source, "The retained block changed after a failed pop.");

        Require(queue.TryPushSamples(source.data(), source.size(), false) == AudioBlockPushResult::Accepted, "The queue should accept data before discard.");
        queue.DiscardAndScrub();
        Require(queue.TryPushSamples(source.data(), source.size(), false) == AudioBlockPushResult::Accepted, "The queue should be reusable after quiescent discard.");
        Require(queue.TryPop(destination.data(), destination.size(), sampleCount) && destination == source, "Reuse after discard changed the block.");
    }

    void TestConcurrentProducerAndConsumerPreserveOrderAcrossWraparound()
    {
        clear_dictate::BoundedAudioBlockQueue queue(8, 1);
        constexpr std::size_t SampleTotal = 100000;
        const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(10);
        std::atomic<bool> orderingFailed { false };
        std::atomic<bool> timedOut { false };

        std::thread consumer(
            [&queue, &orderingFailed, &timedOut, deadline, SampleTotal]()
            {
                std::array<float, 1> destination {};
                for (std::size_t expectedValue = 1; expectedValue <= SampleTotal; ++expectedValue)
                {
                    std::size_t sampleCount = 0;
                    while (!queue.TryPop(destination.data(), destination.size(), sampleCount))
                    {
                        if (std::chrono::steady_clock::now() >= deadline)
                        {
                            timedOut.store(true, std::memory_order_release);
                            return;
                        }
                        std::this_thread::yield();
                    }
                    if (sampleCount != 1 || destination[0] != static_cast<float>(expectedValue))
                    {
                        orderingFailed.store(true, std::memory_order_relaxed);
                    }
                }
            });

        for (std::size_t sourceValue = 1; sourceValue <= SampleTotal; ++sourceValue)
        {
            const float sample = static_cast<float>(sourceValue);
            while (queue.TryPushSamples(&sample, 1, false) == AudioBlockPushResult::Overflow)
            {
                if (std::chrono::steady_clock::now() >= deadline)
                {
                    timedOut.store(true, std::memory_order_release);
                    break;
                }
                std::this_thread::yield();
            }
            if (timedOut.load(std::memory_order_acquire))
            {
                break;
            }
        }
        consumer.join();

        Require(!timedOut.load(std::memory_order_acquire), "Concurrent queue stress exceeded its ten-second deadline.");
        Require(!orderingFailed.load(std::memory_order_relaxed), "Concurrent queue ordering changed across wraparound.");
        Require(queue.StorageContainsOnlyZeroesForVerification(), "Concurrent consumption must scrub every slot.");
    }

    void TestUnsafeDimensionsAreRejected()
    {
        bool blockCountRejected = false;
        try
        {
            clear_dictate::BoundedAudioBlockQueue queue(4097, 1);
        }
        catch (const std::invalid_argument&)
        {
            blockCountRejected = true;
        }
        Require(blockCountRejected, "An unbounded block count must be rejected before allocation.");

        bool blockSizeRejected = false;
        try
        {
            clear_dictate::BoundedAudioBlockQueue queue(1, 65537);
        }
        catch (const std::invalid_argument&)
        {
            blockSizeRejected = true;
        }
        Require(blockSizeRejected, "An unbounded audio block size must be rejected before allocation.");

        bool totalMemoryRejected = false;
        try
        {
            clear_dictate::BoundedAudioBlockQueue queue(4096, 257);
        }
        catch (const std::invalid_argument&)
        {
            totalMemoryRejected = true;
        }
        Require(totalMemoryRejected, "A queue exceeding the total real-time memory budget must be rejected.");

        clear_dictate::BoundedAudioBlockQueue queue(1, 1);
        Require(
            queue.TryPushSamples(nullptr, std::numeric_limits<std::size_t>::max(), true) == AudioBlockPushResult::Overflow,
            "A maximum-size silent packet must fail safely without arithmetic overflow.");
        Require(queue.TryPushSamples(nullptr, 0, true) == AudioBlockPushResult::InvalidInput, "An empty packet must be identified as invalid.");
        Require(queue.TryPushSamples(nullptr, 1, false) == AudioBlockPushResult::InvalidInput, "Missing non-silent samples must be identified as invalid.");
    }

    int RunAllTests()
    {
        TestPacketsSplitWithoutChangingOrder();
        TestOverflowRejectsTheWholePacket();
        TestSilentPacketPreservesSampleCount();
        TestDiscardScrubsQueuedAudio();
        TestWraparoundPreservesMultiBlockPackets();
        TestFailedPopDoesNotConsumeAndDiscardAllowsReuse();
        TestConcurrentProducerAndConsumerPreserveOrderAcrossWraparound();
        TestUnsafeDimensionsAreRejected();
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
        std::cerr << "ClearDictate bounded audio queue tests failed: " << exception.what() << '\n';
        return 1;
    }
}
