#include "clear_dictate/WorkerPayloads.h"

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <string>
#include <vector>

namespace clear_dictate
{
    namespace
    {
        constexpr std::uint32_t ModelLoadPayloadMagic = 0x43444D4C;
        constexpr std::uint16_t ModelLoadPayloadVersion = 1;
        constexpr std::uint32_t SpeechModelLoadPayloadMagic = 0x4344534C;
        constexpr std::uint16_t SpeechModelLoadPayloadVersion = 1;
        constexpr std::uint32_t RecordingStartPayloadMagic = 0x43445253;
        constexpr std::uint16_t RecordingStartPayloadVersion = 1;
        constexpr std::size_t MaximumModelPathBytes = 4000;
        constexpr std::size_t MaximumEndpointIdentifierBytes = 4000;
        constexpr std::int32_t MaximumInferenceThreadCount = 64;

        const std::string ProductionSystemInstruction = R"(You edit spoken transcripts into clear written English.

Remove hesitation fillers, abandoned starts, accidental repetitions, and verbal clutter.

Improve punctuation and sentence structure only where required for readability.

Preserve the speaker's intended meaning exactly.

Preserve all names, numbers, dates, measurements, prices, identifiers, technical terms, negations, qualifications, uncertainty, and corrections.

Do not summarize.

Do not add facts.

Do not answer the transcript.

Do not explain your edits.

Return only the edited transcript.)";

        void SecureClear(std::string& sensitiveText) noexcept
        {
            volatile char* sensitiveBytes = sensitiveText.empty() ? nullptr : sensitiveText.data();
            for (std::size_t byteIndex = 0; byteIndex < sensitiveText.size(); ++byteIndex)
            {
                sensitiveBytes[byteIndex] = '\0';
            }
            sensitiveText.clear();
        }

        class SensitiveStringScrubber final
        {
        public:
            explicit SensitiveStringScrubber(std::string& sensitiveText) noexcept
                : sensitiveText_(sensitiveText)
            {
            }

            ~SensitiveStringScrubber()
            {
                SecureClear(sensitiveText_);
            }

        private:
            std::string& sensitiveText_;
        };

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

        class PayloadReader final
        {
        public:
            explicit PayloadReader(const std::vector<std::uint8_t>& payload)
                : payload_(payload)
            {
            }

            std::uint16_t ReadUnsigned16()
            {
                RequireRemaining(2);
                const std::uint16_t value =
                    (static_cast<std::uint16_t>(payload_[position_]) << 8) |
                    payload_[position_ + 1];
                position_ += 2;
                return value;
            }

            std::uint32_t ReadUnsigned32()
            {
                RequireRemaining(4);
                std::uint32_t value = 0;

                for (std::size_t byteOffset = 0; byteOffset < 4; ++byteOffset)
                {
                    value = (value << 8) | payload_[position_ + byteOffset];
                }

                position_ += 4;
                return value;
            }

            std::string ReadString(std::size_t byteCount)
            {
                RequireRemaining(byteCount);
                const auto firstByte = payload_.begin() + static_cast<std::ptrdiff_t>(position_);
                const std::string value(firstByte, firstByte + static_cast<std::ptrdiff_t>(byteCount));
                position_ += byteCount;
                return value;
            }

            bool IsAtEnd() const noexcept
            {
                return position_ == payload_.size();
            }

        private:
            void RequireRemaining(std::size_t byteCount) const
            {
                if (byteCount > payload_.size() - position_)
                {
                    throw WorkerPayloadException(WorkerPayloadFailure::InvalidLength);
                }
            }

            const std::vector<std::uint8_t>& payload_;
            std::size_t position_ = 0;
        };

        void ValidateModelPath(const std::string& utf8ModelPath)
        {
            if (utf8ModelPath.empty() ||
                utf8ModelPath.size() > MaximumModelPathBytes ||
                utf8ModelPath.find('\0') != std::string::npos)
            {
                throw WorkerPayloadException(WorkerPayloadFailure::InvalidPath);
            }
        }

        void ValidateSpeechModelDirectory(const std::string& modelDirectory)
        {
            ValidateModelPath(modelDirectory);
            if (std::any_of(
                    modelDirectory.begin(),
                    modelDirectory.end(),
                    [](unsigned char pathByte)
                    {
                        return pathByte > 0x7F;
                    }))
            {
                throw WorkerPayloadException(WorkerPayloadFailure::InvalidPath);
            }
        }

