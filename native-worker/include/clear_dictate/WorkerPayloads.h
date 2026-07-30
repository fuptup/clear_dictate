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
    TextPolishPrompt BuildTextPolishPrompt(const std::string& cleanTranscript);
}
