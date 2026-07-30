#include "clear_dictate/AudioCapturePacketProcessor.h"

#include <limits>

namespace clear_dictate
{
    AudioCapturePacketProcessor::AudioCapturePacketProcessor(BoundedAudioBlockQueue& queue) noexcept
        : queue_(queue)
    {
    }

    AudioCapturePacketResult AudioCapturePacketProcessor::Process(const AudioCapturePacket& packet) noexcept
    {
        if (packet.frameCount == 0 || (!packet.isSilent && packet.samples == nullptr))
        {
            return AudioCapturePacketResult::InvalidPacket;
        }
        if (packet.hasInvalidTimestamp)
        {
            return AudioCapturePacketResult::InvalidTimestamp;
        }
        if (hasAcceptedPacket_ && packet.hasDataDiscontinuity)
        {
            return AudioCapturePacketResult::DataDiscontinuity;
        }
        if (hasAcceptedPacket_ && packet.devicePosition != expectedDevicePosition_)
        {
            return packet.devicePosition < expectedDevicePosition_
                ? AudioCapturePacketResult::NonMonotonicPosition
                : AudioCapturePacketResult::DataDiscontinuity;
        }
        if (hasAcceptedPacket_ && packet.performanceCounterPosition <= lastPerformanceCounterPosition_)
        {
            return AudioCapturePacketResult::NonMonotonicPosition;
        }
        if (packet.devicePosition > std::numeric_limits<std::uint64_t>::max() - packet.frameCount)
        {
            return AudioCapturePacketResult::NonMonotonicPosition;
        }

        const AudioBlockPushResult pushResult = queue_.TryPushSamples(packet.samples, packet.frameCount, packet.isSilent);
        if (pushResult == AudioBlockPushResult::Overflow)
        {
            return AudioCapturePacketResult::QueueOverflow;
        }
        if (pushResult != AudioBlockPushResult::Accepted)
        {
            return AudioCapturePacketResult::InvalidPacket;
        }

        hasAcceptedPacket_ = true;
        expectedDevicePosition_ = packet.devicePosition + packet.frameCount;
        lastPerformanceCounterPosition_ = packet.performanceCounterPosition;
        return AudioCapturePacketResult::Accepted;
    }
}
