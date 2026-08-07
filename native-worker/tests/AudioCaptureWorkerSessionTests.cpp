#include "clear_dictate/AudioCaptureWorkerSession.h"
#include "clear_dictate/WorkerPayloads.h"

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
#include <vector>

namespace
{
    using clear_dictate::AudioCaptureWorkerSession;
    using clear_dictate::AudioCaptureWorkerSessionState;
    using clear_dictate::BoundedAudioBlockQueue;
    using clear_dictate::OperationPrivacy;
    using clear_dictate::SpeechAudioCapture;
    using clear_dictate::SpeechAudioCaptureFactory;
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

    WorkerOperationIdentity TestIdentity(std::uint64_t requestToken)
    {
        return { "capture_session", "capture_operation", OperationPrivacy::Private, requestToken };
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
            const bool received = changed_.wait_for(lock, std::chrono::seconds(5), [this, expectedType]()
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
            Require(received, "Timed out waiting for an audio capture worker frame.");
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

    class TestCapture final : public SpeechAudioCapture
    {
    public:
        explicit TestCapture(BoundedAudioBlockQueue& destinationQueue)
            : destinationQueue_(destinationQueue)
        {
        }

        WindowsCaptureError Start(const std::wstring&) override
        {
            {
                std::lock_guard<std::mutex> lock(mutex_);
                if (cancelRequested_)
                {
                    state_ = WindowsCaptureState::Cancelled;
                    return WindowsCaptureError::Cancelled;
                }
                state_ = WindowsCaptureState::Capturing;
            }
            std::array<float, 3> samples { 0.0F, 0.25F, -0.5F };
            Require(destinationQueue_.TryPushSamples(samples.data(), samples.size(), false) == clear_dictate::AudioBlockPushResult::Accepted, "The deterministic audio block must enter the queue.");
            audioPublished_ = true;
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
            if (audioPublished_ && !audioReported_)
            {
                audioReported_ = true;
                return WindowsCaptureActivity::AudioAvailable;
            }
            if (state_ == WindowsCaptureState::Stopped || state_ == WindowsCaptureState::Cancelled)
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
            return WindowsCaptureError::None;
        }

        std::uint64_t AcceptedFrameCount() const noexcept override
        {
            return 3;
        }

    private:
        BoundedAudioBlockQueue& destinationQueue_;
        mutable std::mutex mutex_;
        std::condition_variable changed_;
        WindowsCaptureState state_ = WindowsCaptureState::Constructed;
        bool audioPublished_ = false;
        bool audioReported_ = false;
        bool cancelRequested_ = false;
    };

    class TestCaptureFactory final : public SpeechAudioCaptureFactory
    {
    public:
        std::unique_ptr<SpeechAudioCapture> Create(BoundedAudioBlockQueue& destinationQueue) override
        {
            return std::make_unique<TestCapture>(destinationQueue);
        }
    };

    void CompleteHandshake(AudioCaptureWorkerSession& session, CapturedFrames& output)
    {
        session.Handle(WorkerProtocolFrame::Control(WorkerMessageType::Hello, {}));
        output.WaitForType(WorkerMessageType::Ready);
    }

    void TestReleasePublishesAudioThenCompletion()
    {
        TestCaptureFactory captureFactory;
        CapturedFrames output;
        AudioCaptureWorkerSession session(captureFactory, [&output](const WorkerProtocolFrame& frame) { output.Add(frame); });
        CompleteHandshake(session, output);
        const WorkerOperationIdentity identity = TestIdentity(71);
        session.Handle(WorkerProtocolFrame::Operation(WorkerMessageType::StartRecording, identity, clear_dictate::EncodeRecordingStartRequest({ "" })));
        output.WaitForType(WorkerMessageType::RecordingStarted);
        output.WaitForType(WorkerMessageType::AudioChunk);
        session.Handle(WorkerProtocolFrame::Operation(WorkerMessageType::StopRecording, identity, {}));
        output.WaitForType(WorkerMessageType::RecordingComplete);

        const std::vector<WorkerProtocolFrame> frames = output.Snapshot();
        Require(frames.size() == 4, "A completed capture must publish READY, RECORDING_STARTED, AUDIO_CHUNK, and RECORDING_COMPLETE.");
        const std::vector<std::uint8_t> expectedPayload =
        {
            0x43, 0x44, 0x41, 0x55, 0x00, 0x01, 0x00, 0x01,
            0x00, 0x00, 0x3E, 0x80, 0x00, 0x00, 0x00, 0x03,
            0x00, 0x00, 0x00, 0x00, 0x3E, 0x80, 0x00, 0x00, 0xBF, 0x00, 0x00, 0x00
        };
        Require(frames[2].payload == expectedPayload, "The audio capture session must preserve the exact float samples.");
        Require(session.State() == AudioCaptureWorkerSessionState::Idle, "A completed capture must return the worker to Idle.");
    }

    void TestCancellationAcknowledgesAndDiscardsCapture()
    {
        TestCaptureFactory captureFactory;
        CapturedFrames output;
        AudioCaptureWorkerSession session(captureFactory, [&output](const WorkerProtocolFrame& frame) { output.Add(frame); });
        CompleteHandshake(session, output);
        const WorkerOperationIdentity identity = TestIdentity(72);
        session.Handle(WorkerProtocolFrame::Operation(WorkerMessageType::StartRecording, identity, clear_dictate::EncodeRecordingStartRequest({ "" })));
        output.WaitForType(WorkerMessageType::RecordingStarted);
        session.Handle(WorkerProtocolFrame::Operation(WorkerMessageType::Cancel, identity, {}));
        output.WaitForType(WorkerMessageType::OperationCancelled);
        Require(session.State() == AudioCaptureWorkerSessionState::Idle, "Cancellation must return the capture worker to Idle.");
    }
}

int main()
{
    try
    {
        TestReleasePublishesAudioThenCompletion();
        TestCancellationAcknowledgesAndDiscardsCapture();
        std::cout << "All audio capture worker session tests passed." << std::endl;
        return 0;
    }
    catch (const std::exception& exception)
    {
        std::cerr << "Audio capture worker session test failure: " << exception.what() << std::endl;
        return 1;
    }
}
