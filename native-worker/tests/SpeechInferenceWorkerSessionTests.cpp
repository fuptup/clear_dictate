#include "clear_dictate/SpeechInferenceWorkerSession.h"
#include "clear_dictate/WorkerPayloads.h"
#include "clear_dictate/WorkerTranscriptPayloads.h"

#include <array>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <exception>
#include <iostream>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <thread>
#include <utility>
#include <vector>

namespace
{
    using clear_dictate::BoundedAudioBlockQueue;
    using clear_dictate::OperationPrivacy;
    using clear_dictate::SpeechAudioCapture;
    using clear_dictate::SpeechAudioCaptureFactory;
    using clear_dictate::SpeechInferenceWorkerSession;
    using clear_dictate::SpeechModelLoadRequest;
    using clear_dictate::SpeechRecognitionBackend;
    using clear_dictate::SpeechRecognitionBackendLoader;
    using clear_dictate::TranscriptDelta;
    using clear_dictate::WindowsCaptureActivity;
    using clear_dictate::WindowsCaptureError;
    using clear_dictate::WindowsCaptureState;
    using clear_dictate::WorkerMessageType;
    using clear_dictate::WorkerOperationIdentity;
    using clear_dictate::WorkerProtocolFrame;

    void Require(bool condition, const std::string& failureMessage)
    {
        if (!condition)
        {
            throw std::runtime_error(failureMessage);
        }
    }

    WorkerOperationIdentity TestIdentity(std::uint64_t requestToken = 71)
    {
        return { "speech_session", "recording_operation", OperationPrivacy::Private, requestToken };
    }

    class CapturedFrames final
    {
    public:
        void Add(const WorkerProtocolFrame& frame)
        {
            std::lock_guard<std::mutex> lock(mutex_);
            frames_.push_back(frame);
            changed_.notify_all();
        }

        void WaitForType(WorkerMessageType expectedType)
        {
            std::unique_lock<std::mutex> lock(mutex_);
            const bool received = changed_.wait_for(
                lock,
                std::chrono::seconds(5),
                [this, expectedType]()
                {
                    for (const WorkerProtocolFrame& frame : frames_)
                    {
                        if (frame.type == expectedType)
                        {
                            return true;
                        }
                    }
                    return false;
                });
            Require(received, "Timed out waiting for a speech worker frame.");
        }

        std::vector<WorkerProtocolFrame> Snapshot()
        {
            std::lock_guard<std::mutex> lock(mutex_);
            return frames_;
        }

    private:
        std::mutex mutex_;
        std::condition_variable changed_;
        std::vector<WorkerProtocolFrame> frames_;
    };

    class TestSpeechBackend final : public SpeechRecognitionBackend
    {
    public:
        TestSpeechBackend()
            : ownerThreadIdentifier_(std::this_thread::get_id())
        {
        }

        ~TestSpeechBackend() override
        {
            RequireOwnerThread();
        }

        void Start() override
        {
            RequireOwnerThread();
            {
                std::unique_lock<std::mutex> lock(controlMutex_);
                startEntered_ = true;
                controlChanged_.notify_all();
                controlChanged_.wait(lock, [this]() { return !blockStart_; });
                started_ = true;
            }
        }

        void AddAudio(const float*, std::size_t sampleCount, std::int32_t sampleRate) override
        {
            RequireOwnerThread();
            Require(started_, "Audio must not be added before the speech stream starts.");
            Require(sampleRate == 16000, "The session must supply 16 kHz mono audio.");
            acceptedSampleCount_ += sampleCount;
        }

        std::vector<TranscriptDelta> TranscribeChangedLines(bool) override
        {
            RequireOwnerThread();
            if (partialPublished_ || acceptedSampleCount_ == 0)
            {
                return {};
            }

            partialPublished_ = true;
            return { { 9, true, false, false, "hello" } };
        }

        std::string StopAndFinish() override
        {
            RequireOwnerThread();
            Require(started_, "A final transcript requires an active stream.");
            started_ = false;
            return finalTranscript_;
        }

