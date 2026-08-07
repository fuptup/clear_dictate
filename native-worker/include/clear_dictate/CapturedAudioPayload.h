#pragma once

#include <cstddef>
#include <cstdint>
#include <stdexcept>
#include <vector>

namespace clear_dictate
{
    enum class CapturedAudioPayloadFailure
    {
        InvalidSampleRate,
        InvalidSamples,
        PayloadTooLarge
    };

    class CapturedAudioPayloadException final : public std::runtime_error
    {
    public:
        explicit CapturedAudioPayloadException(CapturedAudioPayloadFailure failure);
        CapturedAudioPayloadFailure Failure() const noexcept;

    private:
        CapturedAudioPayloadFailure failure_;
    };

    /**
     * Encodes one mono float sample chunk for the Kotlin capture client.
     * Header integers and IEEE 754 samples use network byte order.
     */
    std::vector<std::uint8_t> EncodeCapturedAudioChunk(const float* samples, std::size_t sampleCount, std::int32_t sampleRate, std::size_t maximumPayloadBytes);
}
