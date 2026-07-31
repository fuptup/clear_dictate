#include "clear_dictate/CaptureDeviceListPayload.h"

#include <cstdint>
#include <exception>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

namespace
{
    void Require(bool condition, const std::string& failureMessage)
    {
        if (!condition)
        {
            throw std::runtime_error(failureMessage);
        }
    }

    void TestRoundTripAndGoldenBytes()
    {
        const std::vector<clear_dictate::CaptureDeviceDescription> devices =
        {
            { "endpoint_1", "Desk microphone", true },
            { "endpoint_2", "Webcam microphone", false }
        };
        const std::vector<std::uint8_t> encoded = clear_dictate::EncodeCaptureDeviceList(devices);
        const std::vector<std::uint8_t> expected =
        {
            0x43, 0x44, 0x41, 0x4C,
            0x00, 0x01,
            0x00, 0x02,
            0x01,
            0x00, 0x00, 0x00, 0x0A,
            0x00, 0x00, 0x00, 0x0F,
            'e', 'n', 'd', 'p', 'o', 'i', 'n', 't', '_', '1',
            'D', 'e', 's', 'k', ' ', 'm', 'i', 'c', 'r', 'o', 'p', 'h', 'o', 'n', 'e',
            0x00,
            0x00, 0x00, 0x00, 0x0A,
            0x00, 0x00, 0x00, 0x11,
            'e', 'n', 'd', 'p', 'o', 'i', 'n', 't', '_', '2',
            'W', 'e', 'b', 'c', 'a', 'm', ' ', 'm', 'i', 'c', 'r', 'o', 'p', 'h', 'o', 'n', 'e'
        };

        Require(encoded == expected, "The device-list payload must retain its cross-language golden representation.");
        Require(clear_dictate::DecodeCaptureDeviceList(encoded) == devices, "The device-list payload must round-trip.");
    }

    void TestDuplicateEndpointIsRejected()
    {
        bool rejected = false;
        try
        {
            static_cast<void>(clear_dictate::EncodeCaptureDeviceList(
                {
                    { "same_endpoint", "First", true },
                    { "same_endpoint", "Second", false }
                }));
        }
        catch (const clear_dictate::CaptureDeviceListPayloadException& exception)
        {
            rejected = exception.Failure() == clear_dictate::CaptureDeviceListPayloadFailure::DuplicateIdentifier;
        }
        Require(rejected, "Duplicate endpoint identifiers must be rejected.");
    }

    void TestMultipleDefaultsAreRejected()
    {
        bool rejected = false;
        try
        {
            static_cast<void>(clear_dictate::EncodeCaptureDeviceList(
                {
                    { "endpoint_1", "First", true },
                    { "endpoint_2", "Second", true }
                }));
        }
        catch (const clear_dictate::CaptureDeviceListPayloadException& exception)
        {
            rejected = exception.Failure() == clear_dictate::CaptureDeviceListPayloadFailure::MultipleDefaultDevices;
        }
        Require(rejected, "Only one default capture endpoint may be published.");
    }

    void TestMalformedUtf8IsRejected()
    {
        std::vector<std::uint8_t> payload = clear_dictate::EncodeCaptureDeviceList(
            { { "endpoint_1", "Microphone", true } });
        payload.back() = 0xFF;

        bool rejected = false;
        try
        {
            static_cast<void>(clear_dictate::DecodeCaptureDeviceList(payload));
        }
        catch (const clear_dictate::CaptureDeviceListPayloadException& exception)
        {
            rejected = exception.Failure() == clear_dictate::CaptureDeviceListPayloadFailure::InvalidUtf8;
        }
        Require(rejected, "Malformed UTF-8 device text must be rejected.");
    }
}

int main()
{
    try
    {
        TestRoundTripAndGoldenBytes();
        TestDuplicateEndpointIsRejected();
        TestMultipleDefaultsAreRejected();
        TestMalformedUtf8IsRejected();
        std::cout << "All capture device list payload tests passed." << std::endl;
        return 0;
    }
    catch (const std::exception& exception)
    {
        std::cerr << "Capture device list payload test failure: " << exception.what() << std::endl;
        return 1;
    }
}
