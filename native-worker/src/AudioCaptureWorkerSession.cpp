#include "clear_dictate/AudioCaptureWorkerSession.h"

#include "clear_dictate/CapturedAudioPayload.h"
#include "clear_dictate/WorkerPayloads.h"

#define NOMINMAX
#include <Windows.h>

#include <array>
#include <chrono>
#include <condition_variable>
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <optional>
#include <string>
#include <thread>
#include <utility>
#include <vector>

namespace clear_dictate
{
    namespace
    {
        constexpr std::size_t AudioQueueBlockCount = 512;
        constexpr std::size_t AudioSamplesPerBlock = 160;
        constexpr std::int32_t SpeechSampleRate = 16000;
        constexpr std::uint32_t CaptureWaitMilliseconds = 100;
        constexpr std::uint32_t CaptureErrorCategoryBase = 100;

        void SecureClear(std::array<float, AudioSamplesPerBlock>& samples) noexcept
        {
            volatile float* writableSamples = samples.data();
            for (std::size_t sampleIndex = 0; sampleIndex < samples.size(); ++sampleIndex)
            {
                writableSamples[sampleIndex] = 0.0F;
            }
        }

        void SecureClear(std::vector<std::uint8_t>& bytes) noexcept
        {
            volatile std::uint8_t* writableBytes = bytes.empty() ? nullptr : bytes.data();
            for (std::size_t byteIndex = 0; byteIndex < bytes.size(); ++byteIndex)
            {
                writableBytes[byteIndex] = 0;
            }
            bytes.clear();
        }

        class SensitiveBytesScrubber final
        {
        public:
            explicit SensitiveBytesScrubber(std::vector<std::uint8_t>& bytes) noexcept
                : bytes_(bytes)
            {
            }

            ~SensitiveBytesScrubber()
            {
                SecureClear(bytes_);
            }

        private:
            std::vector<std::uint8_t>& bytes_;
        };

        std::wstring ConvertUtf8ToWide(const std::string& utf8Text)
        {
            if (utf8Text.empty())
            {
                return {};
            }

            const int requiredCharacterCount = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, utf8Text.data(), static_cast<int>(utf8Text.size()), nullptr, 0);
            if (requiredCharacterCount <= 0)
            {
                throw WorkerPayloadException(WorkerPayloadFailure::InvalidEndpointIdentifier);
            }

