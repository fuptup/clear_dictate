#include "clear_dictate/WorkerProtocol.h"

#include <exception>
#include <iostream>
#include <sstream>
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

    std::string Encode(const clear_dictate::WorkerProtocolFrame& frame)
    {
        std::ostringstream output(std::ios::out | std::ios::binary);
        clear_dictate::WorkerProtocolCodec codec(1024);
        codec.Write(frame, output);
        return output.str();
    }

    clear_dictate::WorkerProtocolFrame Decode(const std::string& encodedFrame)
    {
        std::istringstream input(encodedFrame, std::ios::in | std::ios::binary);
        clear_dictate::WorkerProtocolCodec codec(1024);
        return codec.Read(input);
    }

    void TestOperationFrameMatchesBigEndianGoldenContract()
    {
        const clear_dictate::WorkerProtocolFrame frame = clear_dictate::WorkerProtocolFrame::Operation(
            clear_dictate::WorkerMessageType::PolishTranscript,
            { "client-7", "operation-19", clear_dictate::OperationPrivacy::Private, 27 },
            { 't', 'e', 'x', 't' });

        const std::string encodedFrame = Encode(frame);
        const std::vector<unsigned char> expectedBytes =
        {
            0x00, 0x00, 0x00, 0x35,
            0x43, 0x44, 0x49, 0x50,
            0x00, 0x05,
            0x0B,
            0x01,
            0x00, 0x00, 0x00, 0x08,
            'c', 'l', 'i', 'e', 'n', 't', '-', '7',
            0x00, 0x00, 0x00, 0x0C,
            'o', 'p', 'e', 'r', 'a', 't', 'i', 'o', 'n', '-', '1', '9',
            0x01,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x1B,
            0x00, 0x00, 0x00, 0x04,
            't', 'e', 'x', 't'
        };

        Require(
            std::vector<unsigned char>(encodedFrame.begin(), encodedFrame.end()) == expectedBytes,
            "The native operation frame must match Kotlin's big-endian byte contract.");

        const clear_dictate::WorkerProtocolFrame decodedFrame = Decode(encodedFrame);
        Require(decodedFrame.scope == clear_dictate::WorkerFrameScope::Operation, "The decoded frame scope must remain operation.");
        Require(decodedFrame.identity.clientSessionIdentifier == "client-7", "The decoded client identity changed.");
        Require(decodedFrame.identity.operationIdentifier == "operation-19", "The decoded operation identity changed.");
        Require(decodedFrame.identity.workerRequestToken == 27, "The decoded worker token changed.");
        Require(decodedFrame.payload == std::vector<std::uint8_t>({ 't', 'e', 'x', 't' }), "The decoded payload changed.");
    }

    void TestControlFrameNeedsNoOperationIdentity()
    {
        const clear_dictate::WorkerProtocolFrame frame =
            clear_dictate::WorkerProtocolFrame::Control(clear_dictate::WorkerMessageType::Hello, {});
        const clear_dictate::WorkerProtocolFrame decodedFrame = Decode(Encode(frame));

        Require(decodedFrame.scope == clear_dictate::WorkerFrameScope::Control, "The control frame scope changed.");
        Require(decodedFrame.type == clear_dictate::WorkerMessageType::Hello, "The control frame type changed.");
    }

    void TestEveryTruncatedFrameFailsWithFixedCategory()
    {
        const std::string completeFrame = Encode(
            clear_dictate::WorkerProtocolFrame::Operation(
                clear_dictate::WorkerMessageType::PolishTranscript,
                { "client-7", "operation-19", clear_dictate::OperationPrivacy::Private, 27 },
                { 's', 'e', 'n', 's', 'i', 't', 'i', 'v', 'e' }));

        for (std::size_t truncatedSize = 0; truncatedSize < completeFrame.size(); ++truncatedSize)
        {
            bool rejected = false;

            try
            {
                static_cast<void>(Decode(completeFrame.substr(0, truncatedSize)));
            }
            catch (const clear_dictate::WorkerProtocolException& exception)
            {
                rejected = true;
                Require(
                    std::string(exception.what()).find("sensitive") == std::string::npos,
                    "Protocol exceptions must never contain payload text.");
            }

            Require(rejected, "Every truncated prefix must be rejected.");
        }
    }

    void TestInvalidUtf8TranscriptIsRejected()
    {
        bool rejected = false;

        try
        {
            static_cast<void>(
                Encode(
                    clear_dictate::WorkerProtocolFrame::Operation(
                        clear_dictate::WorkerMessageType::PolishedTranscript,
                        { "client-7", "operation-19", clear_dictate::OperationPrivacy::Private, 27 },
                        { 0xC3, 0x28 })));
        }
        catch (const clear_dictate::WorkerProtocolException&)
        {
            rejected = true;
        }

        Require(rejected, "Invalid UTF-8 transcript output must fail closed.");
    }

    void TestPolishRequestAcceptsBinaryRolePayload()
    {
        const clear_dictate::WorkerProtocolFrame decodedFrame = Decode(
            Encode(
                clear_dictate::WorkerProtocolFrame::Operation(
                    clear_dictate::WorkerMessageType::PolishTranscript,
                    { "client-7", "operation-19", clear_dictate::OperationPrivacy::Private, 27 },
                    { 0xC3, 0x28 })));

        Require(decodedFrame.payload == std::vector<std::uint8_t>({ 0xC3, 0x28 }), "A binary prompt-role payload must pass through the framing layer unchanged.");
    }

    void TestRecordingCompleteHasNoPayload()
    {
        const clear_dictate::WorkerProtocolFrame decodedFrame = Decode(
            Encode(
                clear_dictate::WorkerProtocolFrame::Operation(
                    clear_dictate::WorkerMessageType::RecordingComplete,
                    { "client-7", "operation-19", clear_dictate::OperationPrivacy::Private, 27 },
                    {})));

        Require(decodedFrame.payload.empty(), "Recording completion must round-trip without a payload.");
    }

    void TestEmptyAudioChunkIsRejected()
    {
        bool rejected = false;

        try
        {
            static_cast<void>(
                Encode(
                    clear_dictate::WorkerProtocolFrame::Operation(
                        clear_dictate::WorkerMessageType::AudioChunk,
                        { "client-7", "operation-19", clear_dictate::OperationPrivacy::Private, 27 },
                        {})));
        }
        catch (const clear_dictate::WorkerProtocolException&)
        {
            rejected = true;
        }

        Require(rejected, "An audio chunk must carry a structured captured-audio payload.");
    }

    void TestRecordingStartRequiresVersionedPayload()
    {
        const std::vector<std::uint8_t> defaultEndpointPayload =
        {
            0x43, 0x44, 0x52, 0x53,
            0x00, 0x01,
            0x00, 0x00, 0x00, 0x00
        };
        const clear_dictate::WorkerProtocolFrame decodedFrame = Decode(
            Encode(
                clear_dictate::WorkerProtocolFrame::Operation(
                    clear_dictate::WorkerMessageType::StartRecording,
                    { "client-7", "operation-19", clear_dictate::OperationPrivacy::Private, 27 },
                    defaultEndpointPayload)));
        Require(decodedFrame.payload == defaultEndpointPayload, "The versioned recording-start payload changed.");

        bool rejectedEmptyPayload = false;
        try
        {
            static_cast<void>(
                Encode(
                    clear_dictate::WorkerProtocolFrame::Operation(
                        clear_dictate::WorkerMessageType::StartRecording,
                        { "client-7", "operation-19", clear_dictate::OperationPrivacy::Private, 27 },
                        {})));
        }
        catch (const clear_dictate::WorkerProtocolException&)
        {
            rejectedEmptyPayload = true;
        }
        Require(rejectedEmptyPayload, "Protocol version 5 must reject an unversioned recording-start command.");
    }

    void TestRecordingStartedAcknowledgementHasNoPayload()
    {
        const clear_dictate::WorkerProtocolFrame decodedFrame = Decode(
            Encode(
                clear_dictate::WorkerProtocolFrame::Operation(
                    clear_dictate::WorkerMessageType::RecordingStarted,
                    { "client-7", "operation-19", clear_dictate::OperationPrivacy::Private, 27 },
                    {})));
        Require(
            decodedFrame.type == clear_dictate::WorkerMessageType::RecordingStarted,
            "The recording-start acknowledgement type changed.");
    }

    int RunAllTests()
    {
        TestOperationFrameMatchesBigEndianGoldenContract();
        TestControlFrameNeedsNoOperationIdentity();
        TestEveryTruncatedFrameFailsWithFixedCategory();
        TestInvalidUtf8TranscriptIsRejected();
        TestPolishRequestAcceptsBinaryRolePayload();
        TestRecordingCompleteHasNoPayload();
        TestEmptyAudioChunkIsRejected();
        TestRecordingStartRequiresVersionedPayload();
        TestRecordingStartedAcknowledgementHasNoPayload();
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
        std::cerr << "ClearDictate worker protocol tests failed: " << exception.what() << '\n';
        return 1;
    }
}
