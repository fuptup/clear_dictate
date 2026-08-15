#include "clear_dictate/WorkerProtocol.h"

#include <algorithm>
#include <array>
#include <cstdint>
#include <istream>
#include <limits>
#include <ostream>
#include <utility>

namespace clear_dictate
{
    namespace
    {
        constexpr std::size_t MaximumIdentifierBytes = 64;
        constexpr std::size_t MinimumFrameBodyBytes = 4 + 2 + 1 + 1 + 4;
        constexpr std::size_t MaximumFrameOverheadBytes =
            4 + 2 + 1 + 1 + 4 + MaximumIdentifierBytes + 4 + MaximumIdentifierBytes + 1 + 8 + 4;
        constexpr std::size_t MaximumModelMetadataBytes = 4096;
        constexpr std::size_t MaximumRecordingStartMetadataBytes = 4096;
        constexpr std::size_t FixedValuePayloadBytes = 4;

        void SecureClear(std::vector<std::uint8_t>& sensitiveBytes) noexcept
        {
            volatile std::uint8_t* writableBytes = sensitiveBytes.empty() ? nullptr : sensitiveBytes.data();
            for (std::size_t byteIndex = 0; byteIndex < sensitiveBytes.size(); ++byteIndex)
            {
                writableBytes[byteIndex] = 0;
            }
            sensitiveBytes.clear();
        }

        class SensitiveBytesScrubber final
        {
        public:
            explicit SensitiveBytesScrubber(std::vector<std::uint8_t>& sensitiveBytes) noexcept
                : sensitiveBytes_(sensitiveBytes)
            {
            }

            ~SensitiveBytesScrubber()
            {
                SecureClear(sensitiveBytes_);
            }

        private:
            std::vector<std::uint8_t>& sensitiveBytes_;
        };

        bool IsControlMessage(WorkerMessageType type) noexcept
        {
            switch (type)
            {
                case WorkerMessageType::Hello:
                case WorkerMessageType::Ready:
                case WorkerMessageType::LoadModels:
                case WorkerMessageType::Shutdown:
                case WorkerMessageType::ModelsLoaded:
                case WorkerMessageType::ControlError:
                    return true;

                default:
                    return false;
            }
        }

        WorkerMessageType DecodeMessageType(std::uint8_t code)
        {
            switch (static_cast<WorkerMessageType>(code))
            {
                case WorkerMessageType::Hello:
                case WorkerMessageType::Ready:
                case WorkerMessageType::LoadModels:
                case WorkerMessageType::StartRecording:
                case WorkerMessageType::StopRecording:
                case WorkerMessageType::Cancel:
                case WorkerMessageType::CancellationAcknowledged:
                case WorkerMessageType::AudioChunk:
                case WorkerMessageType::RecordingComplete:
                case WorkerMessageType::PolishTranscript:
                case WorkerMessageType::PolishedTranscript:
                case WorkerMessageType::Error:
                case WorkerMessageType::Shutdown:
                case WorkerMessageType::ModelsLoaded:
                case WorkerMessageType::ControlError:
                case WorkerMessageType::OperationCancelled:
                case WorkerMessageType::RecordingStarted:
                    return static_cast<WorkerMessageType>(code);
            }
            throw WorkerProtocolException(WorkerProtocolFailure::UnknownMessageType);
        }

        bool IsOpaqueIdentifier(const std::string& value) noexcept
        {
            if (value.empty() || value.size() > MaximumIdentifierBytes)
            {
                return false;
            }

            return std::all_of(
                value.begin(),
                value.end(),
                [](unsigned char character)
                {
                    return (character >= 'A' && character <= 'Z') ||
                        (character >= 'a' && character <= 'z') ||
                        (character >= '0' && character <= '9') ||
                        character == '_' ||
                        character == '-';
                });
        }

