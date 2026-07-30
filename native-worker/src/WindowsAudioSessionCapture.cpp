#include "clear_dictate/WindowsAudioSessionCapture.h"

#include "clear_dictate/AudioCapturePacketProcessor.h"

#define NOMINMAX
#include <Windows.h>
#include <audioclient.h>
#include <avrt.h>
#include <mmdeviceapi.h>
#include <wrl/client.h>

#include <atomic>
#include <algorithm>
#include <cmath>
#include <condition_variable>
#include <cstdint>
#include <mutex>
#include <stdexcept>
#include <thread>
#include <vector>

namespace clear_dictate
{
    namespace
    {
        using Microsoft::WRL::ComPtr;

        constexpr DWORD CaptureStallTimeoutMilliseconds = 3000;
        constexpr DWORD CaptureStreamFlags =
            AUDCLNT_STREAMFLAGS_EVENTCALLBACK |
            AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM |
            AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY |
            AUDCLNT_STREAMFLAGS_NOPERSIST;

        enum class ShutdownRequest
        {
            None,
            Stop,
            Cancel
        };

        WindowsCaptureError MapInitializationFailure(HRESULT result) noexcept
        {
            if (result == E_ACCESSDENIED)
            {
                return WindowsCaptureError::PrivacyBlocked;
            }
            if (result == AUDCLNT_E_DEVICE_IN_USE)
            {
                return WindowsCaptureError::DeviceBusy;
            }
            if (result == AUDCLNT_E_UNSUPPORTED_FORMAT)
            {
                return WindowsCaptureError::UnsupportedFormat;
            }
            if (result == AUDCLNT_E_SERVICE_NOT_RUNNING)
            {
                return WindowsCaptureError::AudioServiceUnavailable;
            }
            if (result == AUDCLNT_E_DEVICE_INVALIDATED || result == AUDCLNT_E_RESOURCES_INVALIDATED)
            {
                return WindowsCaptureError::DeviceInvalidated;
            }
            return WindowsCaptureError::InitializationFailed;
        }

        WindowsCaptureError MapRuntimeFailure(HRESULT result) noexcept
        {
            if (result == E_ACCESSDENIED)
            {
                return WindowsCaptureError::PrivacyBlocked;
            }
            if (result == AUDCLNT_E_DEVICE_INVALIDATED || result == AUDCLNT_E_RESOURCES_INVALIDATED)
            {
                return WindowsCaptureError::DeviceInvalidated;
            }
            if (result == AUDCLNT_E_SERVICE_NOT_RUNNING)
            {
                return WindowsCaptureError::AudioServiceUnavailable;
            }
            return WindowsCaptureError::CaptureFailed;
        }

        WindowsCaptureError MapPacketFailure(AudioCapturePacketResult result) noexcept
        {
            switch (result)
            {
                case AudioCapturePacketResult::Accepted:
                    return WindowsCaptureError::None;
                case AudioCapturePacketResult::InvalidPacket:
                    return WindowsCaptureError::InvalidCapturePacket;
                case AudioCapturePacketResult::DataDiscontinuity:
                    return WindowsCaptureError::DataDiscontinuity;
                case AudioCapturePacketResult::InvalidTimestamp:
                    return WindowsCaptureError::InvalidTimestamp;
                case AudioCapturePacketResult::NonMonotonicPosition:
                    return WindowsCaptureError::NonMonotonicPosition;
                case AudioCapturePacketResult::QueueOverflow:
                    return WindowsCaptureError::QueueOverflow;
            }
            return WindowsCaptureError::CaptureFailed;
        }

        WAVEFORMATEX BuildRequiredCaptureFormat() noexcept
        {
            WAVEFORMATEX format {};
            format.wFormatTag = WAVE_FORMAT_IEEE_FLOAT;
            format.nChannels = 1;
            format.nSamplesPerSec = 16000;
            format.nAvgBytesPerSec = 16000 * sizeof(float);
            format.nBlockAlign = sizeof(float);
            format.wBitsPerSample = 32;
            format.cbSize = 0;
            return format;
        }
    }

