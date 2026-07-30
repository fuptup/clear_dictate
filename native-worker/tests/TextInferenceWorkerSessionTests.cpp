#include "clear_dictate/TextInferenceWorkerSession.h"

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <exception>
#include <iostream>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

namespace
{
    using clear_dictate::OperationPrivacy;
    using clear_dictate::TextGenerationBackend;
    using clear_dictate::TextGenerationBackendLoader;
    using clear_dictate::TextGenerationResult;
    using clear_dictate::TextGenerationStatus;
    using clear_dictate::TextInferenceWorkerSession;
    using clear_dictate::TextModelLoadRequest;
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

    WorkerOperationIdentity TestIdentity(std::uint64_t requestToken = 41)
    {
        return { "session_1", "operation_1", OperationPrivacy::Private, requestToken };
    }

    std::vector<std::uint8_t> Bytes(const std::string& text)
    {
        return { text.begin(), text.end() };
    }

    class ControllableBackend final : public TextGenerationBackend
    {
    public:
        TextGenerationResult Generate(
            std::uint64_t requestIdentifier,
            const std::string&,
            const std::string&,
            const std::function<bool()>& cancellationRequestedAtStart) override
        {
            {
                std::lock_guard<std::mutex> lock(stateMutex_);
                activeRequestIdentifier_ = requestIdentifier;
                generationStarted_ = true;
                cancellationObserved_ = cancellationRequestedAtStart();
            }
            stateChanged_.notify_all();

            std::unique_lock<std::mutex> lock(stateMutex_);
            stateChanged_.wait(lock, [this]() { return cancellationObserved_ || completionAllowed_ || closing_; });

            if (cancellationObserved_ || closing_)
            {
                return { TextGenerationStatus::Cancelled, {}, 0 };
            }

            return { TextGenerationStatus::Completed, "polished", 3 };
        }

        bool Cancel(std::uint64_t requestIdentifier) noexcept override
        {
            std::lock_guard<std::mutex> lock(stateMutex_);
            if (!generationStarted_ || activeRequestIdentifier_ != requestIdentifier || completionAllowed_)
            {
                return false;
            }

            cancellationObserved_ = true;
            stateChanged_.notify_all();
            return true;
        }

        void Close() noexcept override
        {
            std::lock_guard<std::mutex> lock(stateMutex_);
            closing_ = true;
            stateChanged_.notify_all();
        }

        void WaitUntilGenerationStarts()
        {
            std::unique_lock<std::mutex> lock(stateMutex_);
            stateChanged_.wait(lock, [this]() { return generationStarted_; });
        }

        void AllowCompletion()
        {
            std::lock_guard<std::mutex> lock(stateMutex_);
            completionAllowed_ = true;
            stateChanged_.notify_all();
        }

    private:
        std::mutex stateMutex_;
        std::condition_variable stateChanged_;
        std::uint64_t activeRequestIdentifier_ = 0;
        bool generationStarted_ = false;
        bool cancellationObserved_ = false;
        bool completionAllowed_ = false;
        bool closing_ = false;
    };

    class TestBackendLoader final : public TextGenerationBackendLoader
    {
    public:
        std::unique_ptr<TextGenerationBackend> Load(const TextModelLoadRequest& request) override
        {
            loadedRequest = request;
            auto backend = std::make_unique<ControllableBackend>();
            backendPointer = backend.get();
            return backend;
        }

        TextModelLoadRequest loadedRequest;
        ControllableBackend* backendPointer = nullptr;
    };

    class CapturedFrames final
    {
    public:
        void Add(const WorkerProtocolFrame& frame)
        {
            std::lock_guard<std::mutex> lock(framesMutex_);
            frames_.push_back(frame);
            framesChanged_.notify_all();
        }

        void WaitForCount(std::size_t expectedCount)
        {
            std::unique_lock<std::mutex> lock(framesMutex_);
            framesChanged_.wait(lock, [this, expectedCount]() { return frames_.size() >= expectedCount; });
        }

        std::vector<WorkerProtocolFrame> Snapshot()
        {
            std::lock_guard<std::mutex> lock(framesMutex_);
            return frames_;
        }

    private:
        std::mutex framesMutex_;
        std::condition_variable framesChanged_;
        std::vector<WorkerProtocolFrame> frames_;
    };

    void CompleteHandshake(TextInferenceWorkerSession& session)
    {
        session.Handle(WorkerProtocolFrame::Control(WorkerMessageType::Hello, {}));
        session.Handle(WorkerProtocolFrame::Control(
            WorkerMessageType::LoadModels,
            clear_dictate::EncodeTextModelLoadRequest({ "C:\\models\\qwen.gguf", 4 })));
    }

