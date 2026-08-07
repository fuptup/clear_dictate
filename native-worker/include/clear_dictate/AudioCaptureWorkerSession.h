#pragma once

#include "clear_dictate/SpeechAudioCapture.h"
#include "clear_dictate/WorkerProtocol.h"

#include <functional>
#include <memory>
#include <stdexcept>

namespace clear_dictate
{
    enum class AudioCaptureWorkerSessionState
    {
        AwaitingHello,
        Idle,
        RecordingStarting,
        Recording,
        RecordingStopping,
        RecordingCancelling,
        Closed,
        Failed
    };

    enum class AudioCaptureWorkerSessionFailure
    {
        IllegalState,
        IllegalMessage,
        OperationIdentityMismatch
    };

    class AudioCaptureWorkerSessionException final : public std::runtime_error
    {
    public:
        explicit AudioCaptureWorkerSessionException(AudioCaptureWorkerSessionFailure failure);
        AudioCaptureWorkerSessionFailure Failure() const noexcept;

    private:
        AudioCaptureWorkerSessionFailure failure_;
    };

    /**
     * Owns one Windows microphone capture thread and publishes audio chunks without running inference.
     * Release is represented by StopRecording and completes only after every accepted sample is delivered.
     */
    class AudioCaptureWorkerSession final
    {
    public:
        using FrameWriter = std::function<void(const WorkerProtocolFrame&)>;

        AudioCaptureWorkerSession(SpeechAudioCaptureFactory& captureFactory, FrameWriter frameWriter, std::function<void()> fatalFailureHandler = {});
        ~AudioCaptureWorkerSession();

        AudioCaptureWorkerSession(const AudioCaptureWorkerSession&) = delete;
        AudioCaptureWorkerSession& operator=(const AudioCaptureWorkerSession&) = delete;

        void Handle(const WorkerProtocolFrame& frame);
        AudioCaptureWorkerSessionState State() const;
        void Close() noexcept;

    private:
        class Implementation;
        std::unique_ptr<Implementation> implementation_;
    };
}
