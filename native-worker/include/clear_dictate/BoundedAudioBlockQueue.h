#pragma once

#include <atomic>
#include <cstddef>
#include <vector>

namespace clear_dictate
{
    enum class AudioBlockPushResult
    {
        Accepted,
        Overflow,
        InvalidInput
    };

    /**
     * Preallocates a bounded single-producer/single-consumer audio queue.
     *
     * The capture thread never allocates or blocks. The recognition thread
     * copies each block into its own reusable buffer, after which the queue
     * scrubs the consumed slot. Both threads must be stopped and joined before
     * destruction or DiscardAndScrub.
     */
    class BoundedAudioBlockQueue final
    {
    public:
        BoundedAudioBlockQueue(std::size_t usableBlockCount, std::size_t samplesPerBlock);
        ~BoundedAudioBlockQueue();

        BoundedAudioBlockQueue(const BoundedAudioBlockQueue&) = delete;
        BoundedAudioBlockQueue& operator=(const BoundedAudioBlockQueue&) = delete;

        /**
         * Publishes one capture packet atomically, splitting it across blocks.
         *
         * Overflow is terminal for the current recording: the caller must stop
         * capture and report a fixed, non-sensitive error. It must never block,
         * retry a live capture packet, or silently discard the packet.
         */
        AudioBlockPushResult TryPushSamples(const float* samples, std::size_t sampleCount, bool isSilentPacket) noexcept;

        /**
         * Copies one queued block into caller-owned memory.
         *
         * The consumer owns the destination after this call and must overwrite
         * it after the speech engine has copied the samples, including every
         * cancellation and error path.
         */
        bool TryPop(float* destination, std::size_t destinationCapacity, std::size_t& sampleCount) noexcept;
        // Call only after producer and consumer threads have stopped.
        void DiscardAndScrub() noexcept;

        std::size_t SamplesPerBlock() const noexcept;
        // Test/diagnostic inspection; the queue must be quiescent.
        bool StorageContainsOnlyZeroesForVerification() const noexcept;

    private:
        struct AudioBlock final
        {
            explicit AudioBlock(std::size_t sampleCapacity);

            std::vector<float> samples;
            std::size_t sampleCount = 0;
        };

        static void Scrub(AudioBlock& block) noexcept;
        std::size_t AvailableBlockCount(std::size_t producerIndex, std::size_t consumerIndex) const noexcept;

        std::vector<AudioBlock> blocks_;
        std::size_t samplesPerBlock_;
        // Separate cache lines prevent capture and recognition from invalidating
        // the same line on every packet.
        alignas(64) std::atomic<std::size_t> producerIndex_ { 0 };
        alignas(64) std::atomic<std::size_t> consumerIndex_ { 0 };
    };

    static_assert(
        std::atomic<std::size_t>::is_always_lock_free,
        "The real-time audio queue requires lock-free size_t atomics.");
}
