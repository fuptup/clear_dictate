#include "clear_dictate/AudioCapturePacketProcessor.h"

#include <array>
#include <exception>
#include <iostream>
#include <stdexcept>
#include <string>

namespace
{
    void Require(bool condition, const std::string& failureMessage)
    {
        if (!condition)
        {
            throw std::runtime_error(failureMessage);
        }
    }

    clear_dictate::AudioCapturePacket MakePacket(const float* samples, std::size_t frameCount, std::uint64_t devicePosition, std::uint64_t performanceCounterPosition)
    {
        return { samples, frameCount, false, false, false, devicePosition, performanceCounterPosition };
    }

    void TestAcceptedPacketIsCopiedAndScrubbedAfterConsumption()
    {
        clear_dictate::BoundedAudioBlockQueue queue(2, 4);
        clear_dictate::AudioCapturePacketProcessor processor(queue);
        const std::array<float, 4> source = { 0.25F, -0.5F, 0.75F, -1.0F };

        Require(
            processor.Process(MakePacket(source.data(), source.size(), 100, 1000)) == clear_dictate::AudioCapturePacketResult::Accepted,
            "A valid packet should be accepted.");

        std::array<float, 4> destination {};
        std::size_t sampleCount = 0;
        Require(queue.TryPop(destination.data(), destination.size(), sampleCount), "The accepted packet is missing.");
        Require(sampleCount == source.size() && destination == source, "The accepted packet changed.");
        Require(queue.StorageContainsOnlyZeroesForVerification(), "The queue must scrub consumed microphone samples.");
    }

    void TestSilentPacketPreservesExactFrameCount()
    {
        clear_dictate::BoundedAudioBlockQueue queue(3, 4);
        clear_dictate::AudioCapturePacketProcessor processor(queue);
        clear_dictate::AudioCapturePacket packet = MakePacket(nullptr, 7, 200, 2000);
        packet.isSilent = true;

        Require(processor.Process(packet) == clear_dictate::AudioCapturePacketResult::Accepted, "Explicit endpoint silence should be accepted.");

        std::array<float, 4> destination = { 1.0F, 1.0F, 1.0F, 1.0F };
        std::size_t totalFrameCount = 0;
        std::size_t sampleCount = 0;
        while (queue.TryPop(destination.data(), destination.size(), sampleCount))
        {
            totalFrameCount += sampleCount;
            for (std::size_t sampleIndex = 0; sampleIndex < sampleCount; ++sampleIndex)
            {
                Require(destination[sampleIndex] == 0.0F, "Endpoint silence must become explicit zero samples.");
            }
        }
        Require(totalFrameCount == packet.frameCount, "Endpoint silence changed duration.");
    }

    void TestTimingFailuresAreTerminalResultsWithoutPublishing()
    {
        clear_dictate::BoundedAudioBlockQueue queue(2, 4);
        clear_dictate::AudioCapturePacketProcessor processor(queue);
        const std::array<float, 1> source = { 0.5F };

        clear_dictate::AudioCapturePacket firstPacket = MakePacket(source.data(), 1, 100, 1000);
        firstPacket.hasDataDiscontinuity = true;
        Require(
            processor.Process(firstPacket) == clear_dictate::AudioCapturePacketResult::Accepted,
            "Windows may mark the first packet discontinuous during stream startup.");

        clear_dictate::AudioCapturePacket discontinuousPacket = MakePacket(source.data(), 1, 101, 1001);
        discontinuousPacket.hasDataDiscontinuity = true;
        Require(
            processor.Process(discontinuousPacket) == clear_dictate::AudioCapturePacketResult::DataDiscontinuity,
            "A post-start discontinuity must fail closed.");

        clear_dictate::AudioCapturePacket invalidTimestampPacket = MakePacket(source.data(), 1, 102, 1002);
        invalidTimestampPacket.hasInvalidTimestamp = true;
        Require(
            processor.Process(invalidTimestampPacket) == clear_dictate::AudioCapturePacketResult::InvalidTimestamp,
            "An uncertain timestamp must fail closed.");

        Require(
            processor.Process(MakePacket(source.data(), 1, 100, 1003)) == clear_dictate::AudioCapturePacketResult::NonMonotonicPosition,
            "A repeated device position must fail closed.");
        Require(
            processor.Process(MakePacket(source.data(), 1, 102, 1003)) == clear_dictate::AudioCapturePacketResult::DataDiscontinuity,
            "An unflagged forward gap must fail closed.");
        Require(
            processor.Process(MakePacket(source.data(), 1, 101, 1000)) == clear_dictate::AudioCapturePacketResult::NonMonotonicPosition,
            "A repeated performance-counter position must fail closed.");

        std::array<float, 4> destination {};
        std::size_t sampleCount = 0;
        Require(queue.TryPop(destination.data(), destination.size(), sampleCount), "The initial valid packet is missing.");
        Require(!queue.TryPop(destination.data(), destination.size(), sampleCount), "Rejected timing packets must not be published.");
    }

    void TestOverflowAndInvalidInputRemainDistinct()
    {
        clear_dictate::BoundedAudioBlockQueue queue(1, 1);
        clear_dictate::AudioCapturePacketProcessor processor(queue);
        const std::array<float, 1> source = { 0.5F };

        Require(
            processor.Process(MakePacket(source.data(), 1, 1, 1)) == clear_dictate::AudioCapturePacketResult::Accepted,
            "The first packet should fill the queue.");
        Require(
            processor.Process(MakePacket(source.data(), 1, 2, 2)) == clear_dictate::AudioCapturePacketResult::QueueOverflow,
            "A full queue must report terminal overflow.");
        Require(
            processor.Process(MakePacket(nullptr, 1, 3, 3)) == clear_dictate::AudioCapturePacketResult::InvalidPacket,
            "Missing non-silent samples must remain a distinct programming error.");
    }

    int RunAllTests()
    {
        TestAcceptedPacketIsCopiedAndScrubbedAfterConsumption();
        TestSilentPacketPreservesExactFrameCount();
        TestTimingFailuresAreTerminalResultsWithoutPublishing();
        TestOverflowAndInvalidInputRemainDistinct();
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
        std::cerr << "ClearDictate capture packet processor tests failed: " << exception.what() << '\n';
        return 1;
    }
}
