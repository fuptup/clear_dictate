#include "clear_dictate/WorkerTranscriptPayloads.h"

#include <cstddef>
#include <limits>

namespace clear_dictate
{
    namespace
    {
        constexpr std::uint32_t PayloadMagic = 0x43445444;
        constexpr std::uint16_t PayloadVersion = 1;
        constexpr std::uint8_t IsNewFlag = 1;
        constexpr std::uint8_t IsUpdatedFlag = 2;
        constexpr std::uint8_t IsCompleteFlag = 4;
        constexpr std::uint8_t KnownFlags = IsNewFlag | IsUpdatedFlag | IsCompleteFlag;
        constexpr std::size_t HeaderBytes = 4 + 2 + 8 + 1 + 4;
        constexpr std::size_t MaximumTextBytes = 64 * 1024 - HeaderBytes;

        bool IsValidUtf8(const std::string& text) noexcept
        {
            const auto* bytes = reinterpret_cast<const std::uint8_t*>(text.data());
            std::size_t byteIndex = 0;
            while (byteIndex < text.size())
            {
                const std::uint8_t firstByte = bytes[byteIndex];
                if (firstByte <= 0x7F)
                {
                    ++byteIndex;
                    continue;
                }

                std::size_t continuationCount = 0;
                std::uint32_t codePoint = 0;
                std::uint32_t minimumCodePoint = 0;
                if ((firstByte & 0xE0) == 0xC0)
                {
                    continuationCount = 1;
                    codePoint = firstByte & 0x1F;
                    minimumCodePoint = 0x80;
                }
                else if ((firstByte & 0xF0) == 0xE0)
                {
                    continuationCount = 2;
                    codePoint = firstByte & 0x0F;
                    minimumCodePoint = 0x800;
                }
                else if ((firstByte & 0xF8) == 0xF0)
                {
                    continuationCount = 3;
                    codePoint = firstByte & 0x07;
                    minimumCodePoint = 0x10000;
                }
                else
                {
                    return false;
                }
                if (byteIndex + continuationCount >= text.size())
                {
                    return false;
                }
                for (std::size_t continuationIndex = 1; continuationIndex <= continuationCount; ++continuationIndex)
                {
                    const std::uint8_t continuationByte = bytes[byteIndex + continuationIndex];
                    if ((continuationByte & 0xC0) != 0x80)
                    {
                        return false;
                    }
                    codePoint = (codePoint << 6) | (continuationByte & 0x3F);
                }
                if (codePoint < minimumCodePoint || codePoint > 0x10FFFF || (codePoint >= 0xD800 && codePoint <= 0xDFFF))
                {
                    return false;
                }
                byteIndex += continuationCount + 1;
            }
            return true;
        }

        void AppendUnsigned16(std::vector<std::uint8_t>& output, std::uint16_t value)
        {
            output.push_back(static_cast<std::uint8_t>((value >> 8) & 0xFF));
            output.push_back(static_cast<std::uint8_t>(value & 0xFF));
        }

        void AppendUnsigned32(std::vector<std::uint8_t>& output, std::uint32_t value)
        {
            for (int shift = 24; shift >= 0; shift -= 8)
            {
                output.push_back(static_cast<std::uint8_t>((value >> shift) & 0xFF));
            }
        }

        void AppendUnsigned64(std::vector<std::uint8_t>& output, std::uint64_t value)
        {
            for (int shift = 56; shift >= 0; shift -= 8)
            {
                output.push_back(static_cast<std::uint8_t>((value >> shift) & 0xFF));
            }
        }

        class PayloadReader final
        {
        public:
            explicit PayloadReader(const std::vector<std::uint8_t>& payload)
                : payload_(payload)
            {
            }

            std::uint8_t ReadByte()
            {
                Require(1);
                return payload_[position_++];
            }

            std::uint16_t ReadUnsigned16()
            {
                Require(2);
                const std::uint16_t value = static_cast<std::uint16_t>(
                    (static_cast<std::uint16_t>(payload_[position_]) << 8) | payload_[position_ + 1]);
                position_ += 2;
                return value;
            }