    void TestHandshakeAndCompletedPolish()
    {
        TestBackendLoader loader;
        CapturedFrames output;
        TextInferenceWorkerSession session(loader, [&output](const WorkerProtocolFrame& frame) { output.Add(frame); });

        CompleteHandshake(session);
        session.Handle(WorkerProtocolFrame::Operation(WorkerMessageType::PolishTranscript, TestIdentity(), Bytes("raw text")));

        loader.backendPointer->WaitUntilGenerationStarts();
        loader.backendPointer->AllowCompletion();
        output.WaitForCount(3);

        const std::vector<WorkerProtocolFrame> frames = output.Snapshot();
        Require(frames[0].type == WorkerMessageType::Ready, "HELLO must produce READY.");
        Require(frames[1].type == WorkerMessageType::ModelsLoaded, "LOAD_MODELS must produce MODELS_LOADED.");
        Require(frames[2].type == WorkerMessageType::PolishedTranscript, "A completed generation must produce POLISHED_TRANSCRIPT.");
        Require(std::string(frames[2].payload.begin(), frames[2].payload.end()) == "polished", "The generated text payload must be preserved.");

        session.Handle(WorkerProtocolFrame::Operation(WorkerMessageType::Cancel, TestIdentity(), {}));
        Require(session.State() == clear_dictate::WorkerSessionState::Idle, "A late cancellation for the last terminal operation must not kill the worker.");
        Require(output.Snapshot().size() == 3, "A late cancellation must not produce a second terminal or acknowledgement frame.");
    }

    void TestCancellationAfterGenerationStarts()
    {
        TestBackendLoader loader;
        CapturedFrames output;
        TextInferenceWorkerSession session(loader, [&output](const WorkerProtocolFrame& frame) { output.Add(frame); });

        CompleteHandshake(session);
        const WorkerOperationIdentity identity = TestIdentity(42);
        session.Handle(WorkerProtocolFrame::Operation(WorkerMessageType::PolishTranscript, identity, Bytes("raw text")));
        loader.backendPointer->WaitUntilGenerationStarts();
        session.Handle(WorkerProtocolFrame::Operation(WorkerMessageType::Cancel, identity, {}));
        output.WaitForCount(4);

        const std::vector<WorkerProtocolFrame> frames = output.Snapshot();
        Require(frames[2].type == WorkerMessageType::CancellationAcknowledged, "An accepted cancellation must be acknowledged.");
        Require(frames[3].type == WorkerMessageType::OperationCancelled, "An acknowledged cancellation must terminate as cancelled.");
    }

    void TestCancellationBeforeBackendStartIsLatched()
    {
        TestBackendLoader loader;
        CapturedFrames output;
        TextInferenceWorkerSession session(loader, [&output](const WorkerProtocolFrame& frame) { output.Add(frame); });

        CompleteHandshake(session);
        const WorkerOperationIdentity identity = TestIdentity(45);
        session.Handle(WorkerProtocolFrame::Operation(WorkerMessageType::PolishTranscript, identity, Bytes("raw text")));
        session.Handle(WorkerProtocolFrame::Operation(WorkerMessageType::Cancel, identity, {}));
        output.WaitForCount(4);

        const std::vector<WorkerProtocolFrame> frames = output.Snapshot();
        Require(frames[2].type == WorkerMessageType::CancellationAcknowledged, "A cancellation accepted before backend start must be acknowledged.");
        Require(frames[3].type == WorkerMessageType::OperationCancelled, "A latched pre-start cancellation must terminate as cancelled.");
    }

    void TestMismatchedCancellationIsRejected()
    {
        TestBackendLoader loader;
        CapturedFrames output;
        TextInferenceWorkerSession session(loader, [&output](const WorkerProtocolFrame& frame) { output.Add(frame); });

        CompleteHandshake(session);
        const WorkerOperationIdentity activeIdentity = TestIdentity(43);
        session.Handle(WorkerProtocolFrame::Operation(WorkerMessageType::PolishTranscript, activeIdentity, Bytes("raw text")));

        bool mismatchRejected = false;
        try
        {
            session.Handle(WorkerProtocolFrame::Operation(WorkerMessageType::Cancel, TestIdentity(44), {}));
        }
        catch (const clear_dictate::WorkerSessionException& exception)
        {
            mismatchRejected = exception.Failure() == clear_dictate::WorkerSessionFailure::OperationIdentityMismatch;
        }

        Require(mismatchRejected, "Cancellation with a different immutable identity must be rejected.");
        loader.backendPointer->WaitUntilGenerationStarts();
        loader.backendPointer->AllowCompletion();
        output.WaitForCount(3);
    }
}

int main()
{
    try
    {
        TestHandshakeAndCompletedPolish();
        TestCancellationAfterGenerationStarts();
        TestCancellationBeforeBackendStartIsLatched();
        TestMismatchedCancellationIsRejected();
        std::cout << "All text inference worker session tests passed." << std::endl;
        return 0;
    }
    catch (const std::exception& exception)
    {
        std::cerr << "Text inference worker session test failure: " << exception.what() << std::endl;
        return 1;
    }
}
