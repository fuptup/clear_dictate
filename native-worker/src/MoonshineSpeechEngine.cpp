#include "clear_dictate\MoonshineSpeechEngine.h"

#include "clear_dictate\VerifiedModelFile.h"
#include "moonshine-c-api.h"

#include <array>
#include <exception>
#include <limits>
#include <thread>

namespace clear_dictate
{
    namespace
    {
        struct LockedModelComponent final
        {
            const char* filename;
            std::uint64_t byteCount;
            const char* sha256;
        };

        constexpr std::array<LockedModelComponent, 8> LockedModelComponents =
        {{
            { "adapter.ort", 3647712, "16307442b7f4229f2f1511fc51b545cec9616e55872c588f3a297bbc6f4762ea" },
            { "cross_kv.ort", 11544952, "354b9a955caeb768b528f447f0a36ce4b850ca7b4531900165df304d97904fba" },
            { "decoder_kv.ort", 146216448, "fa67aa87521247f5bf44d3e44d4e4978e58c1f114249c3c6909c882624056715" },
            { "decoder_kv_with_attention.ort", 146138304, "40919de95d08690da3a8ff6df14cf55b3220046f3b767b4a4b769e7b32aaf2d2" },
            { "encoder.ort", 94202872, "a5f11167a62eef61787fe8410453257d6ddb8eba90af461a9604e5f2e93d5322" },
            { "frontend.ort", 47467256, "378fe8a5d7090a1b9ab88bbb1fc95bde010cdd64ec23419350d2d23c675636e9" },
            { "streaming_config.json", 513, "28e83b7a28e91472692a035e0dae3116422ae43aeb2bef5ed822c44ce89b88af" },
            { "tokenizer.bin", 249974, "6884b35fd6377d4c4d32336a0bc152f36b64d1e45b6503683cdc238250a8472d" }
        }};

        std::uint8_t HexNibble(char character)
        {
            if (character >= '0' && character <= '9')
            {
                return static_cast<std::uint8_t>(character - '0');
            }
            if (character >= 'a' && character <= 'f')
            {
                return static_cast<std::uint8_t>(character - 'a' + 10);
            }
            throw MoonshineSpeechException();
        }

        ModelFileExpectation BuildExpectation(const LockedModelComponent& component)
        {
            std::array<std::uint8_t, 32> digest {};
            for (std::size_t byteIndex = 0; byteIndex < digest.size(); ++byteIndex)
            {
                digest[byteIndex] = static_cast<std::uint8_t>(
                    (HexNibble(component.sha256[byteIndex * 2]) << 4) |
                    HexNibble(component.sha256[byteIndex * 2 + 1]));
            }
            return { component.byteCount, digest };
        }

    }

    MoonshineSpeechException::MoonshineSpeechException()
        : std::runtime_error("Moonshine speech engine failure.")
    {
    }

    MoonshineSpeechEngine::MoonshineSpeechEngine(const std::string& utf8ModelDirectory)
        : ownerThreadIdentifier_(std::this_thread::get_id())
    {
        if (utf8ModelDirectory.empty() || utf8ModelDirectory.find('\0') != std::string::npos)
        {
            throw MoonshineSpeechException();
        }

        const char finalDirectoryCharacter = utf8ModelDirectory.back();
        const std::string pathSeparator =
            finalDirectoryCharacter == '/' || finalDirectoryCharacter == '\\' ? std::string() : std::string("/");
        verifiedModelFiles_.reserve(LockedModelComponents.size());
        for (const LockedModelComponent& component : LockedModelComponents)
        {
            const std::string componentPath = utf8ModelDirectory + pathSeparator + component.filename;
            verifiedModelFiles_.push_back(
                std::make_unique<VerifiedModelFile>(componentPath, BuildExpectation(component)));
        }

        const moonshine_option_t options[] =
        {
            { "return_audio_data", "false" },
            { "log_output_text", "false" }
        };
        transcriberHandle_ = moonshine_load_transcriber_from_files(
            utf8ModelDirectory.c_str(),
            MOONSHINE_MODEL_ARCH_MEDIUM_STREAMING,
            options,
            static_cast<std::uint64_t>(std::size(options)),
            MOONSHINE_HEADER_VERSION);
        if (transcriberHandle_ < 0)
        {
            throw MoonshineSpeechException();
        }
    }

    MoonshineSpeechEngine::~MoonshineSpeechEngine()
    {
        if (std::this_thread::get_id() != ownerThreadIdentifier_)
        {
            std::terminate();
        }

        ReleaseActiveStreamBestEffort();
        if (transcriberHandle_ >= 0)
        {
            moonshine_free_transcriber(transcriberHandle_);
        }
    }

    void MoonshineSpeechEngine::Start()
    {
        RequireOwnerThread();
        if (isPoisoned_ || streamHandle_ >= 0)
        {
            throw MoonshineSpeechException();
        }
        streamHandle_ = moonshine_create_stream(transcriberHandle_, 0);
        if (streamHandle_ < 0)
        {
            throw MoonshineSpeechException();
        }
        try
        {
            RequireSuccess(moonshine_start_stream(transcriberHandle_, streamHandle_));
        }
        catch (...)
        {
            isPoisoned_ = true;
            ReleaseActiveStreamBestEffort();
            throw;
        }
    }