            std::wstring wideText(static_cast<std::size_t>(requiredCharacterCount), L'\0');
            const int convertedCharacterCount = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, utf8Text.data(), static_cast<int>(utf8Text.size()), wideText.data(), requiredCharacterCount);
            if (convertedCharacterCount != requiredCharacterCount)
            {
                throw WorkerPayloadException(WorkerPayloadFailure::InvalidEndpointIdentifier);
            }
            return wideText;
        }

        std::vector<std::uint8_t> EncodeFixedError(std::uint32_t category)
        {
            return
            {
                static_cast<std::uint8_t>((category >> 24) & 0xFF),
                static_cast<std::uint8_t>((category >> 16) & 0xFF),
                static_cast<std::uint8_t>((category >> 8) & 0xFF),
                static_cast<std::uint8_t>(category & 0xFF)
            };
        }

        bool IdentitiesMatch(const WorkerOperationIdentity& left, const WorkerOperationIdentity& right) noexcept
        {
            return left.clientSessionIdentifier == right.clientSessionIdentifier &&
                left.operationIdentifier == right.operationIdentifier &&
                left.privacy == right.privacy &&
                left.workerRequestToken == right.workerRequestToken;
        }
    }

    AudioCaptureWorkerSessionException::AudioCaptureWorkerSessionException(AudioCaptureWorkerSessionFailure failure)
        : std::runtime_error("Audio capture worker session failure."),
          failure_(failure)
    {
    }

    AudioCaptureWorkerSessionFailure AudioCaptureWorkerSessionException::Failure() const noexcept
    {
        return failure_;
    }

    class AudioCaptureWorkerSession::Implementation final
    {
    public:
        Implementation(SpeechAudioCaptureFactory& captureFactory, FrameWriter frameWriter, std::function<void()> fatalFailureHandler)
            : captureFactory_(captureFactory),
              frameWriter_(std::move(frameWriter)),
              fatalFailureHandler_(std::move(fatalFailureHandler)),
              audioQueue_(AudioQueueBlockCount, AudioSamplesPerBlock)
        {
            if (!frameWriter_)
            {
                throw std::invalid_argument("An audio capture worker frame writer is required.");
            }
            captureThread_ = std::thread(&Implementation::CaptureLoop, this);
        }

        ~Implementation()
        {
            Close();
        }

        void Handle(const WorkerProtocolFrame& frame)
        {
            std::lock_guard<std::mutex> commandLock(commandMutex_);
            switch (State())
            {
                case AudioCaptureWorkerSessionState::AwaitingHello:
                    HandleHello(frame);
                    return;
                case AudioCaptureWorkerSessionState::Idle:
                    HandleIdle(frame);
                    return;
                case AudioCaptureWorkerSessionState::RecordingStarting:
                case AudioCaptureWorkerSessionState::Recording:
                case AudioCaptureWorkerSessionState::RecordingStopping:
                case AudioCaptureWorkerSessionState::RecordingCancelling:
                    HandleActive(frame);
                    return;
                case AudioCaptureWorkerSessionState::Closed:
                case AudioCaptureWorkerSessionState::Failed:
                    Reject(AudioCaptureWorkerSessionFailure::IllegalState);
            }
        }

        AudioCaptureWorkerSessionState State() const
        {
            std::lock_guard<std::mutex> stateLock(stateMutex_);
            return state_;
        }

        void Close() noexcept
        {
            {
                std::lock_guard<std::mutex> stateLock(stateMutex_);
                if (state_ == AudioCaptureWorkerSessionState::Closed)
                {
                    return;
                }
                closing_ = true;
                if (activeCapture_ != nullptr)
                {
                    activeCapture_->RequestCancel();
                }
            }
            workChanged_.notify_all();
            if (captureThread_.joinable())
            {
                captureThread_.join();
            }
            audioQueue_.DiscardAndScrub();
            std::lock_guard<std::mutex> stateLock(stateMutex_);
            state_ = AudioCaptureWorkerSessionState::Closed;
        }

    private:
        struct PendingRecording final
        {
            WorkerOperationIdentity identity;
            RecordingStartRequest request;
        };

        void HandleHello(const WorkerProtocolFrame& frame)
        {
            if (frame.scope != WorkerFrameScope::Control || frame.type != WorkerMessageType::Hello)
            {
                Reject(AudioCaptureWorkerSessionFailure::IllegalMessage);
            }
            {
                std::lock_guard<std::mutex> stateLock(stateMutex_);
                state_ = AudioCaptureWorkerSessionState::Idle;
            }
            WriteFrame(WorkerProtocolFrame::Control(WorkerMessageType::Ready, {}));
        }

        void HandleIdle(const WorkerProtocolFrame& frame)
        {
            if (frame.scope == WorkerFrameScope::Control && frame.type == WorkerMessageType::Shutdown)
            {
                {
                    std::lock_guard<std::mutex> stateLock(stateMutex_);
                    closing_ = true;
                    state_ = AudioCaptureWorkerSessionState::Closed;
                }
                workChanged_.notify_all();
                return;
            }
            if (frame.scope != WorkerFrameScope::Operation || frame.type != WorkerMessageType::StartRecording)
            {
                Reject(AudioCaptureWorkerSessionFailure::IllegalMessage);
            }

            PendingRecording recording { frame.identity, DecodeRecordingStartRequest(frame.payload) };
            {
                std::lock_guard<std::mutex> stateLock(stateMutex_);
                activeIdentity_ = frame.identity;
                pendingRecording_ = std::move(recording);
                cancellationRequested_ = false;
                state_ = AudioCaptureWorkerSessionState::RecordingStarting;
            }
            workChanged_.notify_all();
        }

        void HandleActive(const WorkerProtocolFrame& frame)
        {
            if (frame.scope != WorkerFrameScope::Operation)
            {
                Reject(AudioCaptureWorkerSessionFailure::IllegalMessage);
            }
            RequireActiveIdentity(frame);

            if (frame.type == WorkerMessageType::StopRecording)
            {
                std::lock_guard<std::mutex> stateLock(stateMutex_);
                if (state_ != AudioCaptureWorkerSessionState::Recording)
                {
                    Reject(AudioCaptureWorkerSessionFailure::IllegalState);
                }
                state_ = AudioCaptureWorkerSessionState::RecordingStopping;
                activeCapture_->RequestStop();
                return;
            }

            if (frame.type == WorkerMessageType::Cancel)
            {
                {
                    std::lock_guard<std::mutex> stateLock(stateMutex_);
                    cancellationRequested_ = true;
                    state_ = AudioCaptureWorkerSessionState::RecordingCancelling;
                    if (activeCapture_ != nullptr)
                    {
                        activeCapture_->RequestCancel();
                    }
                }
                WriteFrame(WorkerProtocolFrame::Operation(WorkerMessageType::CancellationAcknowledged, frame.identity, {}));
                return;
            }
            Reject(AudioCaptureWorkerSessionFailure::IllegalMessage);
        }

        void CaptureLoop() noexcept
        {
            try
            {
                while (true)
                {
                    std::optional<PendingRecording> recording;
                    {
                        std::unique_lock<std::mutex> stateLock(stateMutex_);
                        workChanged_.wait(stateLock, [this]() { return closing_ || pendingRecording_.has_value(); });
                        if (closing_)
                        {
                            return;
                        }
                        recording = std::move(pendingRecording_);
                        pendingRecording_.reset();
                    }
                    CaptureRecording(std::move(*recording));
                }
            }
            catch (...)
            {
                FailUnexpectedly();
            }
        }

        void CaptureRecording(PendingRecording recording)
        {
            std::unique_ptr<SpeechAudioCapture> capture = captureFactory_.Create(audioQueue_);
            if (!capture)
            {
                throw std::runtime_error("The audio capture factory returned no capture.");
            }
            {
                std::lock_guard<std::mutex> stateLock(stateMutex_);
                activeCapture_ = capture.get();
                if (cancellationRequested_ || closing_)
                {
                    capture->RequestCancel();
                }
            }

            const WindowsCaptureError startError = capture->Start(ConvertUtf8ToWide(recording.request.utf8EndpointIdentifier));
            if (startError != WindowsCaptureError::None)
            {
                capture->JoinProducer();
                ClearActiveCapture(capture.get());
                audioQueue_.DiscardAndScrub();
                if (CancellationOrCloseRequested() || startError == WindowsCaptureError::Cancelled)
                {
                    PublishCancelled(recording.identity);
                }
                else
                {
                    PublishCaptureError(recording.identity, startError);
                }
                return;
            }

            PublishRecordingStarted(recording.identity);
            ConsumeRecording(*capture, recording.identity);
        }

        void ConsumeRecording(SpeechAudioCapture& capture, const WorkerOperationIdentity& identity)
        {
            std::array<float, AudioSamplesPerBlock> consumerBuffer {};
            bool captureWaitFailed = false;

            while (true)
            {
                const WindowsCaptureActivity activity = capture.WaitForActivity(CaptureWaitMilliseconds);
                if (activity == WindowsCaptureActivity::WaitFailed)
                {
                    captureWaitFailed = true;
                    capture.RequestCancel();
                    break;
                }

                PublishQueuedAudio(identity, consumerBuffer);
                if (activity == WindowsCaptureActivity::Terminal)
                {
                    break;
                }
            }

            capture.JoinProducer();
            ClearActiveCapture(&capture);
            if (CancellationOrCloseRequested())
            {
                SecureClear(consumerBuffer);
                audioQueue_.DiscardAndScrub();
                PublishCancelled(identity);
                return;
            }
            if (captureWaitFailed || capture.State() == WindowsCaptureState::Failed || capture.Error() != WindowsCaptureError::None)
            {
                const WindowsCaptureError captureError = captureWaitFailed ? WindowsCaptureError::CaptureFailed : capture.Error();
                SecureClear(consumerBuffer);
                audioQueue_.DiscardAndScrub();
                PublishCaptureError(identity, captureError);
                return;
            }

            PublishQueuedAudio(identity, consumerBuffer);
            SecureClear(consumerBuffer);
            audioQueue_.DiscardAndScrub();
            PublishTerminal(WorkerProtocolFrame::Operation(WorkerMessageType::RecordingComplete, identity, {}));
        }

        void PublishQueuedAudio(const WorkerOperationIdentity& identity, std::array<float, AudioSamplesPerBlock>& consumerBuffer)
        {
            std::size_t copiedSampleCount = 0;
            while (audioQueue_.TryPop(consumerBuffer.data(), consumerBuffer.size(), copiedSampleCount))
            {
                WorkerProtocolFrame audioFrame = WorkerProtocolFrame::Operation(
                    WorkerMessageType::AudioChunk,
                    identity,
                    EncodeCapturedAudioChunk(consumerBuffer.data(), copiedSampleCount, SpeechSampleRate, WorkerProtocolCodec::AbsoluteMaximumPayloadBytes));
                SensitiveBytesScrubber payloadScrubber(audioFrame.payload);
                PublishNonTerminal(audioFrame);
                SecureClear(consumerBuffer);
            }
        }

        void PublishRecordingStarted(const WorkerOperationIdentity& identity)
        {
            std::lock_guard<std::mutex> commandLock(commandMutex_);
            {
                std::lock_guard<std::mutex> stateLock(stateMutex_);
                if (cancellationRequested_ || closing_)
                {
                    return;
                }
                if (state_ != AudioCaptureWorkerSessionState::RecordingStarting || !activeIdentity_ || !IdentitiesMatch(*activeIdentity_, identity))
                {
                    Reject(AudioCaptureWorkerSessionFailure::IllegalState);
                }
                state_ = AudioCaptureWorkerSessionState::Recording;
            }
            WriteFrame(WorkerProtocolFrame::Operation(WorkerMessageType::RecordingStarted, identity, {}));
        }

        void PublishNonTerminal(const WorkerProtocolFrame& frame)
        {
            std::lock_guard<std::mutex> commandLock(commandMutex_);
            {
                std::lock_guard<std::mutex> stateLock(stateMutex_);
                if (cancellationRequested_ || closing_ || !activeIdentity_ || !IdentitiesMatch(*activeIdentity_, frame.identity))
                {
                    return;
                }
            }
            WriteFrame(frame);
        }

        void PublishCancelled(const WorkerOperationIdentity& identity)
        {
            if (!IsClosing())
            {
                PublishTerminal(WorkerProtocolFrame::Operation(WorkerMessageType::OperationCancelled, identity, {}));
            }
        }

        void PublishCaptureError(const WorkerOperationIdentity& identity, WindowsCaptureError captureError)
        {
            WorkerProtocolFrame errorFrame = WorkerProtocolFrame::Operation(
                WorkerMessageType::Error,
                identity,
                EncodeFixedError(CaptureErrorCategoryBase + static_cast<std::uint32_t>(captureError)));
            SensitiveBytesScrubber payloadScrubber(errorFrame.payload);
            PublishTerminal(errorFrame);
        }

        void PublishTerminal(const WorkerProtocolFrame& frame)
        {
            std::lock_guard<std::mutex> commandLock(commandMutex_);
            {
                std::lock_guard<std::mutex> stateLock(stateMutex_);
                if (closing_)
                {
                    return;
                }
                if (!activeIdentity_ || !IdentitiesMatch(*activeIdentity_, frame.identity))
                {
                    Reject(AudioCaptureWorkerSessionFailure::IllegalState);
                }
            }
            WriteFrame(frame);
            {
                std::lock_guard<std::mutex> stateLock(stateMutex_);
                activeIdentity_.reset();
                cancellationRequested_ = false;
                state_ = AudioCaptureWorkerSessionState::Idle;
            }
        }

        void RequireActiveIdentity(const WorkerProtocolFrame& frame)
        {
            std::lock_guard<std::mutex> stateLock(stateMutex_);
            if (!activeIdentity_ || !IdentitiesMatch(*activeIdentity_, frame.identity))
            {
                Reject(AudioCaptureWorkerSessionFailure::OperationIdentityMismatch);
            }
        }

        void ClearActiveCapture(SpeechAudioCapture* expectedCapture) noexcept
        {
            std::lock_guard<std::mutex> stateLock(stateMutex_);
            if (activeCapture_ == expectedCapture)
            {
                activeCapture_ = nullptr;
            }
        }

        bool CancellationOrCloseRequested() const noexcept
        {
            std::lock_guard<std::mutex> stateLock(stateMutex_);
            return cancellationRequested_ || closing_;
        }

        bool IsClosing() const noexcept
        {
            std::lock_guard<std::mutex> stateLock(stateMutex_);
            return closing_;
        }

        void FailUnexpectedly() noexcept
        {
            {
                std::lock_guard<std::mutex> stateLock(stateMutex_);
                state_ = AudioCaptureWorkerSessionState::Failed;
                closing_ = true;
                if (activeCapture_ != nullptr)
                {
                    activeCapture_->RequestCancel();
                }
            }
            if (fatalFailureHandler_)
            {
                fatalFailureHandler_();
            }
        }

        void WriteFrame(const WorkerProtocolFrame& frame)
        {
            frameWriter_(frame);
        }

        [[noreturn]] void Reject(AudioCaptureWorkerSessionFailure failure)
        {
            throw AudioCaptureWorkerSessionException(failure);
        }

        SpeechAudioCaptureFactory& captureFactory_;
        FrameWriter frameWriter_;
        std::function<void()> fatalFailureHandler_;
        BoundedAudioBlockQueue audioQueue_;
        std::mutex commandMutex_;
        mutable std::mutex stateMutex_;
        std::condition_variable workChanged_;
        AudioCaptureWorkerSessionState state_ = AudioCaptureWorkerSessionState::AwaitingHello;
        std::optional<PendingRecording> pendingRecording_;
        std::optional<WorkerOperationIdentity> activeIdentity_;
        SpeechAudioCapture* activeCapture_ = nullptr;
        bool cancellationRequested_ = false;
        bool closing_ = false;
        std::thread captureThread_;
    };

    AudioCaptureWorkerSession::AudioCaptureWorkerSession(SpeechAudioCaptureFactory& captureFactory, FrameWriter frameWriter, std::function<void()> fatalFailureHandler)
        : implementation_(std::make_unique<Implementation>(captureFactory, std::move(frameWriter), std::move(fatalFailureHandler)))
    {
    }

    AudioCaptureWorkerSession::~AudioCaptureWorkerSession() = default;

    void AudioCaptureWorkerSession::Handle(const WorkerProtocolFrame& frame)
    {
        implementation_->Handle(frame);
    }

    AudioCaptureWorkerSessionState AudioCaptureWorkerSession::State() const
    {
        return implementation_->State();
    }

    void AudioCaptureWorkerSession::Close() noexcept
    {
        implementation_->Close();
    }
}
