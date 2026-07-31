#pragma once

#include "clear_dictate/SpeechAudioCapture.h"
#include "clear_dictate/SpeechRecognitionBackend.h"
#include "clear_dictate/WorkerProtocol.h"

#include <functional>
#include <memory>
#include <stdexcept>

namespace clear_dictate
{
    enum class SpeechWorkerSessionState
    {
        AwaitingHello,
        AwaitingModelLoad,
        LoadingModel,
        Idle,
        RecordingStarting,
        Recording,
        RecordingStopping,
        RecordingCancelling,
        Closed,
        Failed
    };

    enum class SpeechWorkerSessionFailure
    {
        IllegalState,
        IllegalMessage,
        OperationIdentityMismatch
    };

    class SpeechWorkerSessionException final : public std::runtime_error
    {
    public:
        explicit SpeechWorkerSessionException(SpeechWorkerSessionFailure failure);

        SpeechWorkerSessionFailure Failure() const noexcept;

    private:
        SpeechWorkerSessionFailure failure_;
    };

    /**
     * Enforces the speech worker protocol while a dedicated recognition thread
     * owns model loading, streaming inference, capture joins, and model teardown.
     */
    class SpeechInferenceWorkerSession final
    {
    public:
        using FrameWriter = std::function<void(const WorkerProtocolFrame&)>;

        SpeechInferenceWorkerSession(
            SpeechRecognitionBackendLoader& backendLoader,
            SpeechAudioCaptureFactory& captureFactory,
            FrameWriter frameWriter,
            std::function<void()> fatalFailureHandler = {});
        ~SpeechInferenceWorkerSession();

        SpeechInferenceWorkerSession(const SpeechInferenceWorkerSession&) = delete;
        SpeechInferenceWorkerSession& operator=(const SpeechInferenceWorkerSession&) = delete;

        void Handle(const WorkerProtocolFrame& frame);
        SpeechWorkerSessionState State() const;
        void Close() noexcept;

    private:
        class Implementation;
        std::unique_ptr<Implementation> implementation_;
    };
}
