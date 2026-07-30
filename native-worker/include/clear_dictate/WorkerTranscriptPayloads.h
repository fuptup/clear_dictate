#pragma once

#include <cstdint>
#include <stdexcept>
#include <string>
#include <vector>

namespace clear_dictate
{
    /**
     * One immutable Moonshine line change copied before the next native model call.
     */
    struct TranscriptDelta final
    {
        std::uint64_t lineIdentifier;
        bool isNew;
        bool isUpdated;
        bool isComplete;
        std::string text;
    };

    bool operator==(const TranscriptDelta& left, const TranscriptDelta& right) noexcept;

    class TranscriptPayloadException final : public std::runtime_error
    {
    public:
        TranscriptPayloadException();
    };

    std::vector<std::uint8_t> EncodeTranscriptDelta(const TranscriptDelta& delta);
    TranscriptDelta DecodeTranscriptDelta(const std::vector<std::uint8_t>& payload);
}
