#include "clear_dictate/WindowsCaptureDeviceEnumerator.h"

#define NOMINMAX
#include <Windows.h>
#include <propsys.h>
#include <functiondiscoverykeys_devpkey.h>
#include <mmdeviceapi.h>
#include <wrl/client.h>

#include <algorithm>
#include <string>
#include <utility>
#include <vector>

namespace clear_dictate
{
    namespace
    {
        using Microsoft::WRL::ComPtr;

        constexpr std::size_t MaximumEndpointIdentifierBytes = 4000;
        constexpr std::size_t MaximumFriendlyNameBytes = 1000;

        class ComApartment final
        {
        public:
            ComApartment()
            {
                const HRESULT initializationResult = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
                if (initializationResult == RPC_E_CHANGED_MODE)
                {
                    return;
                }
                if (FAILED(initializationResult))
                {
                    throw WindowsCaptureDeviceEnumerationException();
                }
                shouldUninitialize_ = true;
            }

            ~ComApartment()
            {
                if (shouldUninitialize_)
                {
                    CoUninitialize();
                }
            }

            ComApartment(const ComApartment&) = delete;
            ComApartment& operator=(const ComApartment&) = delete;

        private:
            bool shouldUninitialize_ = false;
        };

        class EndpointIdentifier final
        {
        public:
            ~EndpointIdentifier()
            {
                CoTaskMemFree(value_);
            }

            wchar_t** Address() noexcept
            {
                return &value_;
            }

            const wchar_t* Value() const noexcept
            {
                return value_;
            }

            EndpointIdentifier(const EndpointIdentifier&) = delete;
            EndpointIdentifier& operator=(const EndpointIdentifier&) = delete;
            EndpointIdentifier() = default;

        private:
            wchar_t* value_ = nullptr;
        };

        class PropertyValue final
        {
        public:
            PropertyValue()
            {
                PropVariantInit(&value_);
            }

            ~PropertyValue()
            {
                PropVariantClear(&value_);
            }

            PROPVARIANT* Address() noexcept
            {
                return &value_;
            }

            const PROPVARIANT& Value() const noexcept
            {
                return value_;
            }

            PropertyValue(const PropertyValue&) = delete;
            PropertyValue& operator=(const PropertyValue&) = delete;

        private:
            PROPVARIANT value_ {};
        };

        std::string ConvertUtf16ToUtf8(const wchar_t* value, std::size_t maximumUtf8Bytes)
        {
            if (value == nullptr || value[0] == L'\0')
            {
                throw WindowsCaptureDeviceEnumerationException();
            }

            const int requiredBytes = WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, value, -1, nullptr, 0, nullptr, nullptr);
            if (requiredBytes <= 1 || static_cast<std::size_t>(requiredBytes - 1) > maximumUtf8Bytes)
            {
                throw WindowsCaptureDeviceEnumerationException();
            }

            std::string convertedValue(static_cast<std::size_t>(requiredBytes), '\0');
            const int writtenBytes = WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, value, -1, convertedValue.data(), requiredBytes, nullptr, nullptr);
            if (writtenBytes != requiredBytes)
            {
                throw WindowsCaptureDeviceEnumerationException();
            }
            convertedValue.resize(static_cast<std::size_t>(requiredBytes - 1));
            return convertedValue;
        }

        std::wstring ReadDefaultEndpointIdentifier(IMMDeviceEnumerator& deviceEnumerator)
        {
            ComPtr<IMMDevice> defaultDevice;
            const HRESULT defaultDeviceResult = deviceEnumerator.GetDefaultAudioEndpoint(eCapture, eConsole, &defaultDevice);
            if (defaultDeviceResult == E_NOTFOUND)
            {
                return {};
            }
            if (FAILED(defaultDeviceResult))
            {
                throw WindowsCaptureDeviceEnumerationException();
            }

            EndpointIdentifier endpointIdentifier;
            if (FAILED(defaultDevice->GetId(endpointIdentifier.Address())) || endpointIdentifier.Value() == nullptr)
            {
                throw WindowsCaptureDeviceEnumerationException();
            }
            return endpointIdentifier.Value();
        }

        CaptureDeviceDescription ReadDevice(IMMDevice& device, const std::wstring& defaultEndpointIdentifier)
        {
            EndpointIdentifier endpointIdentifier;
            if (FAILED(device.GetId(endpointIdentifier.Address())) || endpointIdentifier.Value() == nullptr)
            {
                throw WindowsCaptureDeviceEnumerationException();
            }

            ComPtr<IPropertyStore> propertyStore;
            if (FAILED(device.OpenPropertyStore(STGM_READ, &propertyStore)))
            {
                throw WindowsCaptureDeviceEnumerationException();
            }

            PropertyValue friendlyName;
            if (FAILED(propertyStore->GetValue(PKEY_Device_FriendlyName, friendlyName.Address())) || friendlyName.Value().vt != VT_LPWSTR)
            {
                throw WindowsCaptureDeviceEnumerationException();
            }

            return
            {
                ConvertUtf16ToUtf8(endpointIdentifier.Value(), MaximumEndpointIdentifierBytes),
                ConvertUtf16ToUtf8(friendlyName.Value().pwszVal, MaximumFriendlyNameBytes),
                !defaultEndpointIdentifier.empty() && defaultEndpointIdentifier == endpointIdentifier.Value()
            };
        }
    }

    WindowsCaptureDeviceEnumerationException::WindowsCaptureDeviceEnumerationException()
        : std::runtime_error("Windows capture-device enumeration failed.")
    {
    }

    std::vector<CaptureDeviceDescription> WindowsCaptureDeviceEnumerator::EnumerateActiveCaptureDevices() const
    {
        const ComApartment apartment;

        ComPtr<IMMDeviceEnumerator> deviceEnumerator;
        const HRESULT createResult = CoCreateInstance(
            __uuidof(MMDeviceEnumerator),
            nullptr,
            CLSCTX_INPROC_SERVER,
            IID_PPV_ARGS(&deviceEnumerator));
        if (FAILED(createResult))
        {
            throw WindowsCaptureDeviceEnumerationException();
        }

        const std::wstring defaultEndpointIdentifier = ReadDefaultEndpointIdentifier(*deviceEnumerator.Get());
        ComPtr<IMMDeviceCollection> activeDevices;
        if (FAILED(deviceEnumerator->EnumAudioEndpoints(eCapture, DEVICE_STATE_ACTIVE, &activeDevices)))
        {
            throw WindowsCaptureDeviceEnumerationException();
        }

        UINT deviceCount = 0;
        if (FAILED(activeDevices->GetCount(&deviceCount)))
        {
            throw WindowsCaptureDeviceEnumerationException();
        }

        std::vector<CaptureDeviceDescription> devices;
        devices.reserve(deviceCount);
        for (UINT deviceIndex = 0; deviceIndex < deviceCount; ++deviceIndex)
        {
            ComPtr<IMMDevice> device;
            if (FAILED(activeDevices->Item(deviceIndex, &device)))
            {
                throw WindowsCaptureDeviceEnumerationException();
            }
            devices.push_back(ReadDevice(*device.Get(), defaultEndpointIdentifier));
        }

        std::sort(
            devices.begin(),
            devices.end(),
            [](const CaptureDeviceDescription& left, const CaptureDeviceDescription& right)
            {
                if (left.isDefault != right.isDefault)
                {
                    return left.isDefault;
                }
                if (left.friendlyName != right.friendlyName)
                {
                    return left.friendlyName < right.friendlyName;
                }
                return left.endpointIdentifier < right.endpointIdentifier;
            });
        return devices;
    }
}
