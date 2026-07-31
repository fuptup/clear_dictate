#pragma once

#include "clear_dictate/SpeechAudioCapture.h"

#include <memory>

namespace clear_dictate
{
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
     * Start and JoinProducer are sequenced by that one session-owner thread and
     * must never run concurrently. RequestStop and RequestCancel are the only
     * methods designed for concurrent calls from a protocol-control thread.
     */
    class WindowsAudioSessionCapture final : public SpeechAudioCapture
    {
    public:
        explicit WindowsAudioSessionCapture(BoundedAudioBlockQueue& destinationQueue);
        ~WindowsAudioSessionCapture();

        WindowsAudioSessionCapture(const WindowsAudioSessionCapture&) = delete;
        WindowsAudioSessionCapture& operator=(const WindowsAudioSessionCapture&) = delete;

        WindowsCaptureError Start(const std::wstring& selectedEndpointIdentifier) override;

        /**
         * Signals the capture thread without waiting for Windows audio calls or
         * producer teardown. The recognition owner must call JoinProducer after
         * Start has returned and before touching queued audio or destroying this
         * object.
         */
        void RequestStop() noexcept override;
        void RequestCancel() noexcept override;
        void JoinProducer() noexcept override;

        void StopAndJoinProducer() noexcept;
        void CancelAndJoinProducer() noexcept;

        /**
         * Wakes a future recognition consumer for queued audio or terminal state.
         * On Terminal, the consumer must still drain queued audio for normal Stop.
         */
        WindowsCaptureActivity WaitForActivity(std::uint32_t timeoutMilliseconds) noexcept override;
        WindowsCaptureState State() const noexcept override;
        WindowsCaptureError Error() const noexcept override;
        std::uint64_t AcceptedFrameCount() const noexcept override;

    private:
        class Implementation;
        std::unique_ptr<Implementation> implementation_;
    };
}