    class WindowsAudioSessionCapture::Implementation final
    {
    public:
        explicit Implementation(BoundedAudioBlockQueue& destinationQueue)
            : destinationQueue_(destinationQueue),
              packetProcessor_(destinationQueue)
        {
            stopEvent_ = CreateEventW(nullptr, TRUE, FALSE, nullptr);
            audioReadyEvent_ = CreateEventW(nullptr, FALSE, FALSE, nullptr);
            consumerAudioEvent_ = CreateEventW(nullptr, FALSE, FALSE, nullptr);
            terminalEvent_ = CreateEventW(nullptr, TRUE, FALSE, nullptr);
            if (stopEvent_ == nullptr || audioReadyEvent_ == nullptr || consumerAudioEvent_ == nullptr || terminalEvent_ == nullptr)
            {
                CloseEvents();
                throw std::runtime_error("Windows capture event creation failed.");
            }
        }

        ~Implementation()
        {
            CancelAndJoinProducer();
            ScrubSelectedEndpointIdentifier();
            ScrubCaptureScratch();
            CloseEvents();
        }

        WindowsCaptureError Start(const std::wstring& selectedEndpointIdentifier)
        {
            {
                std::lock_guard<std::mutex> lifecycleLock(lifecycleMutex_);
                const WindowsCaptureState currentState = state_.load(std::memory_order_acquire);
                if (currentState != WindowsCaptureState::Constructed)
                {
                    return currentState == WindowsCaptureState::Cancelled
                        ? WindowsCaptureError::Cancelled
                        : WindowsCaptureError::InitializationFailed;
                }
                if (!destinationQueue_.IsEmptyAndScrubbedForStart())
                {
                    return WindowsCaptureError::InitializationFailed;
                }

                selectedEndpointIdentifier_ = selectedEndpointIdentifier;
                ResetEvent(stopEvent_);
                ResetEvent(audioReadyEvent_);
                ResetEvent(consumerAudioEvent_);
                ResetEvent(terminalEvent_);
                state_.store(WindowsCaptureState::Starting, std::memory_order_release);
                try
                {
                    captureThread_ = std::thread(&Implementation::CaptureThreadMain, this);
                }
                catch (...)
                {
                    PublishTerminalState(WindowsCaptureState::Failed, WindowsCaptureError::InitializationFailed);
                    return WindowsCaptureError::InitializationFailed;
                }
            }

            std::unique_lock<std::mutex> initializationLock(initializationMutex_);
            initializationCondition_.wait(
                initializationLock,
                [this]
                {
                    return initializationCompleted_;
                });
            if (shutdownRequest_.load(std::memory_order_acquire) == ShutdownRequest::Cancel)
            {
                return WindowsCaptureError::Cancelled;
            }
            return error_.load(std::memory_order_acquire);
        }

        void StopAndJoinProducer() noexcept
        {
            std::lock_guard<std::mutex> lifecycleLock(lifecycleMutex_);
            if (state_.load(std::memory_order_acquire) == WindowsCaptureState::Constructed)
            {
                shutdownRequest_.store(ShutdownRequest::Stop, std::memory_order_release);
                PublishTerminalState(WindowsCaptureState::Stopped, WindowsCaptureError::None);
                return;
            }
            RequestShutdown(ShutdownRequest::Stop);
            JoinCaptureThread();
        }

        void CancelAndJoinProducer() noexcept
        {
            std::lock_guard<std::mutex> lifecycleLock(lifecycleMutex_);
            if (state_.load(std::memory_order_acquire) == WindowsCaptureState::Constructed)
            {
                shutdownRequest_.store(ShutdownRequest::Cancel, std::memory_order_release);
                PublishTerminalState(WindowsCaptureState::Cancelled, WindowsCaptureError::None);
                return;
            }
            shutdownRequest_.store(ShutdownRequest::Cancel, std::memory_order_release);
            SetEvent(stopEvent_);
            JoinCaptureThread();
        }