        void CancelAndDiscard() override
        {
            RequireOwnerThread();
            started_ = false;
            cancelled_ = true;
        }

        bool WasCancelled() const noexcept
        {
            return cancelled_;
        }

        void BlockStart()
        {
            std::lock_guard<std::mutex> lock(controlMutex_);
            blockStart_ = true;
        }

        void WaitUntilStartIsBlocked()
        {
            std::unique_lock<std::mutex> lock(controlMutex_);
            const bool startEntered = controlChanged_.wait_for(
                lock,
                std::chrono::seconds(5),
                [this]() { return startEntered_; });
            Require(startEntered, "Timed out waiting for the recognition thread to enter backend Start.");
        }

        void AllowStart()
        {
            std::lock_guard<std::mutex> lock(controlMutex_);
            blockStart_ = false;
            controlChanged_.notify_all();
        }

        void SetFinalTranscript(std::string finalTranscript)
        {
            finalTranscript_ = std::move(finalTranscript);
        }

    private:
        void RequireOwnerThread() const
        {
            Require(
                std::this_thread::get_id() == ownerThreadIdentifier_,
                "Every speech backend call, including destruction, must stay on one recognition thread.");
        }

        std::thread::id ownerThreadIdentifier_;
        std::mutex controlMutex_;
        std::condition_variable controlChanged_;
        std::size_t acceptedSampleCount_ = 0;
        bool started_ = false;
        bool partialPublished_ = false;
        bool cancelled_ = false;
        bool blockStart_ = false;
        bool startEntered_ = false;
        std::string finalTranscript_ = "hello world";
    };

    class TestSpeechBackendLoader final : public SpeechRecognitionBackendLoader
    {
    public:
        std::unique_ptr<SpeechRecognitionBackend> Load(const SpeechModelLoadRequest& request) override
        {
            loadedRequest = request;
            auto backend = std::make_unique<TestSpeechBackend>();
            backendPointer = backend.get();
            return backend;
        }

        SpeechModelLoadRequest loadedRequest;
        TestSpeechBackend* backendPointer = nullptr;
    };

    class TestCapture final : public SpeechAudioCapture
    {
    public:
        TestCapture(BoundedAudioBlockQueue& destinationQueue, WindowsCaptureError configuredStartError)
            : destinationQueue_(destinationQueue),
              configuredStartError_(configuredStartError)
        {
        }

        WindowsCaptureError Start(const std::wstring& selectedEndpointIdentifier) override
        {
            {
                std::lock_guard<std::mutex> lock(mutex_);
                selectedEndpointIdentifier_ = selectedEndpointIdentifier;
                if (configuredStartError_ != WindowsCaptureError::None)
                {
                    state_ = WindowsCaptureState::Failed;
                    error_ = configuredStartError_;
                    return configuredStartError_;
                }

                if (cancelRequested_)
                {
                    state_ = WindowsCaptureState::Cancelled;
                    return WindowsCaptureError::Cancelled;
                }

                state_ = WindowsCaptureState::Capturing;
            }

            std::array<float, 160> samples {};
            samples.fill(0.25F);
            const clear_dictate::AudioBlockPushResult pushResult =
                destinationQueue_.TryPushSamples(samples.data(), samples.size(), false);
            Require(pushResult == clear_dictate::AudioBlockPushResult::Accepted, "The fake capture must publish its deterministic audio block.");
            acceptedFrameCount_ = samples.size();
            changed_.notify_all();
            return WindowsCaptureError::None;
        }

        void RequestStop() noexcept override
        {
            std::lock_guard<std::mutex> lock(mutex_);
            state_ = WindowsCaptureState::Stopped;
            changed_.notify_all();
        }

        void RequestCancel() noexcept override
        {
            std::lock_guard<std::mutex> lock(mutex_);
            cancelRequested_ = true;
            state_ = WindowsCaptureState::Cancelled;
            changed_.notify_all();
        }

        void JoinProducer() noexcept override
        {
        }

