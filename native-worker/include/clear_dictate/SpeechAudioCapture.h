#pragma once

#include "clear_dictate/BoundedAudioBlockQueue.h"

#include <cstdint>
#include <memory>
#include <string>

namespace clear_dictate
{
    enum class WindowsCaptureState
    {
        Constructed,
        Starting,
        Capturing,
        Stopped,
        Cancelled,
        Failed
    };

    enum class WindowsCaptureError
    {
        None,
        PrivacyBlocked,
        NoCaptureDevice,
        SelectedDeviceUnavailable,
        DeviceBusy,
        UnsupportedFormat,
        AudioServiceUnavailable,
        DeviceInvalidated,
        CaptureStalled,
        DataDiscontinuity,
        InvalidTimestamp,
        NonMonotonicPosition,
        QueueOverflow,
        InvalidCapturePacket,
        InitializationFailed,
        CaptureFailed,
        Cancelled
    };

    enum class WindowsCaptureActivity
    {
        AudioAvailable,
        Terminal,
        TimedOut,
        WaitFailed
    };

    /**
     * One recording's capture producer. Stop and cancellation requests are the
     * only methods called concurrently with Start or WaitForActivity.
     */
    class SpeechAudioCapture
    {
    public:
        virtual ~SpeechAudioCapture() = default;

        virtual WindowsCaptureError Start(const std::wstring& selectedEndpointIdentifier) = 0;
        virtual void RequestStop() noexcept = 0;
        virtual void RequestCancel() noexcept = 0;
        virtual void JoinProducer() noexcept = 0;
        virtual WindowsCaptureActivity WaitForActivity(std::uint32_t timeoutMilliseconds) noexcept = 0;
        virtual WindowsCaptureState State() const noexcept = 0;
        virtual WindowsCaptureError Error() const noexcept = 0;
        virtual std::uint64_t AcceptedFrameCount() const noexcept = 0;
    };

    /**
     * Creates a fresh, single-use capture producer for each recording.
     */
    class SpeechAudioCaptureFactory
    {
    public:
        virtual ~SpeechAudioCaptureFactory() = default;
        virtual std::unique_ptr<SpeechAudioCapture> Create(BoundedAudioBlockQueue& destinationQueue) = 0;
    };
}