        WindowsCaptureActivity WaitForActivity(std::uint32_t timeoutMilliseconds) noexcept
        {
            const HANDLE waitHandles[] = { terminalEvent_, consumerAudioEvent_ };
            const DWORD waitResult = WaitForMultipleObjects(2, waitHandles, FALSE, timeoutMilliseconds);
            if (waitResult == WAIT_OBJECT_0)
            {
                return WindowsCaptureActivity::Terminal;
            }
            if (waitResult == WAIT_OBJECT_0 + 1)
            {
                return WindowsCaptureActivity::AudioAvailable;
            }
            return waitResult == WAIT_TIMEOUT ? WindowsCaptureActivity::TimedOut : WindowsCaptureActivity::WaitFailed;
        }

        WindowsCaptureState State() const noexcept
        {
            return state_.load(std::memory_order_acquire);
        }

        WindowsCaptureError Error() const noexcept
        {
            return error_.load(std::memory_order_acquire);
        }

        std::uint64_t AcceptedFrameCount() const noexcept
        {
            return acceptedFrameCount_.load(std::memory_order_acquire);
        }

    private:
        void CaptureThreadMain() noexcept
        {
            const HRESULT apartmentResult = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
            if (FAILED(apartmentResult))
            {
                PublishTerminalState(WindowsCaptureState::Failed, WindowsCaptureError::InitializationFailed);
                return;
            }

            DWORD multimediaTaskIndex = 0;
            HANDLE multimediaTask = AvSetMmThreadCharacteristicsW(L"Audio", &multimediaTaskIndex);

            {
                ComPtr<IMMDeviceEnumerator> deviceEnumerator;
                ComPtr<IMMDevice> captureDevice;
                ComPtr<IAudioClient> audioClient;
                ComPtr<IAudioCaptureClient> captureClient;
                UINT32 endpointBufferFrameCount = 0;

                const WindowsCaptureError initializationError = InitializeCapture(
                    deviceEnumerator,
                    captureDevice,
                    audioClient,
                    captureClient,
                    endpointBufferFrameCount);
                if (initializationError != WindowsCaptureError::None)
                {
                    if (initializationError == WindowsCaptureError::Cancelled)
                    {
                        const WindowsCaptureState abortedState =
                            shutdownRequest_.load(std::memory_order_acquire) == ShutdownRequest::Cancel
                            ? WindowsCaptureState::Cancelled
                            : WindowsCaptureState::Stopped;
                        PublishTerminalState(abortedState, WindowsCaptureError::None);
                    }
                    else
                    {
                        PublishTerminalState(WindowsCaptureState::Failed, initializationError);
                    }
                }
                else
                {
                    PublishCaptureStarted();
                    bool audioClientWasStopped = false;
                    RunCaptureLoop(*audioClient.Get(), *captureClient.Get(), audioClientWasStopped);
                    if (!audioClientWasStopped)
                    {
                        const HRESULT stopResult = audioClient->Stop();
                        if (FAILED(stopResult) && error_.load(std::memory_order_acquire) == WindowsCaptureError::None)
                        {
                            error_.store(MapRuntimeFailure(stopResult), std::memory_order_release);
                        }
                    }
                    PublishCompletedState();
                    ScrubCaptureScratch();
                }
            }

            if (multimediaTask != nullptr)
            {
                AvRevertMmThreadCharacteristics(multimediaTask);
            }
            CoUninitialize();
        }

        WindowsCaptureError InitializeCapture(
            ComPtr<IMMDeviceEnumerator>& deviceEnumerator,
            ComPtr<IMMDevice>& captureDevice,
            ComPtr<IAudioClient>& audioClient,
            ComPtr<IAudioCaptureClient>& captureClient,
            UINT32& endpointBufferFrameCount) noexcept
        {
            WindowsCaptureError captureError = ResolveCaptureDevice(deviceEnumerator, captureDevice);
            if (captureError != WindowsCaptureError::None)
            {
                return captureError;
            }

            captureError = ActivateAndConfigureAudioClient(captureDevice, audioClient);
            if (captureError != WindowsCaptureError::None)
            {
                return captureError;
            }

            return PrepareCaptureResourcesAndStart(audioClient, captureClient, endpointBufferFrameCount);
        }