        bool IsValidUtf8(const std::string& text) noexcept
        {
            std::size_t byteIndex = 0;
            while (byteIndex < text.size())
            {
                const std::uint8_t firstByte = static_cast<std::uint8_t>(text[byteIndex]);
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
                    const std::uint8_t continuationByte = static_cast<std::uint8_t>(text[byteIndex + continuationIndex]);
                    if ((continuationByte & 0xC0) != 0x80)
                    {
                        return false;
                    }
                    codePoint = (codePoint << 6) | (continuationByte & 0x3F);
                }

                if (codePoint < minimumCodePoint ||
                    codePoint > 0x10FFFF ||
                    (codePoint >= 0xD800 && codePoint <= 0xDFFF))
                {
                    return false;
                }

                byteIndex += continuationCount + 1;
            }

            return true;
        }

        void ValidateEndpointIdentifier(const std::string& endpointIdentifier)
        {
            if (endpointIdentifier.size() > MaximumEndpointIdentifierBytes ||
                endpointIdentifier.find('\0') != std::string::npos ||
                !IsValidUtf8(endpointIdentifier))
            {
                throw WorkerPayloadException(WorkerPayloadFailure::InvalidEndpointIdentifier);
            }
        }

        std::string EscapeXmlText(const std::string& text)
        {
            std::string escapedText;
            escapedText.reserve(text.size());

            for (char character : text)
            {
                switch (character)
                {
                    case '&':
                        escapedText += "&amp;";
                        break;

                    case '<':
                        escapedText += "&lt;";
                        break;

                    case '>':
                        escapedText += "&gt;";
                        break;

                    default:
                        escapedText.push_back(character);
                        break;
                }
            }

            return escapedText;
        }
    }

    WorkerPayloadException::WorkerPayloadException(WorkerPayloadFailure failure)
        : std::runtime_error("Worker payload failure."),
          failure_(failure)
    {
    }

    WorkerPayloadFailure WorkerPayloadException::Failure() const noexcept
    {
        return failure_;
    }

    std::vector<std::uint8_t> EncodeTextModelLoadRequest(const TextModelLoadRequest& request)
    {
        ValidateModelPath(request.utf8ModelPath);
        if (request.inferenceThreadCount <= 0 || request.inferenceThreadCount > MaximumInferenceThreadCount)
        {
            throw WorkerPayloadException(WorkerPayloadFailure::InvalidThreadCount);
        }

        std::vector<std::uint8_t> payload;
        payload.reserve(4 + 2 + 2 + 4 + request.utf8ModelPath.size());
        AppendUnsigned32(payload, ModelLoadPayloadMagic);
        AppendUnsigned16(payload, ModelLoadPayloadVersion);
        AppendUnsigned16(payload, static_cast<std::uint16_t>(request.inferenceThreadCount));
        AppendUnsigned32(payload, static_cast<std::uint32_t>(request.utf8ModelPath.size()));
        payload.insert(payload.end(), request.utf8ModelPath.begin(), request.utf8ModelPath.end());
        return payload;
    }

    TextModelLoadRequest DecodeTextModelLoadRequest(const std::vector<std::uint8_t>& payload)
    {
        PayloadReader reader(payload);
        if (reader.ReadUnsigned32() != ModelLoadPayloadMagic)
        {
            throw WorkerPayloadException(WorkerPayloadFailure::InvalidMagic);
        }

        if (reader.ReadUnsigned16() != ModelLoadPayloadVersion)
        {
            throw WorkerPayloadException(WorkerPayloadFailure::UnsupportedVersion);
        }

        const std::uint16_t inferenceThreadCount = reader.ReadUnsigned16();
        if (inferenceThreadCount == 0 || inferenceThreadCount > MaximumInferenceThreadCount)
        {
            throw WorkerPayloadException(WorkerPayloadFailure::InvalidThreadCount);
        }

        const std::uint32_t pathByteCount = reader.ReadUnsigned32();
        if (pathByteCount == 0 || pathByteCount > MaximumModelPathBytes)
        {
            throw WorkerPayloadException(WorkerPayloadFailure::InvalidLength);
        }

        const std::string utf8ModelPath = reader.ReadString(pathByteCount);
        ValidateModelPath(utf8ModelPath);

        if (!reader.IsAtEnd())
        {
            throw WorkerPayloadException(WorkerPayloadFailure::TrailingBytes);
        }

        return { utf8ModelPath, inferenceThreadCount };
    }

    std::vector<std::uint8_t> EncodeSpeechModelLoadRequest(const SpeechModelLoadRequest& request)
    {
        ValidateSpeechModelDirectory(request.modelDirectory);

        std::vector<std::uint8_t> payload;
        payload.reserve(4 + 2 + 4 + request.modelDirectory.size());
        AppendUnsigned32(payload, SpeechModelLoadPayloadMagic);
        AppendUnsigned16(payload, SpeechModelLoadPayloadVersion);
        AppendUnsigned32(payload, static_cast<std::uint32_t>(request.modelDirectory.size()));
        payload.insert(payload.end(), request.modelDirectory.begin(), request.modelDirectory.end());
        return payload;
    }

    SpeechModelLoadRequest DecodeSpeechModelLoadRequest(const std::vector<std::uint8_t>& payload)
    {
        PayloadReader reader(payload);
        if (reader.ReadUnsigned32() != SpeechModelLoadPayloadMagic)
        {
            throw WorkerPayloadException(WorkerPayloadFailure::InvalidMagic);
        }

        if (reader.ReadUnsigned16() != SpeechModelLoadPayloadVersion)
        {
            throw WorkerPayloadException(WorkerPayloadFailure::UnsupportedVersion);
        }

        const std::uint32_t pathByteCount = reader.ReadUnsigned32();
        if (pathByteCount == 0 || pathByteCount > MaximumModelPathBytes)
        {
            throw WorkerPayloadException(WorkerPayloadFailure::InvalidLength);
        }

        const std::string modelDirectory = reader.ReadString(pathByteCount);
        ValidateSpeechModelDirectory(modelDirectory);

        if (!reader.IsAtEnd())
        {
            throw WorkerPayloadException(WorkerPayloadFailure::TrailingBytes);
        }

        return { modelDirectory };
    }

    std::vector<std::uint8_t> EncodeRecordingStartRequest(const RecordingStartRequest& request)
    {
        ValidateEndpointIdentifier(request.utf8EndpointIdentifier);

        std::vector<std::uint8_t> payload;
        payload.reserve(4 + 2 + 4 + request.utf8EndpointIdentifier.size());
        AppendUnsigned32(payload, RecordingStartPayloadMagic);
        AppendUnsigned16(payload, RecordingStartPayloadVersion);
        AppendUnsigned32(payload, static_cast<std::uint32_t>(request.utf8EndpointIdentifier.size()));
        payload.insert(payload.end(), request.utf8EndpointIdentifier.begin(), request.utf8EndpointIdentifier.end());
        return payload;
    }

    RecordingStartRequest DecodeRecordingStartRequest(const std::vector<std::uint8_t>& payload)
    {
        PayloadReader reader(payload);
        if (reader.ReadUnsigned32() != RecordingStartPayloadMagic)
        {
            throw WorkerPayloadException(WorkerPayloadFailure::InvalidMagic);
        }
        if (reader.ReadUnsigned16() != RecordingStartPayloadVersion)
        {
            throw WorkerPayloadException(WorkerPayloadFailure::UnsupportedVersion);
        }

        const std::uint32_t endpointIdentifierByteCount = reader.ReadUnsigned32();
        if (endpointIdentifierByteCount > MaximumEndpointIdentifierBytes)
        {
            throw WorkerPayloadException(WorkerPayloadFailure::InvalidLength);
        }

        const std::string utf8EndpointIdentifier = reader.ReadString(endpointIdentifierByteCount);
        ValidateEndpointIdentifier(utf8EndpointIdentifier);
        if (!reader.IsAtEnd())
        {
            throw WorkerPayloadException(WorkerPayloadFailure::TrailingBytes);
        }

        return { utf8EndpointIdentifier };
    }

    TextPolishPrompt BuildTextPolishPrompt(const std::string& cleanTranscript)
    {
        std::string encodedTranscript = EscapeXmlText(cleanTranscript);
        SensitiveStringScrubber encodedTranscriptScrubber(encodedTranscript);
        std::string userInstruction =
            "Edit this transcript:\n\n<transcript>\n" +
            encodedTranscript +
            "\n</transcript>";
        SensitiveStringScrubber userInstructionScrubber(userInstruction);

        return { ProductionSystemInstruction, userInstruction };
    }
}
