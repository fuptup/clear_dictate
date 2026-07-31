#pragma once

#include <cstdint>
#include <stdexcept>
#include <string>
#include <vector>

namespace clear_dictate
{
    struct CaptureDeviceDescription final
    {
        std::string endpointIdentifier;
        std::string friendlyName;
        bool isDefault;
    };

    bool operator==(const CaptureDeviceDescription& left, const CaptureDeviceDescription& right) noexcept;

    enum class CaptureDeviceListPayloadFailure
    {
        InvalidMagic,
        UnsupportedVersion,
        InvalidDeviceCount,
        InvalidFlags,
        InvalidIdentifier,
        InvalidFriendlyName,
        DuplicateIdentifier,
        MultipleDefaultDevices,
        InvalidLength,
        InvalidUtf8,
        TrailingBytes
    };

    class CaptureDeviceListPayloadException final : public std::runtime_error
    {
    public:
        explicit CaptureDeviceListPayloadException(CaptureDeviceListPayloadFailure failure);

        CaptureDeviceListPayloadFailure Failure() const noexcept;

    private:
        CaptureDeviceListPayloadFailure failure_;
    };

    std::vector<std::uint8_t> EncodeCaptureDeviceList(const std::vector<CaptureDeviceDescription>& devices);
    std::vector<CaptureDeviceDescription> DecodeCaptureDeviceList(const std::vector<std::uint8_t>& payload);
}