        WindowsCaptureError ResolveCaptureDevice(
            ComPtr<IMMDeviceEnumerator>& deviceEnumerator,
            ComPtr<IMMDevice>& captureDevice) noexcept
        {
            HRESULT result = CoCreateInstance(
                __uuidof(MMDeviceEnumerator),
                nullptr,
                CLSCTX_INPROC_SERVER,
                IID_PPV_ARGS(&deviceEnumerator));
            if (FAILED(result))
            {
                return MapInitializationFailure(result);
            }

            const bool hasSelectedEndpoint = !selectedEndpointIdentifier_.empty();
            if (!hasSelectedEndpoint)
            {
                result = deviceEnumerator->GetDefaultAudioEndpoint(eCapture, eConsole, &captureDevice);
            }
            else
            {
                result = deviceEnumerator->GetDevice(selectedEndpointIdentifier_.c_str(), &captureDevice);
            }
            ScrubSelectedEndpointIdentifier();
            if (FAILED(result))
            {
                if (result == E_ACCESSDENIED)
                {
                    return WindowsCaptureError::PrivacyBlocked;
                }
                return hasSelectedEndpoint ? WindowsCaptureError::SelectedDeviceUnavailable : WindowsCaptureError::NoCaptureDevice;
            }

            DWORD endpointState = 0;
            result = captureDevice->GetState(&endpointState);
            if (FAILED(result) || endpointState != DEVICE_STATE_ACTIVE)
            {
                return hasSelectedEndpoint ? WindowsCaptureError::SelectedDeviceUnavailable : WindowsCaptureError::NoCaptureDevice;
            }

            ComPtr<IMMEndpoint> captureEndpoint;
            EDataFlow endpointDataFlow = eAll;
            result = captureDevice.As(&captureEndpoint);
            if (SUCCEEDED(result))
            {
                result = captureEndpoint->GetDataFlow(&endpointDataFlow);
            }
            if (FAILED(result) || endpointDataFlow != eCapture)
            {
                return hasSelectedEndpoint ? WindowsCaptureError::SelectedDeviceUnavailable : WindowsCaptureError::InitializationFailed;
            }
            if (shutdownRequest_.load(std::memory_order_acquire) != ShutdownRequest::None)
            {
                return WindowsCaptureError::Cancelled;
            }

            return WindowsCaptureError::None;
        }

        WindowsCaptureError ActivateAndConfigureAudioClient(
            const ComPtr<IMMDevice>& captureDevice,
            ComPtr<IAudioClient>& audioClient) noexcept
        {
            HRESULT result = captureDevice->Activate(__uuidof(IAudioClient), CLSCTX_INPROC_SERVER, nullptr, reinterpret_cast<void**>(audioClient.GetAddressOf()));
            if (FAILED(result))
            {
                return MapInitializationFailure(result);
            }
            if (shutdownRequest_.load(std::memory_order_acquire) != ShutdownRequest::None)
            {
                return WindowsCaptureError::Cancelled;
            }

            WAVEFORMATEX requiredFormat = BuildRequiredCaptureFormat();
            result = audioClient->Initialize(
                AUDCLNT_SHAREMODE_SHARED,
                CaptureStreamFlags,
                0,
                0,
                &requiredFormat,
                nullptr);
            if (FAILED(result))
            {
                return MapInitializationFailure(result);
            }
            if (shutdownRequest_.load(std::memory_order_acquire) != ShutdownRequest::None)
            {
                return WindowsCaptureError::Cancelled;
            }

            return WindowsCaptureError::None;
        }