        WindowsCaptureActivity WaitForActivity(std::uint32_t timeoutMilliseconds) noexcept override
        {
            std::unique_lock<std::mutex> lock(mutex_);
            if (!audioActivityReported_ && acceptedFrameCount_ != 0)
            {
                audioActivityReported_ = true;
                return WindowsCaptureActivity::AudioAvailable;
            }
            if (state_ == WindowsCaptureState::Stopped ||
                state_ == WindowsCaptureState::Cancelled ||
                state_ == WindowsCaptureState::Failed)
            {
                return WindowsCaptureActivity::Terminal;
            }

            changed_.wait_for(lock, std::chrono::milliseconds(timeoutMilliseconds));
            return WindowsCaptureActivity::TimedOut;
        }

        WindowsCaptureState State() const noexcept override
        {
            std::lock_guard<std::mutex> lock(mutex_);
            return state_;
        }

        WindowsCaptureError Error() const noexcept override
        {
            std::lock_guard<std::mutex> lock(mutex_);
            return error_;
        }

        std::uint64_t AcceptedFrameCount() const noexcept override
        {
            return acceptedFrameCount_;
        }

    private:
        BoundedAudioBlockQueue& destinationQueue_;
        WindowsCaptureError configuredStartError_;
        mutable std::mutex mutex_;
        std::condition_variable changed_;
        std::wstring selectedEndpointIdentifier_;
        WindowsCaptureState state_ = WindowsCaptureState::Constructed;
        WindowsCaptureError error_ = WindowsCaptureError::None;
        std::uint64_t acceptedFrameCount_ = 0;
        bool audioActivityReported_ = false;
        bool cancelRequested_ = false;
    };

    class TestCaptureFactory final : public SpeechAudioCaptureFactory
    {
    public:
        std::unique_ptr<SpeechAudioCapture> Create(BoundedAudioBlockQueue& destinationQueue) override
        {
            auto capture = std::make_unique<TestCapture>(destinationQueue, configuredStartError);
            capturePointer = capture.get();
            return capture;
        }

        WindowsCaptureError configuredStartError = WindowsCaptureError::None;
        TestCapture* capturePointer = nullptr;
    };

    void CompleteHandshake(SpeechInferenceWorkerSession& session, CapturedFrames& output)
    {
        session.Handle(WorkerProtocolFrame::Control(WorkerMessageType::Hello, {}));
        output.WaitForType(WorkerMessageType::Ready);
        session.Handle(WorkerProtocolFrame::Control(
            WorkerMessageType::LoadModels,
            clear_dictate::EncodeSpeechModelLoadRequest({ "C:\\models\\moonshine" })));
        output.WaitForType(WorkerMessageType::ModelsLoaded);
    }

    void TestCompletedRecordingPublishesPartialAndFinalTranscript()
    {
        TestSpeechBackendLoader loader;
        TestCaptureFactory captureFactory;
        CapturedFrames output;
        SpeechInferenceWorkerSession session(loader, captureFactory, [&output](const WorkerProtocolFrame& frame) { output.Add(frame); });

        CompleteHandshake(session, output);
        const WorkerOperationIdentity identity = TestIdentity();
        session.Handle(WorkerProtocolFrame::Operation(
            WorkerMessageType::StartRecording,
            identity,
            clear_dictate::EncodeRecordingStartRequest({ "" })));
        output.WaitForType(WorkerMessageType::RecordingStarted);
        output.WaitForType(WorkerMessageType::PartialTranscript);

        session.Handle(WorkerProtocolFrame::Operation(WorkerMessageType::StopRecording, identity, {}));
        output.WaitForType(WorkerMessageType::FinalTranscript);

        const std::vector<WorkerProtocolFrame> frames = output.Snapshot();
        Require(loader.loadedRequest.modelDirectory == "C:\\models\\moonshine", "The model request must reach the recognition-thread loader.");
        Require(clear_dictate::DecodeTranscriptDelta(frames[3].payload).text == "hello", "The partial transcript payload must use the shared delta codec.");
        Require(std::string(frames[4].payload.begin(), frames[4].payload.end()) == "hello world", "The final transcript must be preserved.");
        Require(session.State() == clear_dictate::SpeechWorkerSessionState::Idle, "A completed recording must return the worker to Idle.");
    }

