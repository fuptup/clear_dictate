#include "clear_dictate/CapturedAudioPayload.h"

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

    void TestCrossLanguageGoldenPayload()
    {
        const float samples[] = { 0.0F, 0.25F, -0.5F };
        const std::vector<std::uint8_t> payload = clear_dictate::EncodeCapturedAudioChunk(samples, 3, 16000, 64 * 1024);
        const std::vector<std::uint8_t> expected =
        {
            0x43, 0x44, 0x41, 0x55,
            0x00, 0x01,
            0x00, 0x01,
            0x00, 0x00, 0x3E, 0x80,
            0x00, 0x00, 0x00, 0x03,
            0x00, 0x00, 0x00, 0x00,
            0x3E, 0x80, 0x00, 0x00,
            0xBF, 0x00, 0x00, 0x00
        };
        Require(payload == expected, "The native captured-audio payload changed from the Kotlin golden bytes.");
    }

    void TestPayloadBoundIsEnforced()
    {
        const float sample = 0.0F;
        bool rejected = false;
        try
        {
            static_cast<void>(clear_dictate::EncodeCapturedAudioChunk(&sample, 1, 16000, 16));
        }
        catch (const clear_dictate::CapturedAudioPayloadException& exception)
        {
            rejected = exception.Failure() == clear_dictate::CapturedAudioPayloadFailure::PayloadTooLarge;
        }
        Require(rejected, "A captured-audio chunk must fit the worker protocol payload bound.");
    }
}

int main()
{
    try
    {
        TestCrossLanguageGoldenPayload();
        TestPayloadBoundIsEnforced();
        std::cout << "All captured-audio payload tests passed." << std::endl;
        return 0;
    }
    catch (const std::exception& exception)
    {
        std::cerr << "Captured-audio payload test failure: " << exception.what() << std::endl;
        return 1;
    }
}