        WindowsCaptureError PrepareCaptureResourcesAndStart(
            const ComPtr<IAudioClient>& audioClient,
            ComPtr<IAudioCaptureClient>& captureClient,
            UINT32& endpointBufferFrameCount) noexcept
        {
            HRESULT result = audioClient->GetBufferSize(&endpointBufferFrameCount);
            if (FAILED(result))
            {
                return MapInitializationFailure(result);
            }
            if (endpointBufferFrameCount == 0 || endpointBufferFrameCount > destinationQueue_.TotalSampleCapacity())
            {
                return WindowsCaptureError::QueueOverflow;
            }
            try
            {
                captureScratch_.assign(endpointBufferFrameCount, 0.0F);
            }
            catch (...)
            {
                return WindowsCaptureError::InitializationFailed;
            }

            result = audioClient->SetEventHandle(audioReadyEvent_);
            if (FAILED(result))
            {
                return MapInitializationFailure(result);
            }

            result = audioClient->GetService(IID_PPV_ARGS(&captureClient));
            if (FAILED(result))
            {
                return MapInitializationFailure(result);
            }
            if (shutdownRequest_.load(std::memory_order_acquire) != ShutdownRequest::None)
            {
                return WindowsCaptureError::Cancelled;
            }

            result = audioClient->Start();
            return SUCCEEDED(result) ? WindowsCaptureError::None : MapInitializationFailure(result);
        }

        void RunCaptureLoop(IAudioClient& audioClient, IAudioCaptureClient& captureClient, bool& audioClientWasStopped) noexcept
        {
            const HANDLE waitHandles[] = { stopEvent_, audioReadyEvent_ };
            while (true)
            {
                const DWORD waitResult = WaitForMultipleObjects(2, waitHandles, FALSE, CaptureStallTimeoutMilliseconds);
                if (waitResult == WAIT_OBJECT_0)
                {
                    if (shutdownRequest_.load(std::memory_order_acquire) == ShutdownRequest::Stop)
                    {
                        const HRESULT stopResult = audioClient.Stop();
                        audioClientWasStopped = SUCCEEDED(stopResult);
                        if (FAILED(stopResult))
                        {
                            error_.store(MapRuntimeFailure(stopResult), std::memory_order_release);
                            return;
                        }

                        const WindowsCaptureError finalDrainError = DrainAvailablePackets(captureClient);
                        if (finalDrainError != WindowsCaptureError::None)
                        {
                            error_.store(finalDrainError, std::memory_order_release);
                        }
                    }
                    return;
                }
                if (waitResult == WAIT_TIMEOUT)
                {
                    error_.store(WindowsCaptureError::CaptureStalled, std::memory_order_release);
                    return;
                }
                if (waitResult != WAIT_OBJECT_0 + 1)
                {
                    error_.store(WindowsCaptureError::CaptureFailed, std::memory_order_release);
                    return;
                }

                const WindowsCaptureError drainError = DrainAvailablePackets(captureClient);
                if (drainError != WindowsCaptureError::None)
                {
                    error_.store(drainError, std::memory_order_release);
                    return;
                }
            }
        }

