#pragma once

#include <cstdint>
#include <stdexcept>
#include <string>
#include <vector>

namespace clear_dictate
{
    struct TextModelLoadRequest final
    {
        std::string utf8ModelPath;
        std::int32_t inferenceThreadCount;
    };

    /**
     * Identifies the application-private directory containing every locked
     * Moonshine model component required by the speech worker.
     */
    struct SpeechModelLoadRequest final
    {
        std::string modelDirectory;
    };

    /**
     * Selects one exact Windows capture endpoint. An empty identifier requests
     * the default console capture endpoint resolved once at recording start.
     */
    struct RecordingStartRequest final
    {
        std::string utf8EndpointIdentifier;
    };

    struct TextPolishPrompt final
    {
        std::string systemInstruction;
        std::string userInstruction;
    };

    enum class WorkerPayloadFailure
    {
        InvalidMagic,
        UnsupportedVersion,
        InvalidThreadCount,
        InvalidPath,
        InvalidEndpointIdentifier,
        InvalidLength,
        TrailingBytes
    };

    class WorkerPayloadException final : public std::runtime_error
    {
    public:
        explicit WorkerPayloadException(WorkerPayloadFailure failure);

        WorkerPayloadFailure Failure() const noexcept;

    private:
        WorkerPayloadFailure failure_;
    };

    std::vector<std::uint8_t> EncodeTextModelLoadRequest(const TextModelLoadRequest& request);
    TextModelLoadRequest DecodeTextModelLoadRequest(const std::vector<std::uint8_t>& payload);
    std::vector<std::uint8_t> EncodeSpeechModelLoadRequest(const SpeechModelLoadRequest& request);
    SpeechModelLoadRequest DecodeSpeechModelLoadRequest(const std::vector<std::uint8_t>& payload);
    std::vector<std::uint8_t> EncodeRecordingStartRequest(const RecordingStartRequest& request);
    RecordingStartRequest DecodeRecordingStartRequest(const std::vector<std::uint8_t>& payload);
    TextPolishPrompt BuildTextPolishPrompt(const std::string& cleanTranscript);
}
