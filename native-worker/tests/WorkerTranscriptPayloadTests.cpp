#include "clear_dictate/WorkerTranscriptPayloads.h"

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

    void TestDeltaMatchesTheCrossLanguageGoldenContract()
    {
        const clear_dictate::TranscriptDelta delta { 42, true, false, true, "Hello" };
        const std::vector<std::uint8_t> expected =
        {
            0x43, 0x44, 0x54, 0x44,
            0x00, 0x01,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x2A,
            0x05,
            0x00, 0x00, 0x00, 0x05,
            'H', 'e', 'l', 'l', 'o'
        };

        const std::vector<std::uint8_t> encoded = clear_dictate::EncodeTranscriptDelta(delta);
        Require(encoded == expected, "The transcript delta bytes changed.");
        Require(clear_dictate::DecodeTranscriptDelta(encoded) == delta, "The transcript delta did not round-trip.");
    }

    void TestMalformedFlagsAreRejected()
    {
        std::vector<std::uint8_t> payload = clear_dictate::EncodeTranscriptDelta({ 1, true, false, false, "text" });
        payload[14] = 0x80;

        bool rejected = false;
        try
        {
            static_cast<void>(clear_dictate::DecodeTranscriptDelta(payload));
        }
        catch (const clear_dictate::TranscriptPayloadException&)
        {
            rejected = true;
        }

        Require(rejected, "Unknown transcript flags must be rejected.");
    }

    void TestInvalidUtf8IsRejected()
    {
        std::vector<std::uint8_t> payload = clear_dictate::EncodeTranscriptDelta({ 1, true, false, false, "ok" });
        payload[payload.size() - 2] = 0xC3;
        payload[payload.size() - 1] = 0x28;

        bool rejected = false;
        try
        {
            static_cast<void>(clear_dictate::DecodeTranscriptDelta(payload));
        }
        catch (const clear_dictate::TranscriptPayloadException&)
        {
            rejected = true;
        }
        Require(rejected, "Invalid UTF-8 transcript text must be rejected.");
    }

    void TestOpaqueHighBitLineIdentifierRoundTrips()
    {
        const clear_dictate::TranscriptDelta delta { 0x8000000000000000ULL, true, false, false, "opaque" };
        Require(
            clear_dictate::DecodeTranscriptDelta(clear_dictate::EncodeTranscriptDelta(delta)) == delta,
            "A Moonshine line identifier with its high bit set must remain opaque.");
    }

    void TestStickyCompletionWithoutAChangeIsRejected()
    {
        bool rejected = false;
        try
        {
            static_cast<void>(clear_dictate::EncodeTranscriptDelta({ 1, false, false, true, "stale" }));
        }
        catch (const clear_dictate::TranscriptPayloadException&)
        {
            rejected = true;
        }
        Require(rejected, "Sticky completion state alone must not be encoded as a changed line.");
    }

    void TestEveryTruncatedPrefixAndTrailingByteAreRejected()
    {
        const std::vector<std::uint8_t> encoded =
            clear_dictate::EncodeTranscriptDelta({ 1, true, false, false, "sensitive" });
        for (std::size_t truncatedSize = 0; truncatedSize < encoded.size(); ++truncatedSize)
        {
            bool rejected = false;
            try
            {
                static_cast<void>(
                    clear_dictate::DecodeTranscriptDelta(
                        std::vector<std::uint8_t>(encoded.begin(), encoded.begin() + static_cast<std::ptrdiff_t>(truncatedSize))));
            }
            catch (const clear_dictate::TranscriptPayloadException&)
            {
                rejected = true;
            }
            Require(rejected, "Every truncated transcript payload must be rejected.");
        }

        std::vector<std::uint8_t> withTrailingByte = encoded;
        withTrailingByte.push_back(0);
        bool trailingRejected = false;
        try
        {
            static_cast<void>(clear_dictate::DecodeTranscriptDelta(withTrailingByte));
        }
        catch (const clear_dictate::TranscriptPayloadException&)
        {
            trailingRejected = true;
        }
        Require(trailingRejected, "Trailing transcript payload bytes must be rejected.");
    }

    void TestEmptyRecognitionTextRoundTrips()
    {
        const clear_dictate::TranscriptDelta delta { 1, true, false, false, "" };
        Require(
            clear_dictate::DecodeTranscriptDelta(clear_dictate::EncodeTranscriptDelta(delta)) == delta,
            "An empty recognized line must remain valid.");
    }

    int RunAllTests()
    {
        TestDeltaMatchesTheCrossLanguageGoldenContract();
        TestMalformedFlagsAreRejected();
        TestInvalidUtf8IsRejected();
        TestOpaqueHighBitLineIdentifierRoundTrips();
        TestStickyCompletionWithoutAChangeIsRejected();
        TestEveryTruncatedPrefixAndTrailingByteAreRejected();
        TestEmptyRecognitionTextRoundTrips();
        return 0;
    }
}

int main()
{
    try
    {
        return RunAllTests();
    }
    catch (const std::exception& exception)
    {
        std::cerr << "ClearDictate transcript payload tests failed: " << exception.what() << '\n';
        return 1;
    }
}
