#include "clear_dictate/MoonshineSpeechEngine.h"
#include "clear_dictate/SpeechInferenceWorkerSession.h"
#include "clear_dictate/WindowsAudioSessionCapture.h"
#include "clear_dictate/WorkerProtocol.h"

#define NOMINMAX
#include <Windows.h>
#include <fcntl.h>
#include <io.h>

#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <exception>
#include <iostream>
#include <memory>
#include <mutex>
#include <new>
#include <utility>
#include <vector>

namespace
{
    class SensitiveFramePayloadScrubber final
    {
    public:
        explicit SensitiveFramePayloadScrubber(std::vector<std::uint8_t>& payload) noexcept
            : payload_(payload)
        {
        }

        ~SensitiveFramePayloadScrubber()
        {
            volatile std::uint8_t* writableBytes = payload_.empty() ? nullptr : payload_.data();
            for (std::size_t byteIndex = 0; byteIndex < payload_.size(); ++byteIndex)
            {
                writableBytes[byteIndex] = 0;
            }
            payload_.clear();
        }

    private:
        std::vector<std::uint8_t>& payload_;
    };

    /**
     * Constructs the verified Moonshine model on the recognition thread that
     * exclusively owns its complete lifetime.
     */
    class LockedMoonshineBackendLoader final : public clear_dictate::SpeechRecognitionBackendLoader
    {
    public:
        std::unique_ptr<clear_dictate::SpeechRecognitionBackend> Load(
            const clear_dictate::SpeechModelLoadRequest& request) override
        {
            return std::make_unique<clear_dictate::MoonshineSpeechEngine>(request.modelDirectory);
        }
    };

    /**
     * Gives every recording a fresh single-use Windows capture object while the
     * session-owned bounded queue remains stable.
     */
    class WindowsSpeechCaptureFactory final : public clear_dictate::SpeechAudioCaptureFactory
    {
    public:
        std::unique_ptr<clear_dictate::SpeechAudioCapture> Create(
            clear_dictate::BoundedAudioBlockQueue& destinationQueue) override
        {
            return std::make_unique<clear_dictate::WindowsAudioSessionCapture>(destinationQueue);
        }
    };

    int RunSpeechWorker(int argumentCount)
    {
        if (argumentCount != 1)
        {
            std::cerr << "ClearDictate speech worker accepts commands only through its private input pipe." << std::endl;
            return 2;
        }

        if (_setmode(_fileno(stdin), _O_BINARY) == -1 || _setmode(_fileno(stdout), _O_BINARY) == -1)
        {
            std::cerr << "ClearDictate speech worker could not configure its private pipes." << std::endl;
            return 3;
        }

        SetErrorMode(SEM_FAILCRITICALERRORS | SEM_NOGPFAULTERRORBOX | SEM_NOOPENFILEERRORBOX);

        clear_dictate::WorkerProtocolCodec protocolCodec;
        LockedMoonshineBackendLoader backendLoader;
        WindowsSpeechCaptureFactory captureFactory;
        std::mutex outputMutex;
        clear_dictate::SpeechInferenceWorkerSession session(
            backendLoader,
            captureFactory,
            [&protocolCodec, &outputMutex](const clear_dictate::WorkerProtocolFrame& frame)
            {
                std::lock_guard<std::mutex> lock(outputMutex);
                protocolCodec.Write(frame, std::cout);
            },
            []()
            {
                TerminateProcess(GetCurrentProcess(), 10);
            });

        while (session.State() != clear_dictate::SpeechWorkerSessionState::Closed &&
               session.State() != clear_dictate::SpeechWorkerSessionState::Failed)
        {
            if (std::cin.peek() == std::char_traits<char>::eof())
            {
                session.Close();
                return 0;
            }

            clear_dictate::WorkerProtocolFrame frame = protocolCodec.Read(std::cin);
            SensitiveFramePayloadScrubber payloadScrubber(frame.payload);
            session.Handle(frame);
        }

        return session.State() == clear_dictate::SpeechWorkerSessionState::Closed ? 0 : 4;
    }
}

int main(int argumentCount, char**)
{
    try
    {
        return RunSpeechWorker(argumentCount);
    }
    catch (const clear_dictate::WorkerProtocolException&)
    {
        std::cerr << "ClearDictate speech worker rejected an invalid protocol frame." << std::endl;
        return 5;
    }
    catch (const clear_dictate::WorkerPayloadException&)
    {
        std::cerr << "ClearDictate speech worker rejected an invalid command payload." << std::endl;
        return 6;
    }
    catch (const clear_dictate::SpeechWorkerSessionException&)
    {
        std::cerr << "ClearDictate speech worker rejected an invalid command sequence." << std::endl;
        return 7;
    }
    catch (const std::bad_alloc&)
    {
        std::cerr << "ClearDictate speech worker exhausted available memory." << std::endl;
        return 8;
    }
    catch (const std::exception&)
    {
        std::cerr << "ClearDictate speech worker stopped after an internal failure." << std::endl;
        return 9;
    }
}