            std::uint32_t ReadUnsigned32()
            {
                Require(4);
                std::uint32_t value = 0;
                for (int byteIndex = 0; byteIndex < 4; ++byteIndex)
                {
                    value = (value << 8) | payload_[position_++];
                }
                return value;
            }

            std::uint64_t ReadUnsigned64()
            {
                Require(8);
                std::uint64_t value = 0;
                for (int byteIndex = 0; byteIndex < 8; ++byteIndex)
                {
                    value = (value << 8) | payload_[position_++];
                }
                return value;
            }

            std::string ReadText(std::size_t byteCount)
            {
                Require(byteCount);
                const auto begin = payload_.begin() + static_cast<std::ptrdiff_t>(position_);
                const std::string value(begin, begin + static_cast<std::ptrdiff_t>(byteCount));
                position_ += byteCount;
                return value;
            }

            bool IsAtEnd() const noexcept
            {
                return position_ == payload_.size();
            }

        private:
            void Require(std::size_t byteCount) const
            {
                if (byteCount > payload_.size() - position_)
                {
                    throw TranscriptPayloadException();
                }
            }

            const std::vector<std::uint8_t>& payload_;
            std::size_t position_ = 0;
        };

        void Validate(const TranscriptDelta& delta)
        {
            if (delta.lineIdentifier == 0 ||
                (!delta.isNew && !delta.isUpdated) ||
                delta.text.size() > MaximumTextBytes ||
                delta.text.find('\0') != std::string::npos ||
                !IsValidUtf8(delta.text))
            {
                throw TranscriptPayloadException();
            }
        }
    }

    bool operator==(const TranscriptDelta& left, const TranscriptDelta& right) noexcept
    {
        return left.lineIdentifier == right.lineIdentifier &&
            left.isNew == right.isNew &&
            left.isUpdated == right.isUpdated &&
            left.isComplete == right.isComplete &&
            left.text == right.text;
    }

    TranscriptPayloadException::TranscriptPayloadException()
        : std::runtime_error("Worker transcript payload failure.")
    {
    }

    std::vector<std::uint8_t> EncodeTranscriptDelta(const TranscriptDelta& delta)
    {
        Validate(delta);
        std::vector<std::uint8_t> payload;
        payload.reserve(HeaderBytes + delta.text.size());
        AppendUnsigned32(payload, PayloadMagic);
        AppendUnsigned16(payload, PayloadVersion);
        AppendUnsigned64(payload, delta.lineIdentifier);

        std::uint8_t flags = 0;
        flags |= delta.isNew ? IsNewFlag : 0;
        flags |= delta.isUpdated ? IsUpdatedFlag : 0;
        flags |= delta.isComplete ? IsCompleteFlag : 0;
        payload.push_back(flags);
        AppendUnsigned32(payload, static_cast<std::uint32_t>(delta.text.size()));
        payload.insert(payload.end(), delta.text.begin(), delta.text.end());
        return payload;
    }

    TranscriptDelta DecodeTranscriptDelta(const std::vector<std::uint8_t>& payload)
    {
        PayloadReader reader(payload);
        if (reader.ReadUnsigned32() != PayloadMagic || reader.ReadUnsigned16() != PayloadVersion)
        {
            throw TranscriptPayloadException();
        }

        TranscriptDelta delta {};
        delta.lineIdentifier = reader.ReadUnsigned64();
        const std::uint8_t flags = reader.ReadByte();
        if ((flags & ~KnownFlags) != 0)
        {
            throw TranscriptPayloadException();
        }

        delta.isNew = (flags & IsNewFlag) != 0;
        delta.isUpdated = (flags & IsUpdatedFlag) != 0;
        delta.isComplete = (flags & IsCompleteFlag) != 0;
        const std::uint32_t textByteCount = reader.ReadUnsigned32();
        if (textByteCount > MaximumTextBytes)
        {
            throw TranscriptPayloadException();
        }
        delta.text = reader.ReadText(textByteCount);
        if (!reader.IsAtEnd())
        {
            throw TranscriptPayloadException();
        }
        Validate(delta);
        return delta;
    }
}