        WindowsCaptureError DrainAvailablePackets(IAudioCaptureClient& captureClient) noexcept
        {
            UINT32 nextPacketFrameCount = 0;
            HRESULT result = captureClient.GetNextPacketSize(&nextPacketFrameCount);
            if (FAILED(result))
            {
                return MapRuntimeFailure(result);
            }

            while (nextPacketFrameCount != 0)
            {
                BYTE* packetBytes = nullptr;
                UINT32 packetFrameCount = 0;
                DWORD packetFlags = 0;
                UINT64 devicePosition = 0;
                UINT64 performanceCounterPosition = 0;
                result = captureClient.GetBuffer(
                    &packetBytes,
                    &packetFrameCount,
                    &packetFlags,
                    &devicePosition,
                    &performanceCounterPosition);
                if (result == AUDCLNT_S_BUFFER_EMPTY)
                {
                    return WindowsCaptureError::None;
                }
                if (FAILED(result))
                {
                    return MapRuntimeFailure(result);
                }

                const bool isSilent = (packetFlags & AUDCLNT_BUFFERFLAGS_SILENT) != 0;
                bool packetCanBePublished =
                    packetFrameCount != 0 &&
                    packetFrameCount <= captureScratch_.size() &&
                    (isSilent || packetBytes != nullptr);
                if (packetCanBePublished && !isSilent)
                {
                    const float* endpointSamples = reinterpret_cast<const float*>(packetBytes);
                    for (std::size_t sampleIndex = 0; sampleIndex < packetFrameCount; ++sampleIndex)
                    {
                        const float sample = endpointSamples[sampleIndex];
                        captureScratch_[sampleIndex] = std::isfinite(sample) ? std::clamp(sample, -1.0F, 1.0F) : 0.0F;
                    }
                }
                const HRESULT releaseResult = captureClient.ReleaseBuffer(packetFrameCount);
                if (FAILED(releaseResult))
                {
                    ScrubCaptureScratch();
                    return MapRuntimeFailure(releaseResult);
                }

                if (!packetCanBePublished)
                {
                    ScrubCaptureScratch();
                    return WindowsCaptureError::InvalidCapturePacket;
                }

                const AudioCapturePacket packet =
                {
                    isSilent ? nullptr : captureScratch_.data(),
                    packetFrameCount,
                    isSilent,
                    (packetFlags & AUDCLNT_BUFFERFLAGS_DATA_DISCONTINUITY) != 0,
                    (packetFlags & AUDCLNT_BUFFERFLAGS_TIMESTAMP_ERROR) != 0,
                    devicePosition,
                    performanceCounterPosition
                };
                const AudioCapturePacketResult packetResult = packetProcessor_.Process(packet);
                ScrubCaptureScratch();
                if (packetResult != AudioCapturePacketResult::Accepted)
                {
                    return MapPacketFailure(packetResult);
                }

                acceptedFrameCount_.fetch_add(packetFrameCount, std::memory_order_release);
                SetEvent(consumerAudioEvent_);
                result = captureClient.GetNextPacketSize(&nextPacketFrameCount);
                if (FAILED(result))
                {
                    return MapRuntimeFailure(result);
                }
            }
            return WindowsCaptureError::None;
        }

        void RequestShutdown(ShutdownRequest requestedShutdown) noexcept
        {
            ShutdownRequest expectedShutdown = ShutdownRequest::None;
            static_cast<void>(
                shutdownRequest_.compare_exchange_strong(
                    expectedShutdown,
                    requestedShutdown,
                    std::memory_order_acq_rel));
            SetEvent(stopEvent_);
        }

        void JoinCaptureThread() noexcept
        {
            if (captureThread_.joinable() && captureThread_.get_id() != std::this_thread::get_id())
            {
                captureThread_.join();
            }
        }

        void PublishCaptureStarted() noexcept
        {
            state_.store(WindowsCaptureState::Capturing, std::memory_order_release);
            CompleteInitializationWait();
        }

        void PublishTerminalState(WindowsCaptureState terminalState, WindowsCaptureError terminalError) noexcept
        {
            ScrubSelectedEndpointIdentifier();
            error_.store(terminalError, std::memory_order_release);
            state_.store(terminalState, std::memory_order_release);
            SetEvent(terminalEvent_);
            CompleteInitializationWait();
        }

        void PublishCompletedState() noexcept
        {
            if (shutdownRequest_.load(std::memory_order_acquire) == ShutdownRequest::Cancel)
            {
                state_.store(WindowsCaptureState::Cancelled, std::memory_order_release);
                SetEvent(terminalEvent_);
                return;
            }
            if (error_.load(std::memory_order_acquire) != WindowsCaptureError::None)
            {
                state_.store(WindowsCaptureState::Failed, std::memory_order_release);
                SetEvent(terminalEvent_);
                return;
            }
            state_.store(WindowsCaptureState::Stopped, std::memory_order_release);
            SetEvent(terminalEvent_);
        }

        void CompleteInitializationWait() noexcept
        {
            {
                std::lock_guard<std::mutex> initializationLock(initializationMutex_);
                initializationCompleted_ = true;
            }
            initializationCondition_.notify_all();
        }