    void TestCancellationAcknowledgesThenDiscardsRecording()
    {
        TestSpeechBackendLoader loader;
        TestCaptureFactory captureFactory;
        CapturedFrames output;
        SpeechInferenceWorkerSession session(loader, captureFactory, [&output](const WorkerProtocolFrame& frame) { output.Add(frame); });

        CompleteHandshake(session, output);
        const WorkerOperationIdentity identity = TestIdentity(72);
        session.Handle(WorkerProtocolFrame::Operation(
            WorkerMessageType::StartRecording,
            identity,
            clear_dictate::EncodeRecordingStartRequest({ "" })));
        output.WaitForType(WorkerMessageType::RecordingStarted);
        session.Handle(WorkerProtocolFrame::Operation(WorkerMessageType::Cancel, identity, {}));
        output.WaitForType(WorkerMessageType::OperationCancelled);

        const std::vector<WorkerProtocolFrame> frames = output.Snapshot();
        std::size_t acknowledgementIndex = frames.size();
        std::size_t cancellationIndex = frames.size();
        for (std::size_t frameIndex = 0; frameIndex < frames.size(); ++frameIndex)
        {
            if (frames[frameIndex].type == WorkerMessageType::CancellationAcknowledged)
            {
                acknowledgementIndex = frameIndex;
            }
            if (frames[frameIndex].type == WorkerMessageType::OperationCancelled)
            {
                cancellationIndex = frameIndex;
            }
        }

        Require(acknowledgementIndex < cancellationIndex, "Cancellation acknowledgement must be externally ordered before the terminal cancellation.");
        Require(loader.backendPointer->WasCancelled(), "Cancellation must discard the active speech stream on its owner thread.");
        Require(session.State() == clear_dictate::SpeechWorkerSessionState::Idle, "A cancelled recording must return the worker to Idle.");
    }

    void TestMismatchedStopIsRejected()
    {
        TestSpeechBackendLoader loader;
        TestCaptureFactory captureFactory;
        CapturedFrames output;
        SpeechInferenceWorkerSession session(loader, captureFactory, [&output](const WorkerProtocolFrame& frame) { output.Add(frame); });

        CompleteHandshake(session, output);
        session.Handle(WorkerProtocolFrame::Operation(
            WorkerMessageType::StartRecording,
            TestIdentity(73),
            clear_dictate::EncodeRecordingStartRequest({ "" })));
        output.WaitForType(WorkerMessageType::RecordingStarted);

        bool mismatchRejected = false;
        try
        {
            session.Handle(WorkerProtocolFrame::Operation(WorkerMessageType::StopRecording, TestIdentity(74), {}));
        }
        catch (const clear_dictate::SpeechWorkerSessionException& exception)
        {
            mismatchRejected = exception.Failure() == clear_dictate::SpeechWorkerSessionFailure::OperationIdentityMismatch;
        }

        Require(mismatchRejected, "Stop with a different immutable operation identity must be rejected.");
        session.Handle(WorkerProtocolFrame::Operation(WorkerMessageType::Cancel, TestIdentity(73), {}));
        output.WaitForType(WorkerMessageType::OperationCancelled);
    }

