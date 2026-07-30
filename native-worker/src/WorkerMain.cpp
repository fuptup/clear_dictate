#include "clear_dictate/LlamaTextEngine.h"
#include "clear_dictate/TextInferenceWorkerSession.h"
#include "clear_dictate/VerifiedModelFile.h"
#include "clear_dictate/WorkerProtocol.h"
#include "clear_dictate/LockedQwenModel.h"

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
#include <stdexcept>
#include <string>
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
     * Keeps the cryptographically verified model handle alive for at least as long
     * as llama.cpp may access the model.
     */
    class VerifiedLlamaBackend final : public clear_dictate::TextGenerationBackend
    {
    public:
        VerifiedLlamaBackend(const clear_dictate::TextModelLoadRequest& request, const clear_dictate::ModelFileExpectation& expectation)
            : verifiedModelFile_(request.utf8ModelPath, expectation),
              textEngine_(verifiedModelFile_.Get(), request.inferenceThreadCount)
        {
        }

        clear_dictate::TextGenerationResult Generate(
            std::uint64_t requestIdentifier,
            const std::string& systemInstruction,
            const std::string& userInstruction,
            const std::function<bool()>& cancellationRequestedAtStart) override
        {
            return textEngine_.Generate(
                requestIdentifier,
                systemInstruction,
                userInstruction,
                cancellationRequestedAtStart);
        }

        bool Cancel(std::uint64_t requestIdentifier) noexcept override
        {
            return textEngine_.Cancel(requestIdentifier);
        }

        void Close() noexcept override
        {
            textEngine_.Close();
        }

    private:
        clear_dictate::VerifiedModelFile verifiedModelFile_;
        clear_dictate::LlamaTextEngine textEngine_;
    };

    class LockedQwenBackendLoader final : public clear_dictate::TextGenerationBackendLoader
    {
    public:
        std::unique_ptr<clear_dictate::TextGenerationBackend> Load(const clear_dictate::TextModelLoadRequest& request) override
        {
            const clear_dictate::ModelFileExpectation expectation
            {
                clear_dictate::LockedQwenModelByteCount,
                clear_dictate::LockedQwenModelSha256
            };

            return std::make_unique<VerifiedLlamaBackend>(request, expectation);
        }
    };

    int RunWorker(int argumentCount)
    {
        if (argumentCount != 1)
        {
            std::cerr << "ClearDictate worker accepts commands only through its private input pipe." << std::endl;
            return 2;
        }

        if (_setmode(_fileno(stdin), _O_BINARY) == -1 || _setmode(_fileno(stdout), _O_BINARY) == -1)
        {
            std::cerr << "ClearDictate worker could not configure its private pipes." << std::endl;
            return 3;
        }

        SetErrorMode(SEM_FAILCRITICALERRORS | SEM_NOGPFAULTERRORBOX | SEM_NOOPENFILEERRORBOX);

        clear_dictate::WorkerProtocolCodec protocolCodec;
        LockedQwenBackendLoader backendLoader;
        std::mutex outputMutex;
        clear_dictate::TextInferenceWorkerSession session(
            backendLoader,
            [&protocolCodec, &outputMutex](const clear_dictate::WorkerProtocolFrame& frame)
            {
                std::lock_guard<std::mutex> lock(outputMutex);
                protocolCodec.Write(frame, std::cout);
            },
            []()
            {
                TerminateProcess(GetCurrentProcess(), 10);
            });

        while (session.State() != clear_dictate::WorkerSessionState::Closed &&
               session.State() != clear_dictate::WorkerSessionState::Failed)
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

        return session.State() == clear_dictate::WorkerSessionState::Closed ? 0 : 4;
    }
}

int main(int argumentCount, char**)
{
    try
    {
        return RunWorker(argumentCount);
    }
    catch (const clear_dictate::WorkerProtocolException&)
    {
        std::cerr << "ClearDictate worker rejected an invalid protocol frame." << std::endl;
        return 5;
    }
    catch (const clear_dictate::WorkerPayloadException&)
    {
        std::cerr << "ClearDictate worker rejected an invalid command payload." << std::endl;
        return 6;
    }
    catch (const clear_dictate::WorkerSessionException&)
    {
        std::cerr << "ClearDictate worker rejected an invalid command sequence." << std::endl;
        return 7;
    }
    catch (const std::bad_alloc&)
    {
        std::cerr << "ClearDictate worker exhausted available memory." << std::endl;
        return 8;
    }
    catch (const std::exception&)
    {
        std::cerr << "ClearDictate worker stopped after an internal failure." << std::endl;
        return 9;
    }
}
