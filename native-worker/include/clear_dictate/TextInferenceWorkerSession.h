#pragma once

#include "clear_dictate/TextGenerationBackend.h"
#include "clear_dictate/WorkerPayloads.h"
#include "clear_dictate/WorkerProtocol.h"

#include <condition_variable>
#include <cstdint>
#include <functional>
#include <memory>
#include <mutex>
#include <optional>
#include <stdexcept>
#include <thread>

namespace clear_dictate
{
    enum class WorkerSessionState
    {
        AwaitingHello,
        AwaitingModelLoad,
        Idle,
        OperationActive,
        Closed,
        Failed
    };

    enum class WorkerSessionFailure
    {
        IllegalState,
        IllegalMessage,
        OperationIdentityMismatch
    };

    enum class WorkerControlError : std::uint32_t
    {
        ModelLoadFailed = 1
    };

    enum class WorkerOperationError : std::uint32_t
    {
        ContextLimitExceeded = 1,
        OutputLimitReached = 2,
        BackendBusy = 3,
        BackendClosing = 4,
        NativeFailure = 5
    };

    class WorkerSessionException final : public std::runtime_error
    {
    public:
        explicit WorkerSessionException(WorkerSessionFailure failure);

        WorkerSessionFailure Failure() const noexcept;

    private:
        WorkerSessionFailure failure_;
    };

    /**
     * Creates a verified, ready-to-use text model backend for one worker process.
     */
    class TextGenerationBackendLoader
    {
    public:
        virtual ~TextGenerationBackendLoader() = default;
        virtual std::unique_ptr<TextGenerationBackend> Load(const TextModelLoadRequest& request) = 0;
    };

    /**
     * Enforces the native side of the worker protocol and runs generation away from
     * the command-reading thread so cancellation remains responsive.
     */
    class TextInferenceWorkerSession final
    {
    public:
        using FrameWriter = std::function<void(const WorkerProtocolFrame&)>;

        TextInferenceWorkerSession(
            TextGenerationBackendLoader& backendLoader,
            FrameWriter frameWriter,
            std::function<void()> fatalFailureHandler = {});
        ~TextInferenceWorkerSession();

        TextInferenceWorkerSession(const TextInferenceWorkerSession&) = delete;
        TextInferenceWorkerSession& operator=(const TextInferenceWorkerSession&) = delete;

        void Handle(const WorkerProtocolFrame& frame);
        WorkerSessionState State() const;
        void Close() noexcept;

    private:
        struct PendingPolishRequest final
        {
            WorkerOperationIdentity identity;
            TextPolishPrompt prompt;
        };

        void HandleHello(const WorkerProtocolFrame& frame);
        void HandleModelLoad(const WorkerProtocolFrame& frame);
        void HandleIdleCommand(const WorkerProtocolFrame& frame);
        void HandleActiveCommand(const WorkerProtocolFrame& frame);
        void BeginPolish(const WorkerProtocolFrame& frame);
        void RequestCancellation(const WorkerProtocolFrame& frame);
        void GenerationLoop() noexcept;
        void RunPolish(PendingPolishRequest request);
        void PublishGenerationResult(const WorkerOperationIdentity& identity, TextGenerationResult result);
        void WriteFrame(const WorkerProtocolFrame& frame);
        [[noreturn]] void Reject(WorkerSessionFailure failure);

        static bool IdentitiesMatch(const WorkerOperationIdentity& left, const WorkerOperationIdentity& right) noexcept;
        bool MatchesLastTerminalIdentity(const WorkerProtocolFrame& frame) const noexcept;
        static std::vector<std::uint8_t> EncodeFixedError(std::uint32_t category);

        TextGenerationBackendLoader& backendLoader_;
        FrameWriter frameWriter_;
        std::function<void()> fatalFailureHandler_;
        std::mutex commandMutex_;
        mutable std::mutex stateMutex_;
        std::condition_variable pendingRequestChanged_;
        WorkerSessionState state_ = WorkerSessionState::AwaitingHello;
        std::unique_ptr<TextGenerationBackend> backend_;
        std::optional<PendingPolishRequest> pendingRequest_;
        std::optional<WorkerOperationIdentity> activeIdentity_;
        std::optional<WorkerOperationIdentity> lastTerminalIdentity_;
        bool generationStarted_ = false;
        bool cancellationRequested_ = false;
        bool cancellationAcknowledged_ = false;
        bool stopping_ = false;
        std::thread generationThread_;
    };
}