    void TestCancellationWhileSpeechStreamStartsIsLatched()
    {
        TestSpeechBackendLoader loader;
        TestCaptureFactory captureFactory;
        CapturedFrames output;
        SpeechInferenceWorkerSession session(loader, captureFactory, [&output](const WorkerProtocolFrame& frame) { output.Add(frame); });

        CompleteHandshake(session, output);
        loader.backendPointer->BlockStart();
        const WorkerOperationIdentity identity = TestIdentity(75);
        session.Handle(WorkerProtocolFrame::Operation(
            WorkerMessageType::StartRecording,
            identity,
            clear_dictate::EncodeRecordingStartRequest({ "" })));
        loader.backendPointer->WaitUntilStartIsBlocked();
        session.Handle(WorkerProtocolFrame::Operation(WorkerMessageType::Cancel, identity, {}));
        loader.backendPointer->AllowStart();
        output.WaitForType(WorkerMessageType::OperationCancelled);

        const std::vector<WorkerProtocolFrame> frames = output.Snapshot();
        for (const WorkerProtocolFrame& frame : frames)
        {
            Require(frame.type != WorkerMessageType::RecordingStarted, "A cancellation accepted during stream startup must suppress RECORDING_STARTED.");
        }
        Require(loader.backendPointer->WasCancelled(), "A latched startup cancellation must discard the stream on the recognition thread.");
    }

    void TestCaptureStartFailureReturnsReusableOperationError()
    {
        TestSpeechBackendLoader loader;
        TestCaptureFactory captureFactory;
        captureFactory.configuredStartError = WindowsCaptureError::PrivacyBlocked;
        CapturedFrames output;
        SpeechInferenceWorkerSession session(loader, captureFactory, [&output](const WorkerProtocolFrame& frame) { output.Add(frame); });

        CompleteHandshake(session, output);
        session.Handle(WorkerProtocolFrame::Operation(
            WorkerMessageType::StartRecording,
            TestIdentity(76),
            clear_dictate::EncodeRecordingStartRequest({ "" })));
        output.WaitForType(WorkerMessageType::Error);

        const std::vector<WorkerProtocolFrame> frames = output.Snapshot();
        Require(frames.back().payload == std::vector<std::uint8_t>({ 0, 0, 0, 101 }), "Privacy-blocked capture must map to fixed category 101.");
        Require(loader.backendPointer->WasCancelled(), "A capture-start failure must discard the opened speech stream.");
        Require(session.State() == clear_dictate::SpeechWorkerSessionState::Idle, "A capture-start error must leave the verified model reusable.");
    }

    void TestOversizedFinalTranscriptReturnsFixedOperationError()
    {
        TestSpeechBackendLoader loader;
        TestCaptureFactory captureFactory;
        CapturedFrames output;
        SpeechInferenceWorkerSession session(loader, captureFactory, [&output](const WorkerProtocolFrame& frame) { output.Add(frame); });

        CompleteHandshake(session, output);
        loader.backendPointer->SetFinalTranscript(
            std::string(clear_dictate::WorkerProtocolCodec::AbsoluteMaximumPayloadBytes + 1, 'x'));
        const WorkerOperationIdentity identity = TestIdentity(77);
        session.Handle(WorkerProtocolFrame::Operation(
            WorkerMessageType::StartRecording,
            identity,
            clear_dictate::EncodeRecordingStartRequest({ "" })));
        output.WaitForType(WorkerMessageType::RecordingStarted);
        session.Handle(WorkerProtocolFrame::Operation(WorkerMessageType::StopRecording, identity, {}));
        output.WaitForType(WorkerMessageType::Error);

        const std::vector<WorkerProtocolFrame> frames = output.Snapshot();
        Require(frames.back().payload == std::vector<std::uint8_t>({ 0, 0, 0, 5 }), "Oversized final text must map to the fixed native-recognition category.");
        Require(session.State() == clear_dictate::SpeechWorkerSessionState::Idle, "An oversized result must not kill the verified model process.");
    }
}

int main()
{
    try
    {
        TestCompletedRecordingPublishesPartialAndFinalTranscript();
        TestCancellationAcknowledgesThenDiscardsRecording();
        TestMismatchedStopIsRejected();
        TestCancellationWhileSpeechStreamStartsIsLatched();
        TestCaptureStartFailureReturnsReusableOperationError();
        TestOversizedFinalTranscriptReturnsFixedOperationError();
        std::cout << "All speech inference worker session tests passed." << std::endl;
        return 0;
    }
    catch (const std::exception& exception)
    {
        std::cerr << "Speech inference worker session test failure: " << exception.what() << std::endl;
        return 1;
    }
}
