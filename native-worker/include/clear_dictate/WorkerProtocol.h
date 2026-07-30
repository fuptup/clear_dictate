#pragma once

#include <cstddef>
#include <cstdint>
#include <iosfwd>
#include <stdexcept>
#include <string>
#include <vector>

namespace clear_dictate
{
    enum class WorkerMessageType : std::uint8_t
    {
        Hello = 1,
        Ready = 2,
        LoadModels = 3,
        StartRecording = 4,
        StopRecording = 5,
        Cancel = 6,
        CancellationAcknowledged = 7,
        AudioLevel = 8,
        PartialTranscript = 9,
        FinalTranscript = 10,
        PolishTranscript = 11,
        PolishedTranscript = 12,
        Error = 13,
        Shutdown = 14,
        ModelsLoaded = 15,
        ControlError = 16,
        OperationCancelled = 17
    };

    enum class WorkerFrameScope : std::uint8_t
    {
        Control = 0,
        Operation = 1
    };

    enum class OperationPrivacy : std::uint8_t
    {
        Standard = 0,
        Private = 1
    };

    struct WorkerOperationIdentity final
    {
        std::string clientSessionIdentifier;
        std::string operationIdentifier;
        OperationPrivacy privacy = OperationPrivacy::Standard;
        std::uint64_t workerRequestToken = 0;
    };

    struct WorkerProtocolFrame final
    {
        WorkerMessageType type;
        WorkerFrameScope scope;
        WorkerOperationIdentity identity;
        std::vector<std::uint8_t> payload;

        static WorkerProtocolFrame Control(WorkerMessageType type, std::vector<std::uint8_t> payload);
        static WorkerProtocolFrame Operation(WorkerMessageType type, WorkerOperationIdentity identity, std::vector<std::uint8_t> payload);
    };

    enum class WorkerProtocolFailure
    {
        InvalidFrameLength,
        InvalidMagic,
        UnsupportedVersion,
        UnknownMessageType,
        InvalidFrameScope,
        MessageScopeMismatch,
        InvalidIdentifier,
        InvalidPrivacy,
        InvalidWorkerRequestToken,
        InvalidPayloadLength,
        InvalidMessagePayload,
        InvalidUtf8,
        TruncatedFrame,
        TrailingBytes
    };

    /**
     * Fixed-category protocol exception that never includes frame or payload bytes.
     */
    class WorkerProtocolException final : public std::runtime_error
    {
    public:
        explicit WorkerProtocolException(WorkerProtocolFailure failure);

        WorkerProtocolFailure Failure() const noexcept;

    private:
        WorkerProtocolFailure failure_;
    };

    /**
     * Strict version-3 big-endian codec shared with the Kotlin desktop supervisor.
     */
    class WorkerProtocolCodec final
    {
    public:
        static constexpr std::uint32_t Magic = 0x43444950;
        static constexpr std::uint16_t ProtocolVersion = 3;
        static constexpr std::size_t AbsoluteMaximumPayloadBytes = 64 * 1024;

        explicit WorkerProtocolCodec(std::size_t maximumPayloadBytes = AbsoluteMaximumPayloadBytes);

        WorkerProtocolFrame Read(std::istream& input) const;
        void Write(const WorkerProtocolFrame& frame, std::ostream& output) const;

    private:
        std::size_t maximumPayloadBytes_;
    };
}
