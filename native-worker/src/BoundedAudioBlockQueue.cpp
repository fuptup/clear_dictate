#include "clear_dictate/BoundedAudioBlockQueue.h"

#include <algorithm>
#include <limits>
#include <stdexcept>

namespace clear_dictate
{
    namespace
    {
        constexpr std::size_t MaximumUsableBlockCount = 4096;
        constexpr std::size_t MaximumSamplesPerBlock = 65536;
        constexpr std::size_t MaximumTotalPreallocatedSamples = 1048576;
    }

    BoundedAudioBlockQueue::AudioBlock::AudioBlock(std::size_t sampleCapacity)
        : samples(sampleCapacity, 0.0F)
    {
    }

    BoundedAudioBlockQueue::BoundedAudioBlockQueue(std::size_t usableBlockCount, std::size_t samplesPerBlock)
        : samplesPerBlock_(samplesPerBlock)
    {
        if (usableBlockCount == 0 ||
            usableBlockCount > MaximumUsableBlockCount ||
            samplesPerBlock == 0 ||
            samplesPerBlock > MaximumSamplesPerBlock ||
            samplesPerBlock > MaximumTotalPreallocatedSamples / (usableBlockCount + 1))
        {
            throw std::invalid_argument("The audio queue dimensions exceed the fixed real-time memory budget.");
        }

        const std::size_t physicalBlockCount = usableBlockCount + 1;
        blocks_.reserve(physicalBlockCount);
        for (std::size_t blockIndex = 0; blockIndex < physicalBlockCount; ++blockIndex)
        {
            blocks_.emplace_back(samplesPerBlock);
        }
    }

    BoundedAudioBlockQueue::~BoundedAudioBlockQueue()
    {
        DiscardAndScrub();
    }

    AudioBlockPushResult BoundedAudioBlockQueue::TryPushSamples(const float* samples, std::size_t sampleCount, bool isSilentPacket) noexcept
    {
        if (sampleCount == 0 || (!isSilentPacket && samples == nullptr))
        {
            return AudioBlockPushResult::InvalidInput;
        }

        const std::size_t requiredBlockCount = 1 + (sampleCount - 1) / samplesPerBlock_;
        std::size_t producerIndex = producerIndex_.load(std::memory_order_relaxed);
        const std::size_t consumerIndex = consumerIndex_.load(std::memory_order_acquire);
        if (requiredBlockCount > AvailableBlockCount(producerIndex, consumerIndex))
        {
            return AudioBlockPushResult::Overflow;
        }

        std::size_t sourceOffset = 0;
        while (sourceOffset < sampleCount)
        {
            AudioBlock& block = blocks_[producerIndex];
            const std::size_t samplesInBlock = std::min(samplesPerBlock_, sampleCount - sourceOffset);
            if (isSilentPacket)
            {
                std::fill_n(block.samples.begin(), samplesInBlock, 0.0F);
            }
            else
            {
                std::copy_n(samples + sourceOffset, samplesInBlock, block.samples.begin());
            }
            block.sampleCount = samplesInBlock;
            sourceOffset += samplesInBlock;
            producerIndex = (producerIndex + 1) % blocks_.size();
        }

        producerIndex_.store(producerIndex, std::memory_order_release);
        return AudioBlockPushResult::Accepted;
    }

    bool BoundedAudioBlockQueue::TryPop(float* destination, std::size_t destinationCapacity, std::size_t& sampleCount) noexcept
    {
        sampleCount = 0;
        if (destination == nullptr)
        {
            return false;
        }

        const std::size_t consumerIndex = consumerIndex_.load(std::memory_order_relaxed);
        if (consumerIndex == producerIndex_.load(std::memory_order_acquire))
        {
            return false;
        }

        AudioBlock& block = blocks_[consumerIndex];
        if (block.sampleCount > destinationCapacity)
        {
            return false;
        }

        sampleCount = block.sampleCount;
        std::copy_n(block.samples.begin(), sampleCount, destination);
        Scrub(block);
        consumerIndex_.store((consumerIndex + 1) % blocks_.size(), std::memory_order_release);
        return true;
    }

    void BoundedAudioBlockQueue::DiscardAndScrub() noexcept
    {
        for (AudioBlock& block : blocks_)
        {
            Scrub(block);
        }
        consumerIndex_.store(0, std::memory_order_relaxed);
        producerIndex_.store(0, std::memory_order_relaxed);
    }

    std::size_t BoundedAudioBlockQueue::SamplesPerBlock() const noexcept
    {
        return samplesPerBlock_;
    }

    std::size_t BoundedAudioBlockQueue::TotalSampleCapacity() const noexcept
    {
        return (blocks_.size() - 1) * samplesPerBlock_;
    }

    bool BoundedAudioBlockQueue::IsEmptyAndScrubbedForStart() const noexcept
    {
        return producerIndex_.load(std::memory_order_relaxed) == consumerIndex_.load(std::memory_order_relaxed) &&
            StorageContainsOnlyZeroesForVerification();
    }

    bool BoundedAudioBlockQueue::StorageContainsOnlyZeroesForVerification() const noexcept
    {
        return std::all_of(
            blocks_.begin(),
            blocks_.end(),
            [](const AudioBlock& block)
            {
                return block.sampleCount == 0 &&
                    std::all_of(block.samples.begin(), block.samples.end(), [](float sample) { return sample == 0.0F; });
            });
    }

    void BoundedAudioBlockQueue::Scrub(AudioBlock& block) noexcept
    {
        volatile float* writableSamples = block.samples.empty() ? nullptr : block.samples.data();
        for (std::size_t sampleIndex = 0; sampleIndex < block.samples.size(); ++sampleIndex)
        {
            writableSamples[sampleIndex] = 0.0F;
        }
        block.sampleCount = 0;
    }

    std::size_t BoundedAudioBlockQueue::AvailableBlockCount(std::size_t producerIndex, std::size_t consumerIndex) const noexcept
    {
        if (consumerIndex > producerIndex)
        {
            return consumerIndex - producerIndex - 1;
        }
        return blocks_.size() - (producerIndex - consumerIndex) - 1;
    }
}
