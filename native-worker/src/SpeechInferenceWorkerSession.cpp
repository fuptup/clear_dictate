#include "clear_dictate/SpeechInferenceWorkerSession.h"

#define NOMINMAX
#include <Windows.h>

#include "clear_dictate/WorkerPayloads.h"
#include "clear_dictate/WorkerTranscriptPayloads.h"

#include <array>
#include <chrono>
#include <condition_variable>
#include <cstddef>
#include <cstdint>
#include <exception>
#include <mutex>
#include <new>
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
        constexpr std::chrono::milliseconds PartialTranscriptInterval(250);
        constexpr std::uint32_t CaptureWaitMilliseconds = 100;
        constexpr std::uint32_t ModelLoadFailedErrorCategory = 1;
        constexpr std::uint32_t RecognitionFailedErrorCategory = 5;
        constexpr std::uint32_t CaptureErrorCategoryBase = 100;

        void SecureClear(std::array<float, AudioSamplesPerBlock>& samples) noexcept
        {
            volatile float* writableSamples = samples.data();
            for (std::size_t sampleIndex = 0; sampleIndex < samples.size(); ++sampleIndex)
            {
                writableSamples[sampleIndex] = 0.0F;
            }
        }

        void SecureClear(std::string& sensitiveText) noexcept
        {
            volatile char* writableText = sensitiveText.empty() ? nullptr : sensitiveText.data();
            for (std::size_t characterIndex = 0; characterIndex < sensitiveText.size(); ++characterIndex)
            {
                writableText[characterIndex] = '\0';
            }
            sensitiveText.clear();
        }

        void SecureClear(std::vector<std::uint8_t>& sensitiveBytes) noexcept
        {
            volatile std::uint8_t* writableBytes = sensitiveBytes.empty() ? nullptr : sensitiveBytes.data();
            for (std::size_t byteIndex = 0; byteIndex < sensitiveBytes.size(); ++byteIndex)
            {
                writableBytes[byteIndex] = 0;
            }
            sensitiveBytes.clear();
        }

        class SensitiveTextScrubber final
        {
        public:
            explicit SensitiveTextScrubber(std::string& sensitiveText) noexcept
                : sensitiveText_(sensitiveText)
            {
            }

            ~SensitiveTextScrubber()
            {
                SecureClear(sensitiveText_);
            }

        private:
            std::string& sensitiveText_;
        };

        class SensitiveBytesScrubber final
        {
        public:
            explicit SensitiveBytesScrubber(std::vector<std::uint8_t>& sensitiveBytes) noexcept
                : sensitiveBytes_(sensitiveBytes)
            {
            }

            ~SensitiveBytesScrubber()
            {
                SecureClear(sensitiveBytes_);
            }

        private:
            std::vector<std::uint8_t>& sensitiveBytes_;
        };

        std::wstring ConvertUtf8ToWide(const std::string& utf8Text)
        {
            if (utf8Text.empty())
            {
                return {};
            }

            const int requiredCharacterCount = MultiByteToWideChar(
                CP_UTF8,
                MB_ERR_INVALID_CHARS,
                utf8Text.data(),
                static_cast<int>(utf8Text.size()),
                nullptr,
                0);
            if (requiredCharacterCount <= 0)
            {
                throw WorkerPayloadException(WorkerPayloadFailure::InvalidEndpointIdentifier);
            }

            std::wstring wideText(static_cast<std::size_t>(requiredCharacterCount), L'\0');
            const int convertedCharacterCount = MultiByteToWideChar(
                CP_UTF8,
                MB_ERR_INVALID_CHARS,
                utf8Text.data(),
                static_cast<int>(utf8Text.size()),
                wideText.data(),
                requiredCharacterCount);
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

    SpeechWorkerSessionException::SpeechWorkerSessionException(SpeechWorkerSessionFailure failure)
        : std::runtime_error("Speech worker session failure."),
          failure_(failure)
    {
    }

    SpeechWorkerSessionFailure SpeechWorkerSessionException::Failure() const noexcept
    {
        return failure_;
    }

    class SpeechInferenceWorkerSession::Implementation final
    {
    public:
        Implementation(
            SpeechRecognitionBackendLoader& backendLoader,
            SpeechAudioCaptureFactory& captureFactory,
            FrameWriter frameWriter,
            std::function<void()> fatalFailureHandler)
            : backendLoader_(backendLoader),
              captureFactory_(captureFactory),
              frameWriter_(std::move(frameWriter)),
              fatalFailureHandler_(std::move(fatalFailureHandler)),
              audioQueue_(AudioQueueBlockCount, AudioSamplesPerBlock)
        {
            if (!frameWriter_)
            {
                throw std::invalid_argument("A speech worker frame writer is required.");
            }

            recognitionThread_ = std::thread(&Implementation::RecognitionLoop, this);
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
                case SpeechWorkerSessionState::AwaitingHello:
                    HandleHello(frame);
                    return;

                case SpeechWorkerSessionState::AwaitingModelLoad:
                    HandleModelLoad(frame);
                    return;

                case SpeechWorkerSessionState::Idle:
                    HandleIdleCommand(frame);
                    return;

                case SpeechWorkerSessionState::RecordingStarting:
                case SpeechWorkerSessionState::Recording:
                case SpeechWorkerSessionState::RecordingStopping:
                case SpeechWorkerSessionState::RecordingCancelling:
                    HandleActiveCommand(frame);
                    return;

                case SpeechWorkerSessionState::LoadingModel:
                case SpeechWorkerSessionState::Closed:
                case SpeechWorkerSessionState::Failed:
                    Reject(SpeechWorkerSessionFailure::IllegalState);
            }
        }

        SpeechWorkerSessionState State() const
        {
            std::lock_guard<std::mutex> lock(stateMutex_);
            return state_;
        }

        void Close() noexcept
        {
            {
                std::lock_guard<std::mutex> commandLock(commandMutex_);
                RequestClose();
            }

            if (recognitionThread_.joinable())
            {
                recognitionThread_.join();
            }

            std::lock_guard<std::mutex> lock(stateMutex_);
            if (state_ != SpeechWorkerSessionState::Failed)
            {
                state_ = SpeechWorkerSessionState::Closed;
            }
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
                Reject(SpeechWorkerSessionFailure::IllegalMessage);
            }

            {
                std::lock_guard<std::mutex> lock(stateMutex_);
                state_ = SpeechWorkerSessionState::AwaitingModelLoad;
            }
            WriteFrame(WorkerProtocolFrame::Control(WorkerMessageType::Ready, {}));
        }

        void HandleModelLoad(const WorkerProtocolFrame& frame)
        {
            if (frame.scope != WorkerFrameScope::Control || frame.type != WorkerMessageType::LoadModels)
            {
                Reject(SpeechWorkerSessionFailure::IllegalMessage);
            }

            SpeechModelLoadRequest request = DecodeSpeechModelLoadRequest(frame.payload);
            {
                std::lock_guard<std::mutex> lock(stateMutex_);
                if (state_ != SpeechWorkerSessionState::AwaitingModelLoad)
                {
                    Reject(SpeechWorkerSessionFailure::IllegalState);
                }

                pendingModelLoad_ = std::move(request);
                state_ = SpeechWorkerSessionState::LoadingModel;
            }
            workChanged_.notify_one();
        }

        void HandleIdleCommand(const WorkerProtocolFrame& frame)
        {
            if (frame.scope == WorkerFrameScope::Operation &&
                frame.type == WorkerMessageType::Cancel &&
                MatchesLastTerminalIdentity(frame))
            {
                return;
            }

            if (frame.scope == WorkerFrameScope::Control && frame.type == WorkerMessageType::Shutdown)
            {
                RequestClose();
                return;
            }

            if (frame.scope != WorkerFrameScope::Operation || frame.type != WorkerMessageType::StartRecording)
            {
                Reject(SpeechWorkerSessionFailure::IllegalMessage);
            }

            RecordingStartRequest request = DecodeRecordingStartRequest(frame.payload);
            {
                std::lock_guard<std::mutex> lock(stateMutex_);
                if (state_ != SpeechWorkerSessionState::Idle || pendingRecording_)
                {
                    Reject(SpeechWorkerSessionFailure::IllegalState);
                }

                activeIdentity_ = frame.identity;
                pendingRecording_ = PendingRecording { frame.identity, std::move(request) };
                cancellationRequested_ = false;
                cancellationAcknowledged_ = false;
                state_ = SpeechWorkerSessionState::RecordingStarting;
            }
            workChanged_.notify_one();
        }

        void HandleActiveCommand(const WorkerProtocolFrame& frame)
        {
            if (frame.scope != WorkerFrameScope::Operation)
            {
                Reject(SpeechWorkerSessionFailure::IllegalMessage);
            }

            RequireActiveIdentity(frame);
            if (frame.type == WorkerMessageType::StopRecording)
            {
                RequestStop();
                return;
            }
            if (frame.type == WorkerMessageType::Cancel)
            {
                RequestCancellation(frame);
                return;
            }

            Reject(SpeechWorkerSessionFailure::IllegalMessage);
        }

        void RequestStop()
        {
            std::lock_guard<std::mutex> lock(stateMutex_);
            if (state_ != SpeechWorkerSessionState::Recording)
            {
                Reject(SpeechWorkerSessionFailure::IllegalState);
            }

            state_ = SpeechWorkerSessionState::RecordingStopping;
            if (activeCapture_ != nullptr)
            {
                activeCapture_->RequestStop();
            }
        }

        void RequestCancellation(const WorkerProtocolFrame& frame)
        {
            {
                std::lock_guard<std::mutex> lock(stateMutex_);
                if (state_ != SpeechWorkerSessionState::RecordingStarting &&
                    state_ != SpeechWorkerSessionState::Recording &&
                    state_ != SpeechWorkerSessionState::RecordingStopping &&
                    state_ != SpeechWorkerSessionState::RecordingCancelling)
                {
                    Reject(SpeechWorkerSessionFailure::IllegalState);
                }

                if (cancellationAcknowledged_)
                {
                    return;
                }

                cancellationRequested_ = true;
                cancellationAcknowledged_ = true;
                state_ = SpeechWorkerSessionState::RecordingCancelling;
                if (activeCapture_ != nullptr)
                {
                    activeCapture_->RequestCancel();
                }
            }

            WriteFrame(WorkerProtocolFrame::Operation(
                WorkerMessageType::CancellationAcknowledged,
                frame.identity,
                {}));
        }

        void RequestClose() noexcept
        {
            {
                std::lock_guard<std::mutex> lock(stateMutex_);
                if (closing_)
                {
                    return;
                }

                closing_ = true;
                cancellationRequested_ = true;
                if (state_ != SpeechWorkerSessionState::Failed)
                {
                    state_ = SpeechWorkerSessionState::Closed;
                }
                if (activeCapture_ != nullptr)
                {
                    activeCapture_->RequestCancel();
                }
            }
            workChanged_.notify_all();
        }

        void RecognitionLoop() noexcept
        {
            std::unique_ptr<SpeechRecognitionBackend> backend;
            try
            {
                while (true)
                {
                    std::optional<SpeechModelLoadRequest> modelLoadRequest;
                    std::optional<PendingRecording> recording;
                    {
                        std::unique_lock<std::mutex> lock(stateMutex_);
                        workChanged_.wait(
                            lock,
                            [this]()
                            {
                                return closing_ || pendingModelLoad_.has_value() || pendingRecording_.has_value();
                            });

                        if (closing_)
                        {
                            backend.reset();
                            return;
                        }

                        if (pendingModelLoad_)
                        {
                            modelLoadRequest = std::move(pendingModelLoad_);
                            pendingModelLoad_.reset();
                        }
                        else
                        {
                            recording = std::move(pendingRecording_);
                            pendingRecording_.reset();
                        }
                    }

                    if (modelLoadRequest)
                    {
                        backend = LoadBackend(*modelLoadRequest);
                        if (!backend)
                        {
                            return;
                        }
                        PublishModelsLoaded();
                        continue;
                    }

                    if (!recording || !backend)
                    {
                        FailUnexpectedly();
                        return;
                    }

                    RunRecording(*backend, std::move(*recording));
                }
            }
            catch (const std::bad_alloc&)
            {
                FailUnexpectedly();
            }
            catch (...)
            {
                FailUnexpectedly();
            }
        }

        std::unique_ptr<SpeechRecognitionBackend> LoadBackend(const SpeechModelLoadRequest& request)
        {
            try
            {
                std::unique_ptr<SpeechRecognitionBackend> backend = backendLoader_.Load(request);
                if (!backend)
                {
                    throw std::runtime_error("The speech model loader returned no backend.");
                }
                return backend;
            }
            catch (const std::bad_alloc&)
            {
                throw;
            }
            catch (...)
            {
                {
                    std::lock_guard<std::mutex> commandLock(commandMutex_);
                    WriteFrame(WorkerProtocolFrame::Control(
                        WorkerMessageType::ControlError,
                        EncodeFixedError(ModelLoadFailedErrorCategory)));
                }

                std::lock_guard<std::mutex> lock(stateMutex_);
                state_ = SpeechWorkerSessionState::Failed;
                return nullptr;
            }
        }

        void PublishModelsLoaded()
        {
            std::lock_guard<std::mutex> commandLock(commandMutex_);
            {
                std::lock_guard<std::mutex> lock(stateMutex_);
                if (closing_)
                {
                    return;
                }
                if (state_ != SpeechWorkerSessionState::LoadingModel)
                {
                    Reject(SpeechWorkerSessionFailure::IllegalState);
                }
                state_ = SpeechWorkerSessionState::Idle;
            }
            WriteFrame(WorkerProtocolFrame::Control(WorkerMessageType::ModelsLoaded, {}));
        }

        void RunRecording(SpeechRecognitionBackend& backend, PendingRecording recording)
        {
            bool streamStarted = false;
            std::unique_ptr<SpeechAudioCapture> capture;

            try
            {
                backend.Start();
                streamStarted = true;

                if (CancellationOrCloseRequested())
                {
                    backend.CancelAndDiscard();
                    streamStarted = false;
                    audioQueue_.DiscardAndScrub();
                    PublishCancelled(recording.identity);
                    return;
                }

                capture = captureFactory_.Create(audioQueue_);
                if (!capture)
                {
                    throw std::runtime_error("The speech capture factory returned no capture.");
                }

                {
                    std::lock_guard<std::mutex> lock(stateMutex_);
                    activeCapture_ = capture.get();
                    if (cancellationRequested_ || closing_)
                    {
                        capture->RequestCancel();
                    }
                }

                const std::wstring endpointIdentifier = ConvertUtf8ToWide(recording.request.utf8EndpointIdentifier);
                const WindowsCaptureError startError = capture->Start(endpointIdentifier);
                if (startError != WindowsCaptureError::None)
                {
                    capture->JoinProducer();
                    ClearActiveCapture(capture.get());
                    backend.CancelAndDiscard();
                    streamStarted = false;
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
                ConsumeRecording(backend, *capture, recording.identity);
                streamStarted = false;
                capture.reset();
            }
            catch (...)
            {
                if (capture)
                {
                    capture->RequestCancel();
                    capture->JoinProducer();
                    ClearActiveCapture(capture.get());
                }
                audioQueue_.DiscardAndScrub();
                if (streamStarted)
                {
                    try
                    {
                        backend.CancelAndDiscard();
                    }
                    catch (...)
                    {
                    }
                }
                throw;
            }
        }

        void ConsumeRecording(
            SpeechRecognitionBackend& backend,
            SpeechAudioCapture& capture,
            const WorkerOperationIdentity& identity)
        {
            std::array<float, AudioSamplesPerBlock> consumerBuffer {};
            auto nextPartialTranscriptTime = std::chrono::steady_clock::now();
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

                const std::uint64_t drainedSampleCount = DrainQueuedAudio(backend, consumerBuffer);
                const auto now = std::chrono::steady_clock::now();
                if (drainedSampleCount != 0 && now >= nextPartialTranscriptTime)
                {
                    PublishPartialTranscripts(backend, identity);
                    nextPartialTranscriptTime = now + PartialTranscriptInterval;
                }

                if (activity == WindowsCaptureActivity::Terminal)
                {
                    break;
                }
            }

            capture.JoinProducer();
            ClearActiveCapture(&capture);
            const bool cancellationRequested = CancellationOrCloseRequested();
            if (cancellationRequested)
            {
                SecureClear(consumerBuffer);
                audioQueue_.DiscardAndScrub();
                backend.CancelAndDiscard();
                PublishCancelled(identity);
                return;
            }

            if (captureWaitFailed ||
                capture.State() == WindowsCaptureState::Failed ||
                capture.Error() != WindowsCaptureError::None)
            {
                const WindowsCaptureError captureError =
                    captureWaitFailed ? WindowsCaptureError::CaptureFailed : capture.Error();
                SecureClear(consumerBuffer);
                audioQueue_.DiscardAndScrub();
                backend.CancelAndDiscard();
                PublishCaptureError(identity, captureError);
                return;
            }

            static_cast<void>(DrainQueuedAudio(backend, consumerBuffer));
            SecureClear(consumerBuffer);
            std::string finalTranscript = backend.StopAndFinish();
            SensitiveTextScrubber finalTranscriptScrubber(finalTranscript);
            audioQueue_.DiscardAndScrub();
            if (finalTranscript.size() > WorkerProtocolCodec::AbsoluteMaximumPayloadBytes)
            {
                PublishRecognitionError(identity);
                return;
            }

            WorkerProtocolFrame terminalFrame = WorkerProtocolFrame::Operation(
                WorkerMessageType::FinalTranscript,
                identity,
                { finalTranscript.begin(), finalTranscript.end() });
            SensitiveBytesScrubber payloadScrubber(terminalFrame.payload);
            PublishTerminal(terminalFrame);
        }

        std::uint64_t DrainQueuedAudio(
            SpeechRecognitionBackend& backend,
            std::array<float, AudioSamplesPerBlock>& consumerBuffer)
        {
            std::uint64_t drainedSampleCount = 0;
            std::size_t copiedSampleCount = 0;
            while (audioQueue_.TryPop(consumerBuffer.data(), consumerBuffer.size(), copiedSampleCount))
            {
                try
                {
                    backend.AddAudio(consumerBuffer.data(), copiedSampleCount, SpeechSampleRate);
                }
                catch (...)
                {
                    SecureClear(consumerBuffer);
                    throw;
                }

                drainedSampleCount += copiedSampleCount;
                SecureClear(consumerBuffer);
            }
            return drainedSampleCount;
        }

        void PublishPartialTranscripts(
            SpeechRecognitionBackend& backend,
            const WorkerOperationIdentity& identity)
        {
            std::vector<TranscriptDelta> deltas = backend.TranscribeChangedLines(false);
            for (TranscriptDelta& delta : deltas)
            {
                SensitiveTextScrubber textScrubber(delta.text);
                std::vector<std::uint8_t> payload = EncodeTranscriptDelta(delta);
                WorkerProtocolFrame partialFrame = WorkerProtocolFrame::Operation(
                    WorkerMessageType::PartialTranscript,
                    identity,
                    std::move(payload));
                SensitiveBytesScrubber payloadScrubber(partialFrame.payload);
                PublishNonTerminal(partialFrame);
            }
        }

        void PublishRecordingStarted(const WorkerOperationIdentity& identity)
        {
            std::lock_guard<std::mutex> commandLock(commandMutex_);
            {
                std::lock_guard<std::mutex> lock(stateMutex_);
                if (cancellationRequested_ || closing_)
                {
                    return;
                }
                if (state_ != SpeechWorkerSessionState::RecordingStarting ||
                    !activeIdentity_ ||
                    !IdentitiesMatch(*activeIdentity_, identity))
                {
                    Reject(SpeechWorkerSessionFailure::IllegalState);
                }
                state_ = SpeechWorkerSessionState::Recording;
            }
            WriteFrame(WorkerProtocolFrame::Operation(WorkerMessageType::RecordingStarted, identity, {}));
        }

        void PublishNonTerminal(const WorkerProtocolFrame& frame)
        {
            std::lock_guard<std::mutex> commandLock(commandMutex_);
            {
                std::lock_guard<std::mutex> lock(stateMutex_);
                if (cancellationRequested_ || closing_)
                {
                    return;
                }
                if ((state_ != SpeechWorkerSessionState::Recording &&
                     state_ != SpeechWorkerSessionState::RecordingStopping) ||
                    !activeIdentity_ ||
                    !IdentitiesMatch(*activeIdentity_, frame.identity))
                {
                    return;
                }
            }
            WriteFrame(frame);
        }

        void PublishCancelled(const WorkerOperationIdentity& identity)
        {
            if (IsClosing())
            {
                return;
            }

            PublishTerminal(WorkerProtocolFrame::Operation(
                WorkerMessageType::OperationCancelled,
                identity,
                {}));
        }

        void PublishCaptureError(
            const WorkerOperationIdentity& identity,
            WindowsCaptureError captureError)
        {
            const std::uint32_t category =
                CaptureErrorCategoryBase + static_cast<std::uint32_t>(captureError);
            WorkerProtocolFrame errorFrame = WorkerProtocolFrame::Operation(
                WorkerMessageType::Error,
                identity,
                EncodeFixedError(category));
            SensitiveBytesScrubber payloadScrubber(errorFrame.payload);
            PublishTerminal(errorFrame);
        }

        void PublishRecognitionError(const WorkerOperationIdentity& identity)
        {
            WorkerProtocolFrame errorFrame = WorkerProtocolFrame::Operation(
                WorkerMessageType::Error,
                identity,
                EncodeFixedError(RecognitionFailedErrorCategory));
            SensitiveBytesScrubber payloadScrubber(errorFrame.payload);
            PublishTerminal(errorFrame);
        }

        void PublishTerminal(const WorkerProtocolFrame& frame)
        {
            std::lock_guard<std::mutex> commandLock(commandMutex_);
            {
                std::lock_guard<std::mutex> lock(stateMutex_);
                if (closing_)
                {
                    return;
                }
                if (!activeIdentity_ || !IdentitiesMatch(*activeIdentity_, frame.identity))
                {
                    Reject(SpeechWorkerSessionFailure::IllegalState);
                }
            }

            WriteFrame(frame);

            {
                std::lock_guard<std::mutex> lock(stateMutex_);
                lastTerminalIdentity_ = frame.identity;
                activeIdentity_.reset();
                cancellationRequested_ = false;
                cancellationAcknowledged_ = false;
                state_ = SpeechWorkerSessionState::Idle;
            }
        }

        void ClearActiveCapture(SpeechAudioCapture* expectedCapture) noexcept
        {
            std::lock_guard<std::mutex> lock(stateMutex_);
            if (activeCapture_ == expectedCapture)
            {
                activeCapture_ = nullptr;
            }
        }

        bool CancellationOrCloseRequested() const noexcept
        {
            std::lock_guard<std::mutex> lock(stateMutex_);
            return cancellationRequested_ || closing_;
        }

        bool IsClosing() const noexcept
        {
            std::lock_guard<std::mutex> lock(stateMutex_);
            return closing_;
        }

        void RequireActiveIdentity(const WorkerProtocolFrame& frame)
        {
            std::lock_guard<std::mutex> lock(stateMutex_);
            if (!activeIdentity_)
            {
                Reject(SpeechWorkerSessionFailure::IllegalState);
            }
            if (!IdentitiesMatch(*activeIdentity_, frame.identity))
            {
                Reject(SpeechWorkerSessionFailure::OperationIdentityMismatch);
            }
        }

        bool MatchesLastTerminalIdentity(const WorkerProtocolFrame& frame) const noexcept
        {
            std::lock_guard<std::mutex> lock(stateMutex_);
            return lastTerminalIdentity_.has_value() &&
                IdentitiesMatch(*lastTerminalIdentity_, frame.identity);
        }

        void FailUnexpectedly() noexcept
        {
            {
                std::lock_guard<std::mutex> lock(stateMutex_);
                state_ = SpeechWorkerSessionState::Failed;
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

        [[noreturn]] void Reject(SpeechWorkerSessionFailure failure)
        {
            throw SpeechWorkerSessionException(failure);
        }

        SpeechRecognitionBackendLoader& backendLoader_;
        SpeechAudioCaptureFactory& captureFactory_;
        FrameWriter frameWriter_;
        std::function<void()> fatalFailureHandler_;
        BoundedAudioBlockQueue audioQueue_;
        std::mutex commandMutex_;
        mutable std::mutex stateMutex_;
        std::condition_variable workChanged_;
        SpeechWorkerSessionState state_ = SpeechWorkerSessionState::AwaitingHello;
        std::optional<SpeechModelLoadRequest> pendingModelLoad_;
        std::optional<PendingRecording> pendingRecording_;
        std::optional<WorkerOperationIdentity> activeIdentity_;
        std::optional<WorkerOperationIdentity> lastTerminalIdentity_;
        SpeechAudioCapture* activeCapture_ = nullptr;
        bool cancellationRequested_ = false;
        bool cancellationAcknowledged_ = false;
        bool closing_ = false;
        std::thread recognitionThread_;
    };

    SpeechInferenceWorkerSession::SpeechInferenceWorkerSession(
        SpeechRecognitionBackendLoader& backendLoader,
        SpeechAudioCaptureFactory& captureFactory,
        FrameWriter frameWriter,
        std::function<void()> fatalFailureHandler)
        : implementation_(std::make_unique<Implementation>(
            backendLoader,
            captureFactory,
            std::move(frameWriter),
            std::move(fatalFailureHandler)))
    {
    }

    SpeechInferenceWorkerSession::~SpeechInferenceWorkerSession() = default;

    void SpeechInferenceWorkerSession::Handle(const WorkerProtocolFrame& frame)
    {
        implementation_->Handle(frame);
    }

    SpeechWorkerSessionState SpeechInferenceWorkerSession::State() const
    {
        return implementation_->State();
    }

    void SpeechInferenceWorkerSession::Close() noexcept
    {
        implementation_->Close();
    }
}