        void CloseEvents() noexcept
        {
            if (terminalEvent_ != nullptr)
            {
                CloseHandle(terminalEvent_);
                terminalEvent_ = nullptr;
            }
            if (consumerAudioEvent_ != nullptr)
            {
                CloseHandle(consumerAudioEvent_);
                consumerAudioEvent_ = nullptr;
            }
            if (audioReadyEvent_ != nullptr)
            {
                CloseHandle(audioReadyEvent_);
                audioReadyEvent_ = nullptr;
            }
            if (stopEvent_ != nullptr)
            {
                CloseHandle(stopEvent_);
                stopEvent_ = nullptr;
            }
        }

        void ScrubSelectedEndpointIdentifier() noexcept
        {
            volatile wchar_t* writableIdentifier = selectedEndpointIdentifier_.empty() ? nullptr : selectedEndpointIdentifier_.data();
            for (std::size_t characterIndex = 0; characterIndex < selectedEndpointIdentifier_.size(); ++characterIndex)
            {
                writableIdentifier[characterIndex] = L'\0';
            }
            selectedEndpointIdentifier_.clear();
        }

        void ScrubCaptureScratch() noexcept
        {
            volatile float* writableSamples = captureScratch_.empty() ? nullptr : captureScratch_.data();
            for (std::size_t sampleIndex = 0; sampleIndex < captureScratch_.size(); ++sampleIndex)
            {
                writableSamples[sampleIndex] = 0.0F;
            }
        }

        BoundedAudioBlockQueue& destinationQueue_;
        AudioCapturePacketProcessor packetProcessor_;
        std::vector<float> captureScratch_;
        std::wstring selectedEndpointIdentifier_;
        HANDLE stopEvent_ = nullptr;
        HANDLE audioReadyEvent_ = nullptr;
        HANDLE consumerAudioEvent_ = nullptr;
        HANDLE terminalEvent_ = nullptr;
        std::thread captureThread_;
        std::mutex lifecycleMutex_;
        std::mutex initializationMutex_;
        std::condition_variable initializationCondition_;
        bool initializationCompleted_ = false;
        std::atomic<WindowsCaptureState> state_ { WindowsCaptureState::Constructed };
        std::atomic<WindowsCaptureError> error_ { WindowsCaptureError::None };
        std::atomic<ShutdownRequest> shutdownRequest_ { ShutdownRequest::None };
        std::atomic<std::uint64_t> acceptedFrameCount_ { 0 };
    };

    WindowsAudioSessionCapture::WindowsAudioSessionCapture(BoundedAudioBlockQueue& destinationQueue)
        : implementation_(std::make_unique<Implementation>(destinationQueue))
    {
    }

    WindowsAudioSessionCapture::~WindowsAudioSessionCapture() = default;

    WindowsCaptureError WindowsAudioSessionCapture::Start(const std::wstring& selectedEndpointIdentifier)
    {
        return implementation_->Start(selectedEndpointIdentifier);
    }

    void WindowsAudioSessionCapture::StopAndJoinProducer() noexcept
    {
        implementation_->StopAndJoinProducer();
    }

    void WindowsAudioSessionCapture::CancelAndJoinProducer() noexcept
    {
        implementation_->CancelAndJoinProducer();
    }

    WindowsCaptureActivity WindowsAudioSessionCapture::WaitForActivity(std::uint32_t timeoutMilliseconds) noexcept
    {
        return implementation_->WaitForActivity(timeoutMilliseconds);
    }

    WindowsCaptureState WindowsAudioSessionCapture::State() const noexcept
    {
        return implementation_->State();
    }

    WindowsCaptureError WindowsAudioSessionCapture::Error() const noexcept
    {
        return implementation_->Error();
    }

    std::uint64_t WindowsAudioSessionCapture::AcceptedFrameCount() const noexcept
    {
        return implementation_->AcceptedFrameCount();
    }
}
