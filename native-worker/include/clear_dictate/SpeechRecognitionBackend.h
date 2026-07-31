#pragma once

#include "clear_dictate/WorkerPayloads.h"
#include "clear_dictate/WorkerTranscriptPayloads.h"

#include <cstddef>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace clear_dictate
{
    /**
     * Owns one speech-recognition model and one recording stream at a time.
     *
     * The backend is constructed, used, and destroyed on one recognition thread.
     * This explicit ownership matches Moonshine's stream-mutation requirements.
     */
    class SpeechRecognitionBackend
    {
    public:
        virtual ~SpeechRecognitionBackend() = default;

        virtual void Start() = 0;
        virtual void AddAudio(const float* monoSamples, std::size_t sampleCount, std::int32_t sampleRate) = 0;
        virtual std::vector<TranscriptDelta> TranscribeChangedLines(bool forceUpdate) = 0;
        virtual std::string StopAndFinish() = 0;
        virtual void CancelAndDiscard() = 0;
    };

    /**
     * Creates a verified speech backend on the recognition thread that will own it.
     */
    class SpeechRecognitionBackendLoader
    {
    public:
        virtual ~SpeechRecognitionBackendLoader() = default;
        virtual std::unique_ptr<SpeechRecognitionBackend> Load(const SpeechModelLoadRequest& request) = 0;
    };
}
