#pragma once

#include "clear_dictate\WorkerTranscriptPayloads.h"

#include <cstddef>
#include <cstdint>
#include <memory>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

namespace clear_dictate
{
    class VerifiedModelFile;

    class MoonshineSpeechException final : public std::runtime_error
    {
    public:
        MoonshineSpeechException();
    };

    /**
     * Owns one verified Moonshine transcriber and serializes its stream lifecycle.
     *
     * Every public method must be called from the thread that constructs the
     * engine. This matches the pinned library's actual stream-mutation safety.
     */
    class MoonshineSpeechEngine final
    {
    public:
        explicit MoonshineSpeechEngine(const std::string& utf8ModelDirectory);
        ~MoonshineSpeechEngine();

        MoonshineSpeechEngine(const MoonshineSpeechEngine&) = delete;
        MoonshineSpeechEngine& operator=(const MoonshineSpeechEngine&) = delete;

        void Start();
        void AddAudio(const float* monoSamples, std::size_t sampleCount, std::int32_t sampleRate);
        std::vector<TranscriptDelta> TranscribeChangedLines(bool forceUpdate);
        std::string StopAndFinish();
        void CancelAndDiscard();

    private:
        void RequireOwnerThread() const;
        void RequireSuccess(std::int32_t status) const;
        void ReleaseActiveStream();
        void ReleaseActiveStreamBestEffort() noexcept;
        std::string CopyCompleteTranscript(bool forceUpdate);

        std::vector<std::unique_ptr<VerifiedModelFile>> verifiedModelFiles_;
        std::thread::id ownerThreadIdentifier_;
        std::int32_t transcriberHandle_ = -1;
        std::int32_t streamHandle_ = -1;
        bool isPoisoned_ = false;
    };
}
