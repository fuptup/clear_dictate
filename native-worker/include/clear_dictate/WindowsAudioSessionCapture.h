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
     * Owns one event-driven Windows shared-mode capture stream.
     *
     * All Windows audio interfaces are created, used, stopped, and released on
     * the dedicated capture thread. A non-empty endpoint identifier binds the
     * recording to that exact device. An empty identifier selects the default
     * console capture endpoint once; the active recording never follows later
     * default-device changes.
     *
     * This object owns only the queue producer. The session owner must stop and
     * join the recognition consumer before scrubbing or destroying the queue.
     */
    class WindowsAudioSessionCapture final
    {
    public:
        explicit WindowsAudioSessionCapture(BoundedAudioBlockQueue& destinationQueue);
        ~WindowsAudioSessionCapture();

        WindowsAudioSessionCapture(const WindowsAudioSessionCapture&) = delete;
        WindowsAudioSessionCapture& operator=(const WindowsAudioSessionCapture&) = delete;

        WindowsCaptureError Start(const std::wstring& selectedEndpointIdentifier);
        void StopAndJoinProducer() noexcept;
        void CancelAndJoinProducer() noexcept;

        /**
         * Wakes a future recognition consumer for queued audio or terminal state.
         * On Terminal, the consumer must still drain queued audio for normal Stop.
         */
        WindowsCaptureActivity WaitForActivity(std::uint32_t timeoutMilliseconds) noexcept;
        WindowsCaptureState State() const noexcept;
        WindowsCaptureError Error() const noexcept;
        std::uint64_t AcceptedFrameCount() const noexcept;

    private:
        class Implementation;
        std::unique_ptr<Implementation> implementation_;
    };
}
