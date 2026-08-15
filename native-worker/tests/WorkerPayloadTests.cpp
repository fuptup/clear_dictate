#include "clear_dictate/WorkerPayloads.h"

#include <exception>
#include <iostream>
#include <stdexcept>
#include <string>

namespace
{
    void Require(bool condition, const std::string& failureMessage)
    {
        if (!condition)
        {
            throw std::runtime_error(failureMessage);
        }
    }

    void TestModelLoadPayloadRoundTripsUtf8Path()
    {
        const clear_dictate::TextModelLoadRequest request { "C:/模型/qwen.gguf", 4 };
        const clear_dictate::TextModelLoadRequest decodedRequest =
            clear_dictate::DecodeTextModelLoadRequest(clear_dictate::EncodeTextModelLoadRequest(request));

        Require(decodedRequest.utf8ModelPath == request.utf8ModelPath, "The model path changed during payload round trip.");
        Require(decodedRequest.inferenceThreadCount == 4, "The model thread count changed during payload round trip.");
    }

    void TestTextPolishPayloadPreservesSeparatePromptRoles()
    {
        const std::vector<std::uint8_t> payload =
        {
            0x43, 0x44, 0x54, 0x50,
            0x00, 0x01,
            0x00, 0x00, 0x00, 0x06,
            0x00, 0x00, 0x00, 0x04,
            's', 'y', 's', 't', 'e', 'm',
            'u', 's', 'e', 'r'
        };
        const clear_dictate::TextPolishPrompt prompt = clear_dictate::DecodeTextPolishPrompt(payload);

        Require(prompt.systemInstruction == "system", "The trusted system instruction changed during payload decoding.");
        Require(prompt.userInstruction == "user", "The untrusted user message changed during payload decoding.");
    }

    void TestInvalidThreadCountIsRejectedBeforeWorkerLoad()
    {
        bool rejected = false;

        try
        {
            static_cast<void>(
                clear_dictate::EncodeTextModelLoadRequest({ "C:/models/qwen.gguf", 0 }));
        }
        catch (const clear_dictate::WorkerPayloadException&)
        {
            rejected = true;
        }

        Require(rejected, "A non-positive model thread count must be rejected.");
    }

    void TestRecordingStartPayloadRoundTripsDefaultAndSelectedEndpoints()
    {
        const clear_dictate::RecordingStartRequest defaultRequest { "" };
        const clear_dictate::RecordingStartRequest decodedDefault =
            clear_dictate::DecodeRecordingStartRequest(clear_dictate::EncodeRecordingStartRequest(defaultRequest));
        Require(decodedDefault.utf8EndpointIdentifier.empty(), "An empty endpoint must preserve default-device selection.");

        const clear_dictate::RecordingStartRequest selectedRequest { "Microphone \xC3\xA9" };
        const std::vector<std::uint8_t> encodedSelectedRequest =
            clear_dictate::EncodeRecordingStartRequest(selectedRequest);
        const std::vector<std::uint8_t> expectedSelectedPayload =
        {
            0x43, 0x44, 0x52, 0x53,
            0x00, 0x01,
            0x00, 0x00, 0x00, 0x0D,
            'M', 'i', 'c', 'r', 'o', 'p', 'h', 'o', 'n', 'e', ' ', 0xC3, 0xA9
        };
        const clear_dictate::RecordingStartRequest decodedSelected =
            clear_dictate::DecodeRecordingStartRequest(encodedSelectedRequest);
        Require(
            encodedSelectedRequest == expectedSelectedPayload,
            "The recording-start payload changed from the cross-language golden bytes.");
        Require(
            decodedSelected.utf8EndpointIdentifier == selectedRequest.utf8EndpointIdentifier,
            "A selected endpoint identifier changed during payload round trip.");
    }

    void TestRecordingStartPayloadRejectsMalformedUtf8()
    {
        const std::vector<std::uint8_t> malformedPayload =
        {
            0x43, 0x44, 0x52, 0x53,
            0x00, 0x01,
            0x00, 0x00, 0x00, 0x02,
            0xC3, 0x28
        };
        bool rejected = false;

        try
        {
            static_cast<void>(clear_dictate::DecodeRecordingStartRequest(malformedPayload));
        }
        catch (const clear_dictate::WorkerPayloadException&)
        {
            rejected = true;
        }

        Require(rejected, "A malformed UTF-8 endpoint identifier must be rejected.");
    }

    int RunAllTests()
    {
        TestModelLoadPayloadRoundTripsUtf8Path();
        TestTextPolishPayloadPreservesSeparatePromptRoles();
        TestInvalidThreadCountIsRejectedBeforeWorkerLoad();
        TestRecordingStartPayloadRoundTripsDefaultAndSelectedEndpoints();
        TestRecordingStartPayloadRejectsMalformedUtf8();
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
        std::cerr << "ClearDictate worker payload tests failed: " << exception.what() << '\n';
        return 1;
    }
}