    void MoonshineSpeechEngine::AddAudio(const float* monoSamples, std::size_t sampleCount, std::int32_t sampleRate)
    {
        RequireOwnerThread();
        if (streamHandle_ < 0 || monoSamples == nullptr || sampleCount == 0 || sampleRate <= 0)
        {
            throw MoonshineSpeechException();
        }
        try
        {
            RequireSuccess(
                moonshine_transcribe_add_audio_to_stream(
                    transcriberHandle_,
                    streamHandle_,
                    monoSamples,
                    static_cast<std::uint64_t>(sampleCount),
                    sampleRate,
                    0));
        }
        catch (...)
        {
            isPoisoned_ = true;
            ReleaseActiveStreamBestEffort();
            throw;
        }
    }

    std::vector<TranscriptDelta> MoonshineSpeechEngine::TranscribeChangedLines(bool forceUpdate)
    {
        RequireOwnerThread();
        if (streamHandle_ < 0)
        {
            throw MoonshineSpeechException();
        }

        try
        {
            transcript_t* transcript = nullptr;
            RequireSuccess(
                moonshine_transcribe_stream(
                    transcriberHandle_,
                    streamHandle_,
                    forceUpdate ? MOONSHINE_FLAG_FORCE_UPDATE : 0,
                    &transcript));
            if (transcript == nullptr)
            {
                throw MoonshineSpeechException();
            }

            std::vector<TranscriptDelta> deltas;
            for (std::uint64_t lineIndex = 0; lineIndex < transcript->line_count; ++lineIndex)
            {
                const transcript_line_t& line = transcript->lines[lineIndex];
                if (line.is_new == 0 && line.is_updated == 0 && line.has_text_changed == 0)
                {
                    continue;
                }
                deltas.push_back(
                    {
                        line.id,
                        line.is_new != 0,
                        line.is_updated != 0 || line.has_text_changed != 0,
                        line.is_complete != 0,
                        line.text == nullptr ? std::string() : std::string(line.text)
                    });
            }
            return deltas;
        }
        catch (...)
        {
            isPoisoned_ = true;
            ReleaseActiveStreamBestEffort();
            throw;
        }
    }

    std::string MoonshineSpeechEngine::StopAndFinish()
    {
        RequireOwnerThread();
        if (streamHandle_ < 0)
        {
            throw MoonshineSpeechException();
        }
        try
        {
            RequireSuccess(moonshine_stop_stream(transcriberHandle_, streamHandle_));
            std::string finalTranscript = CopyCompleteTranscript(true);
            ReleaseActiveStream();
            return finalTranscript;
        }
        catch (...)
        {
            isPoisoned_ = true;
            ReleaseActiveStreamBestEffort();
            throw;
        }
    }

    void MoonshineSpeechEngine::CancelAndDiscard()
    {
        RequireOwnerThread();
        if (streamHandle_ < 0)
        {
            throw MoonshineSpeechException();
        }
        try
        {
            ReleaseActiveStream();
        }
        catch (...)
        {
            isPoisoned_ = true;
            throw;
        }
    }

    void MoonshineSpeechEngine::RequireOwnerThread() const
    {
        if (std::this_thread::get_id() != ownerThreadIdentifier_)
        {
            throw MoonshineSpeechException();
        }
    }

    void MoonshineSpeechEngine::RequireSuccess(std::int32_t status) const
    {
        if (status != 0)
        {
            throw MoonshineSpeechException();
        }
    }

    void MoonshineSpeechEngine::ReleaseActiveStream()
    {
        if (streamHandle_ >= 0)
        {
            RequireSuccess(moonshine_free_stream(transcriberHandle_, streamHandle_));
            streamHandle_ = -1;
        }
    }

    void MoonshineSpeechEngine::ReleaseActiveStreamBestEffort() noexcept
    {
        if (streamHandle_ >= 0 && std::this_thread::get_id() == ownerThreadIdentifier_)
        {
            static_cast<void>(moonshine_free_stream(transcriberHandle_, streamHandle_));
            streamHandle_ = -1;
        }
    }

    std::string MoonshineSpeechEngine::CopyCompleteTranscript(bool forceUpdate)
    {
        transcript_t* transcript = nullptr;
        RequireSuccess(
            moonshine_transcribe_stream(
                transcriberHandle_,
                streamHandle_,
                forceUpdate ? MOONSHINE_FLAG_FORCE_UPDATE : 0,
                &transcript));
        if (transcript == nullptr)
        {
            throw MoonshineSpeechException();
        }

        std::string combinedText;
        for (std::uint64_t lineIndex = 0; lineIndex < transcript->line_count; ++lineIndex)
        {
            const char* lineText = transcript->lines[lineIndex].text;
            if (lineText == nullptr || lineText[0] == '\0')
            {
                continue;
            }
            if (!combinedText.empty())
            {
                combinedText.push_back(' ');
            }
            combinedText += lineText;
        }
        return combinedText;
    }
}
