#include "clear_dictate/CapturedAudioPayload.h"

#include <bit>
#include <limits>

namespace clear_dictate
{
    namespace
    {
        constexpr std::uint32_t PayloadMagic = 0x43444155;
        constexpr std::uint16_t PayloadVersion = 1;
        constexpr std::uint16_t Float32SampleFormat = 1;
        constexpr std::size_t HeaderByteCount = 16;
        constexpr std::size_t BytesPerSample = 4;

        void AppendUnsigned16(std::vector<std::uint8_t>& payload, std::uint16_t value)
        {
            payload.push_back(static_cast<std::uint8_t>((value >> 8) & 0xFF));
            payload.push_back(static_cast<std::uint8_t>(value & 0xFF));
        }

        void AppendUnsigned32(std::vector<std::uint8_t>& payload, std::uint32_t value)
        {
            for (int shift = 24; shift >= 0; shift -= 8)
            {
                payload.push_back(static_cast<std::uint8_t>((value >> shift) & 0xFF));
            }
        }
    }

    CapturedAudioPayloadException::CapturedAudioPayloadException(CapturedAudioPayloadFailure failure)
        : std::runtime_error("Captured-audio payload failure."),
          failure_(failure)
    {
    }

    CapturedAudioPayloadFailure CapturedAudioPayloadException::Failure() const noexcept
    {
        return failure_;
    }

    std::vector<std::uint8_t> EncodeCapturedAudioChunk(const float* samples, std::size_t sampleCount, std::int32_t sampleRate, std::size_t maximumPayloadBytes)
    {
        if (sampleRate <= 0)
        {
            throw CapturedAudioPayloadException(CapturedAudioPayloadFailure::InvalidSampleRate);
        }
        if (sampleCount != 0 && samples == nullptr)
        {
            throw CapturedAudioPayloadException(CapturedAudioPayloadFailure::InvalidSamples);
        }
        if (sampleCount > std::numeric_limits<std::uint32_t>::max())
        {
            throw CapturedAudioPayloadException(CapturedAudioPayloadFailure::PayloadTooLarge);
        }

        const std::size_t maximumSampleCount = maximumPayloadBytes >= HeaderByteCount ? (maximumPayloadBytes - HeaderByteCount) / BytesPerSample : 0;
        if (sampleCount > maximumSampleCount)
        {
            throw CapturedAudioPayloadException(CapturedAudioPayloadFailure::PayloadTooLarge);
        }

        std::vector<std::uint8_t> payload;
        payload.reserve(HeaderByteCount + sampleCount * BytesPerSample);
        AppendUnsigned32(payload, PayloadMagic);
        AppendUnsigned16(payload, PayloadVersion);
        AppendUnsigned16(payload, Float32SampleFormat);
        AppendUnsigned32(payload, static_cast<std::uint32_t>(sampleRate));
        AppendUnsigned32(payload, static_cast<std::uint32_t>(sampleCount));

        for (std::size_t sampleIndex = 0; sampleIndex < sampleCount; ++sampleIndex)
        {
            AppendUnsigned32(payload, std::bit_cast<std::uint32_t>(samples[sampleIndex]));
        }
        return payload;
    }
}