        bool IsValidUtf8(const std::vector<std::uint8_t>& bytes) noexcept
        {
            std::size_t byteIndex = 0;

            while (byteIndex < bytes.size())
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

                if (byteIndex + continuationCount >= bytes.size())
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

        void ValidatePayload(WorkerMessageType type, const std::vector<std::uint8_t>& payload)
        {
            switch (type)
            {
                case WorkerMessageType::Hello:
                case WorkerMessageType::Ready:
                case WorkerMessageType::ModelsLoaded:
                case WorkerMessageType::Shutdown:
                case WorkerMessageType::StopRecording:
                case WorkerMessageType::Cancel:
                case WorkerMessageType::CancellationAcknowledged:
                case WorkerMessageType::OperationCancelled:
                case WorkerMessageType::RecordingStarted:
                case WorkerMessageType::RecordingComplete:
                    if (!payload.empty())
                    {
                        throw WorkerProtocolException(WorkerProtocolFailure::InvalidMessagePayload);
                    }
                    return;

                case WorkerMessageType::StartRecording:
                    if (payload.size() < 10 || payload.size() > MaximumRecordingStartMetadataBytes)
                    {
                        throw WorkerProtocolException(WorkerProtocolFailure::InvalidMessagePayload);
                    }
                    return;

                case WorkerMessageType::Error:
                case WorkerMessageType::ControlError:
                    if (payload.size() != FixedValuePayloadBytes)
                    {
                        throw WorkerProtocolException(WorkerProtocolFailure::InvalidMessagePayload);
                    }
                    return;

                case WorkerMessageType::LoadModels:
                    if (payload.empty() || payload.size() > MaximumModelMetadataBytes)
                    {
                        throw WorkerProtocolException(WorkerProtocolFailure::InvalidMessagePayload);
                    }
                    return;

                case WorkerMessageType::AudioChunk:
                    if (payload.empty())
                    {
                        throw WorkerProtocolException(WorkerProtocolFailure::InvalidMessagePayload);
                    }
                    return;

                case WorkerMessageType::PolishTranscript:
                    if (payload.empty())
                    {
                        throw WorkerProtocolException(WorkerProtocolFailure::InvalidMessagePayload);
                    }
                    return;

                case WorkerMessageType::PolishedTranscript:
                    if (payload.empty() || !IsValidUtf8(payload))
                    {
                        throw WorkerProtocolException(WorkerProtocolFailure::InvalidUtf8);
                    }
                    return;
            }
        }

        class FrameReader final
        {
        public:
            explicit FrameReader(std::vector<std::uint8_t> bytes)
                : bytes_(std::move(bytes))
            {
            }

            ~FrameReader()
            {
                SecureClear(bytes_);
            }

            std::uint8_t ReadByte()
            {
                RequireRemaining(1);
                return bytes_[position_++];
            }

            std::uint16_t ReadUnsigned16()
            {
                RequireRemaining(2);
                const std::uint16_t value =
                    (static_cast<std::uint16_t>(bytes_[position_]) << 8) |
                    static_cast<std::uint16_t>(bytes_[position_ + 1]);
                position_ += 2;
                return value;
            }

            std::uint32_t ReadUnsigned32()
            {
                RequireRemaining(4);
                std::uint32_t value = 0;

                for (std::size_t byteOffset = 0; byteOffset < 4; ++byteOffset)
                {
                    value = (value << 8) | bytes_[position_ + byteOffset];
                }

                position_ += 4;
                return value;
            }

            std::uint64_t ReadUnsigned64()
            {
                RequireRemaining(8);
                std::uint64_t value = 0;

                for (std::size_t byteOffset = 0; byteOffset < 8; ++byteOffset)
                {
                    value = (value << 8) | bytes_[position_ + byteOffset];
                }

                position_ += 8;
                return value;
            }

            std::vector<std::uint8_t> ReadBytes(std::size_t byteCount)
            {
                RequireRemaining(byteCount);
                const auto firstByte = bytes_.begin() + static_cast<std::ptrdiff_t>(position_);
                std::vector<std::uint8_t> value(firstByte, firstByte + static_cast<std::ptrdiff_t>(byteCount));
                position_ += byteCount;
                return value;
            }

            std::string ReadIdentifier()
            {
                const std::uint32_t identifierLength = ReadUnsigned32();
                if (identifierLength == 0 || identifierLength > MaximumIdentifierBytes)
                {
                    throw WorkerProtocolException(WorkerProtocolFailure::InvalidIdentifier);
                }

                const std::vector<std::uint8_t> identifierBytes = ReadBytes(identifierLength);
                const std::string identifier(identifierBytes.begin(), identifierBytes.end());
                if (!IsOpaqueIdentifier(identifier))
                {
                    throw WorkerProtocolException(WorkerProtocolFailure::InvalidIdentifier);
                }

                return identifier;
            }

            bool IsAtEnd() const noexcept
            {
                return position_ == bytes_.size();
            }

        private:
            void RequireRemaining(std::size_t byteCount) const
            {
                if (byteCount > bytes_.size() - position_)
                {
                    throw WorkerProtocolException(WorkerProtocolFailure::TruncatedFrame);
                }
            }

            std::vector<std::uint8_t> bytes_;
            std::size_t position_ = 0;
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

        void AppendUnsigned64(std::vector<std::uint8_t>& output, std::uint64_t value)
        {
            for (int shift = 56; shift >= 0; shift -= 8)
            {
                output.push_back(static_cast<std::uint8_t>((value >> shift) & 0xFF));
            }
        }

        void AppendIdentifier(std::vector<std::uint8_t>& output, const std::string& identifier)
        {
            if (!IsOpaqueIdentifier(identifier))
            {
                throw WorkerProtocolException(WorkerProtocolFailure::InvalidIdentifier);
            }

            AppendUnsigned32(output, static_cast<std::uint32_t>(identifier.size()));
            output.insert(output.end(), identifier.begin(), identifier.end());
        }

        std::vector<std::uint8_t> ReadExact(std::istream& input, std::size_t byteCount)
        {
            std::vector<std::uint8_t> bytes(byteCount);
            input.read(reinterpret_cast<char*>(bytes.data()), static_cast<std::streamsize>(byteCount));

            if (input.gcount() != static_cast<std::streamsize>(byteCount))
            {
                throw WorkerProtocolException(WorkerProtocolFailure::TruncatedFrame);
            }

            return bytes;
        }
    }

    WorkerProtocolFrame WorkerProtocolFrame::Control(WorkerMessageType type, std::vector<std::uint8_t> payload)
    {
        if (!IsControlMessage(type))
        {
            throw std::invalid_argument("An operation message type cannot be placed in a control frame.");
        }

        return { type, WorkerFrameScope::Control, {}, std::move(payload) };
    }

    WorkerProtocolFrame WorkerProtocolFrame::Operation(
        WorkerMessageType type,
        WorkerOperationIdentity identity,
        std::vector<std::uint8_t> payload)
    {
        if (IsControlMessage(type))
        {
            throw std::invalid_argument("A control message type cannot be placed in an operation frame.");
        }

        return { type, WorkerFrameScope::Operation, std::move(identity), std::move(payload) };
    }

    WorkerProtocolException::WorkerProtocolException(WorkerProtocolFailure failure)
        : std::runtime_error("Worker protocol failure."),
          failure_(failure)
    {
    }

    WorkerProtocolFailure WorkerProtocolException::Failure() const noexcept
    {
        return failure_;
    }

    WorkerProtocolCodec::WorkerProtocolCodec(std::size_t maximumPayloadBytes)
        : maximumPayloadBytes_(maximumPayloadBytes)
    {
        if (maximumPayloadBytes_ > AbsoluteMaximumPayloadBytes)
        {
            throw std::invalid_argument("The worker payload bound exceeds the protocol maximum.");
        }
    }

    WorkerProtocolFrame WorkerProtocolCodec::Read(std::istream& input) const
    {
        const std::vector<std::uint8_t> frameLengthBytes = ReadExact(input, 4);
        FrameReader lengthReader(frameLengthBytes);
        const std::uint32_t frameLength = lengthReader.ReadUnsigned32();
        const std::size_t maximumFrameLength = maximumPayloadBytes_ + MaximumFrameOverheadBytes;

        if (frameLength < MinimumFrameBodyBytes || frameLength > maximumFrameLength)
        {
            throw WorkerProtocolException(WorkerProtocolFailure::InvalidFrameLength);
        }

        FrameReader frameReader(ReadExact(input, frameLength));
        if (frameReader.ReadUnsigned32() != Magic)
        {
            throw WorkerProtocolException(WorkerProtocolFailure::InvalidMagic);
        }

        if (frameReader.ReadUnsigned16() != ProtocolVersion)
        {
            throw WorkerProtocolException(WorkerProtocolFailure::UnsupportedVersion);
        }

        const WorkerMessageType messageType = DecodeMessageType(frameReader.ReadByte());
        const std::uint8_t scopeCode = frameReader.ReadByte();
        if (scopeCode > static_cast<std::uint8_t>(WorkerFrameScope::Operation))
        {
            throw WorkerProtocolException(WorkerProtocolFailure::InvalidFrameScope);
        }

        const WorkerFrameScope scope = static_cast<WorkerFrameScope>(scopeCode);
        WorkerOperationIdentity identity;

        if (scope == WorkerFrameScope::Operation)
        {
            identity.clientSessionIdentifier = frameReader.ReadIdentifier();
            identity.operationIdentifier = frameReader.ReadIdentifier();

            const std::uint8_t privacyCode = frameReader.ReadByte();
            if (privacyCode > static_cast<std::uint8_t>(OperationPrivacy::Private))
            {
                throw WorkerProtocolException(WorkerProtocolFailure::InvalidPrivacy);
            }

            identity.privacy = static_cast<OperationPrivacy>(privacyCode);
            identity.workerRequestToken = frameReader.ReadUnsigned64();
            if (identity.workerRequestToken == 0 ||
                identity.workerRequestToken > static_cast<std::uint64_t>(std::numeric_limits<std::int64_t>::max()))
            {
                throw WorkerProtocolException(WorkerProtocolFailure::InvalidWorkerRequestToken);
            }
        }

        const std::uint32_t payloadLength = frameReader.ReadUnsigned32();
        if (payloadLength > maximumPayloadBytes_)
        {
            throw WorkerProtocolException(WorkerProtocolFailure::InvalidPayloadLength);
        }

        std::vector<std::uint8_t> payload = frameReader.ReadBytes(payloadLength);
        if (!frameReader.IsAtEnd())
        {
            throw WorkerProtocolException(WorkerProtocolFailure::TrailingBytes);
        }

        if (IsControlMessage(messageType) != (scope == WorkerFrameScope::Control))
        {
            throw WorkerProtocolException(WorkerProtocolFailure::MessageScopeMismatch);
        }

        ValidatePayload(messageType, payload);
        return scope == WorkerFrameScope::Control
            ? WorkerProtocolFrame::Control(messageType, std::move(payload))
            : WorkerProtocolFrame::Operation(messageType, std::move(identity), std::move(payload));
    }

    void WorkerProtocolCodec::Write(const WorkerProtocolFrame& frame, std::ostream& output) const
    {
        if (frame.payload.size() > maximumPayloadBytes_)
        {
            throw WorkerProtocolException(WorkerProtocolFailure::InvalidPayloadLength);
        }

        if (IsControlMessage(frame.type) != (frame.scope == WorkerFrameScope::Control))
        {
            throw WorkerProtocolException(WorkerProtocolFailure::MessageScopeMismatch);
        }

        ValidatePayload(frame.type, frame.payload);
        std::vector<std::uint8_t> frameBody;
        SensitiveBytesScrubber frameBodyScrubber(frameBody);
        frameBody.reserve(MaximumFrameOverheadBytes + frame.payload.size());
        AppendUnsigned32(frameBody, Magic);
        AppendUnsigned16(frameBody, ProtocolVersion);
        frameBody.push_back(static_cast<std::uint8_t>(frame.type));
        frameBody.push_back(static_cast<std::uint8_t>(frame.scope));

        if (frame.scope == WorkerFrameScope::Operation)
        {
            AppendIdentifier(frameBody, frame.identity.clientSessionIdentifier);
            AppendIdentifier(frameBody, frame.identity.operationIdentifier);

            if (frame.identity.workerRequestToken == 0 ||
                frame.identity.workerRequestToken > static_cast<std::uint64_t>(std::numeric_limits<std::int64_t>::max()))
            {
                throw WorkerProtocolException(WorkerProtocolFailure::InvalidWorkerRequestToken);
            }

            frameBody.push_back(static_cast<std::uint8_t>(frame.identity.privacy));
            AppendUnsigned64(frameBody, frame.identity.workerRequestToken);
        }

        AppendUnsigned32(frameBody, static_cast<std::uint32_t>(frame.payload.size()));
        frameBody.insert(frameBody.end(), frame.payload.begin(), frame.payload.end());

        std::vector<std::uint8_t> frameLengthBytes;
        AppendUnsigned32(frameLengthBytes, static_cast<std::uint32_t>(frameBody.size()));
        output.write(
            reinterpret_cast<const char*>(frameLengthBytes.data()),
            static_cast<std::streamsize>(frameLengthBytes.size()));
        output.write(reinterpret_cast<const char*>(frameBody.data()), static_cast<std::streamsize>(frameBody.size()));
        output.flush();

        if (!output)
        {
            throw WorkerProtocolException(WorkerProtocolFailure::TruncatedFrame);
        }
    }
}
