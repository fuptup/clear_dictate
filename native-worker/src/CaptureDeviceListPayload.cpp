#include "clear_dictate/CaptureDeviceListPayload.h"

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <string>
#include <unordered_set>
#include <vector>

namespace clear_dictate
{
    namespace
    {
        constexpr std::uint32_t PayloadMagic = 0x4344414C;
        constexpr std::uint16_t PayloadVersion = 1;
        constexpr std::size_t MaximumPayloadBytes = 64 * 1024;
        constexpr std::size_t MaximumDeviceCount = 128;
        constexpr std::size_t MaximumEndpointIdentifierBytes = 4000;
        constexpr std::size_t MaximumFriendlyNameBytes = 1000;
        constexpr std::uint8_t DefaultDeviceFlag = 1;

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

        bool IsValidUtf8(const std::string& value) noexcept
        {
            std::size_t byteIndex = 0;
            while (byteIndex < value.size())
            {
                const std::uint8_t firstByte = static_cast<std::uint8_t>(value[byteIndex]);
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

                if (byteIndex + continuationCount >= value.size())
                {
                    return false;
                }
                for (std::size_t continuationIndex = 1; continuationIndex <= continuationCount; ++continuationIndex)
                {
                    const std::uint8_t continuationByte = static_cast<std::uint8_t>(value[byteIndex + continuationIndex]);
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

        void ValidateText(const std::string& value, std::size_t maximumBytes, CaptureDeviceListPayloadFailure emptyFailure)
        {
            if (value.empty() || value.size() > maximumBytes || value.find('\0') != std::string::npos)
            {
                throw CaptureDeviceListPayloadException(emptyFailure);
            }
            if (!IsValidUtf8(value))
            {
                throw CaptureDeviceListPayloadException(CaptureDeviceListPayloadFailure::InvalidUtf8);
            }
        }

        class PayloadReader final
        {
        public:
            explicit PayloadReader(const std::vector<std::uint8_t>& payload)
                : payload_(payload)
            {
            }

            std::uint8_t ReadUnsigned8()
            {
                RequireRemaining(1);
                return payload_[position_++];
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
                const auto finalByte = firstByte + static_cast<std::ptrdiff_t>(byteCount);
                position_ += byteCount;
                return { firstByte, finalByte };
            }

            bool IsAtEnd() const noexcept
            {
                return position_ == payload_.size();
            }

        private:
            void RequireRemaining(std::size_t requiredBytes)
            {
                if (requiredBytes > payload_.size() - position_)
                {
                    throw CaptureDeviceListPayloadException(CaptureDeviceListPayloadFailure::InvalidLength);
                }
            }

            const std::vector<std::uint8_t>& payload_;
            std::size_t position_ = 0;
        };

        void ValidateDeviceSet(const std::vector<CaptureDeviceDescription>& devices)
        {
            if (devices.size() > MaximumDeviceCount)
            {
                throw CaptureDeviceListPayloadException(CaptureDeviceListPayloadFailure::InvalidDeviceCount);
            }

            std::unordered_set<std::string> endpointIdentifiers;
            bool defaultDeviceFound = false;
            for (const CaptureDeviceDescription& device : devices)
            {
                ValidateText(
                    device.endpointIdentifier,
                    MaximumEndpointIdentifierBytes,
                    CaptureDeviceListPayloadFailure::InvalidIdentifier);
                ValidateText(
                    device.friendlyName,
                    MaximumFriendlyNameBytes,
                    CaptureDeviceListPayloadFailure::InvalidFriendlyName);
                if (!endpointIdentifiers.insert(device.endpointIdentifier).second)
                {
                    throw CaptureDeviceListPayloadException(CaptureDeviceListPayloadFailure::DuplicateIdentifier);
                }
                if (device.isDefault && defaultDeviceFound)
                {
                    throw CaptureDeviceListPayloadException(CaptureDeviceListPayloadFailure::MultipleDefaultDevices);
                }
                defaultDeviceFound = defaultDeviceFound || device.isDefault;
            }
        }
    }

    CaptureDeviceListPayloadException::CaptureDeviceListPayloadException(CaptureDeviceListPayloadFailure failure)
        : std::runtime_error("Capture device list payload failure."),
          failure_(failure)
    {
    }

    CaptureDeviceListPayloadFailure CaptureDeviceListPayloadException::Failure() const noexcept
    {
        return failure_;
    }

    bool operator==(const CaptureDeviceDescription& left, const CaptureDeviceDescription& right) noexcept
    {
        return left.endpointIdentifier == right.endpointIdentifier &&
            left.friendlyName == right.friendlyName &&
            left.isDefault == right.isDefault;
    }

    std::vector<std::uint8_t> EncodeCaptureDeviceList(const std::vector<CaptureDeviceDescription>& devices)
    {
        ValidateDeviceSet(devices);
        std::size_t requiredBytes = 8;
        for (const CaptureDeviceDescription& device : devices)
        {
            requiredBytes += 9 + device.endpointIdentifier.size() + device.friendlyName.size();
        }
        if (requiredBytes > MaximumPayloadBytes)
        {
            throw CaptureDeviceListPayloadException(CaptureDeviceListPayloadFailure::InvalidLength);
        }

        std::vector<std::uint8_t> payload;
        payload.reserve(requiredBytes);
        AppendUnsigned32(payload, PayloadMagic);
        AppendUnsigned16(payload, PayloadVersion);
        AppendUnsigned16(payload, static_cast<std::uint16_t>(devices.size()));
        for (const CaptureDeviceDescription& device : devices)
        {
            payload.push_back(device.isDefault ? DefaultDeviceFlag : 0);
            AppendUnsigned32(payload, static_cast<std::uint32_t>(device.endpointIdentifier.size()));
            AppendUnsigned32(payload, static_cast<std::uint32_t>(device.friendlyName.size()));
            payload.insert(payload.end(), device.endpointIdentifier.begin(), device.endpointIdentifier.end());
            payload.insert(payload.end(), device.friendlyName.begin(), device.friendlyName.end());
        }
        return payload;
    }

    std::vector<CaptureDeviceDescription> DecodeCaptureDeviceList(const std::vector<std::uint8_t>& payload)
    {
        if (payload.size() > MaximumPayloadBytes)
        {
            throw CaptureDeviceListPayloadException(CaptureDeviceListPayloadFailure::InvalidLength);
        }

        PayloadReader reader(payload);
        if (reader.ReadUnsigned32() != PayloadMagic)
        {
            throw CaptureDeviceListPayloadException(CaptureDeviceListPayloadFailure::InvalidMagic);
        }
        if (reader.ReadUnsigned16() != PayloadVersion)
        {
            throw CaptureDeviceListPayloadException(CaptureDeviceListPayloadFailure::UnsupportedVersion);
        }

        const std::size_t deviceCount = reader.ReadUnsigned16();
        if (deviceCount > MaximumDeviceCount)
        {
            throw CaptureDeviceListPayloadException(CaptureDeviceListPayloadFailure::InvalidDeviceCount);
        }

        std::vector<CaptureDeviceDescription> devices;
        devices.reserve(deviceCount);
        for (std::size_t deviceIndex = 0; deviceIndex < deviceCount; ++deviceIndex)
        {
            const std::uint8_t flags = reader.ReadUnsigned8();
            if ((flags & ~DefaultDeviceFlag) != 0)
            {
                throw CaptureDeviceListPayloadException(CaptureDeviceListPayloadFailure::InvalidFlags);
            }
            const std::size_t identifierBytes = reader.ReadUnsigned32();
            const std::size_t friendlyNameBytes = reader.ReadUnsigned32();
            if (identifierBytes > MaximumEndpointIdentifierBytes || friendlyNameBytes > MaximumFriendlyNameBytes)
            {
                throw CaptureDeviceListPayloadException(CaptureDeviceListPayloadFailure::InvalidLength);
            }
            devices.push_back(
                {
                    reader.ReadString(identifierBytes),
                    reader.ReadString(friendlyNameBytes),
                    (flags & DefaultDeviceFlag) != 0
                });
        }
        if (!reader.IsAtEnd())
        {
            throw CaptureDeviceListPayloadException(CaptureDeviceListPayloadFailure::TrailingBytes);
        }
        ValidateDeviceSet(devices);
        return devices;
    }
}
