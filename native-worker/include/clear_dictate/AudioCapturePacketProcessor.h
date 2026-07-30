#pragma once

#include "clear_dictate/BoundedAudioBlockQueue.h"

#include <cstddef>
#include <cstdint>

namespace clear_dictate
{
    /**
     * Describes one complete capture packet. The sample pointer remains valid
     * only for the synchronous Process call.
     *
     * The samples are already 16-kilohertz mono 32-bit floating point because
     * the Windows shared audio engine performs the requested conversion.
     */
    struct AudioCapturePacket final
    {
        const float* samples = nullptr;
        std::size_t frameCount = 0;
        bool isSilent = false;
        bool hasDataDiscontinuity = false;
        bool hasInvalidTimestamp = false;
        std::uint64_t devicePosition = 0;
        std::uint64_t performanceCounterPosition = 0;
    };

    enum class AudioCapturePacketResult
    {
        Accepted,
        InvalidPacket,
        DataDiscontinuity,
        InvalidTimestamp,
        NonMonotonicPosition,
        QueueOverflow
    };

    /**
     * Applies fail-closed timing and overflow rules before publishing capture
     * packets to the single-producer/single-consumer audio queue.
     */
    class AudioCapturePacketProcessor final
    {
    public:
        explicit AudioCapturePacketProcessor(BoundedAudioBlockQueue& queue) noexcept;

        AudioCapturePacketResult Process(const AudioCapturePacket& packet) noexcept;

    private:
        BoundedAudioBlockQueue& queue_;
        bool hasAcceptedPacket_ = false;
        std::uint64_t expectedDevicePosition_ = 0;
        std::uint64_t lastPerformanceCounterPosition_ = 0;
    };
}
