#define _WIN32_WINNT 0x0A00
#define NOMINMAX
#include <Windows.h>

#include <cerrno>
#include <cstdint>
#include <cwchar>
#include <exception>
#include <iostream>
#include <limits>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

namespace
{
    class UniqueHandle final
    {
    public:
        explicit UniqueHandle(HANDLE handle = nullptr) noexcept
            : handle_(handle)
        {
        }

        ~UniqueHandle()
        {
            Reset();
        }

        UniqueHandle(const UniqueHandle&) = delete;
        UniqueHandle& operator=(const UniqueHandle&) = delete;

        UniqueHandle(UniqueHandle&& other) noexcept
            : handle_(std::exchange(other.handle_, nullptr))
        {
        }

        UniqueHandle& operator=(UniqueHandle&& other) noexcept
        {
            if (this != &other)
            {
                Reset();
                handle_ = std::exchange(other.handle_, nullptr);
            }
            return *this;
        }

        HANDLE Get() const noexcept
        {
            return handle_;
        }

        HANDLE Release() noexcept
        {
            return std::exchange(handle_, nullptr);
        }

        void Reset(HANDLE replacement = nullptr) noexcept
        {
            if (handle_ != nullptr && handle_ != INVALID_HANDLE_VALUE)
            {
                CloseHandle(handle_);
            }
            handle_ = replacement;
        }

    private:
        HANDLE handle_;
    };

    class ProcessThreadAttributeList final
    {
    public:
        explicit ProcessThreadAttributeList(HANDLE workerJob)
            : workerJob_(workerJob)
        {
            SIZE_T requiredByteCount = 0;
            InitializeProcThreadAttributeList(nullptr, 1, 0, &requiredByteCount);
            if (requiredByteCount == 0)
            {
                throw std::runtime_error("Could not size the worker creation attributes.");
            }

            storage_ = HeapAlloc(GetProcessHeap(), 0, requiredByteCount);
            if (storage_ == nullptr)
            {
                throw std::runtime_error("Could not allocate the worker creation attributes.");
            }

            attributeList_ = static_cast<LPPROC_THREAD_ATTRIBUTE_LIST>(storage_);
            if (!InitializeProcThreadAttributeList(attributeList_, 1, 0, &requiredByteCount))
            {
                ReleaseStorage();
                throw std::runtime_error("Could not initialize the worker creation attributes.");
            }
            initialized_ = true;

            if (!UpdateProcThreadAttribute(
                    attributeList_,
                    0,
                    PROC_THREAD_ATTRIBUTE_JOB_LIST,
                    &workerJob_,
                    sizeof(workerJob_),
                    nullptr,
                    nullptr))
            {
                ReleaseStorage();
                throw std::runtime_error("Could not bind the worker creation attributes.");
            }
        }

        ~ProcessThreadAttributeList()
        {
            ReleaseStorage();
        }

        ProcessThreadAttributeList(const ProcessThreadAttributeList&) = delete;
        ProcessThreadAttributeList& operator=(const ProcessThreadAttributeList&) = delete;

        LPPROC_THREAD_ATTRIBUTE_LIST Get() const noexcept
        {
            return attributeList_;
        }

    private:
        void ReleaseStorage() noexcept
        {
            if (initialized_)
            {
                DeleteProcThreadAttributeList(attributeList_);
                initialized_ = false;
            }
            if (storage_ != nullptr)
            {
                HeapFree(GetProcessHeap(), 0, storage_);
                storage_ = nullptr;
                attributeList_ = nullptr;
            }
        }

        void* storage_ = nullptr;
        LPPROC_THREAD_ATTRIBUTE_LIST attributeList_ = nullptr;
        HANDLE workerJob_ = nullptr;
        bool initialized_ = false;
    };

    unsigned long long ParseUnsignedValue(const wchar_t* text, const char* failureMessage)
    {
        if (text == nullptr || *text == L'\0' || *text == L'-')
        {
            throw std::runtime_error(failureMessage);
        }

        wchar_t* firstUnparsedCharacter = nullptr;
        errno = 0;
        const unsigned long long parsedValue = std::wcstoull(text, &firstUnparsedCharacter, 10);
        if (errno == ERANGE ||
            firstUnparsedCharacter == text ||
            *firstUnparsedCharacter != L'\0')
        {
            throw std::runtime_error(failureMessage);
        }

        return parsedValue;
    }

    UniqueHandle OpenVerifiedParentProcess(const wchar_t* processIdentifierText, const wchar_t* creationTimeText)
    {
        const unsigned long long parsedProcessIdentifier = ParseUnsignedValue(
            processIdentifierText,
            "The host process identifier is invalid.");
        const unsigned long long expectedCreationTime = ParseUnsignedValue(
            creationTimeText,
            "The host process creation time is invalid.");
        if (parsedProcessIdentifier == 0 ||
            parsedProcessIdentifier > std::numeric_limits<DWORD>::max() ||
            expectedCreationTime == 0)
        {
            throw std::runtime_error("The host process identity is invalid.");
        }

        UniqueHandle parentProcess(OpenProcess(
            SYNCHRONIZE | PROCESS_QUERY_LIMITED_INFORMATION,
            FALSE,
            static_cast<DWORD>(parsedProcessIdentifier)));
        if (parentProcess.Get() == nullptr)
        {
            throw std::runtime_error("Could not monitor the launcher parent.");
        }

        FILETIME creationTime {};
        FILETIME exitTime {};
        FILETIME kernelTime {};
        FILETIME userTime {};
        if (!GetProcessTimes(parentProcess.Get(), &creationTime, &exitTime, &kernelTime, &userTime))
        {
            throw std::runtime_error("Could not verify the launcher parent.");
        }

        ULARGE_INTEGER actualCreationTime {};
        actualCreationTime.LowPart = creationTime.dwLowDateTime;
        actualCreationTime.HighPart = creationTime.dwHighDateTime;
        if (actualCreationTime.QuadPart != expectedCreationTime ||
            WaitForSingleObject(parentProcess.Get(), 0) != WAIT_TIMEOUT)
        {
            throw std::runtime_error("The launcher parent identity is stale.");
        }

        return parentProcess;
    }

    UniqueHandle CreateKillOnCloseJob()
    {
        UniqueHandle job(CreateJobObjectW(nullptr, nullptr));
        if (job.Get() == nullptr)
        {
            throw std::runtime_error("Could not create the worker lifetime job.");
        }

        JOBOBJECT_EXTENDED_LIMIT_INFORMATION limitInformation {};
        limitInformation.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
        if (!SetInformationJobObject(
                job.Get(),
                JobObjectExtendedLimitInformation,
                &limitInformation,
                sizeof(limitInformation)))
        {
            throw std::runtime_error("Could not configure the worker lifetime job.");
        }

        return job;
    }

    void RequireInheritableStandardHandle(DWORD standardHandleIdentifier)
    {
        const HANDLE standardHandle = GetStdHandle(standardHandleIdentifier);
        if (standardHandle == nullptr ||
            standardHandle == INVALID_HANDLE_VALUE ||
            !SetHandleInformation(standardHandle, HANDLE_FLAG_INHERIT, HANDLE_FLAG_INHERIT))
        {
            throw std::runtime_error("A required private pipe handle is unavailable.");
        }
    }

    PROCESS_INFORMATION CreateSuspendedWorker(const std::wstring& workerExecutable, HANDLE workerJob)
    {
        RequireInheritableStandardHandle(STD_INPUT_HANDLE);
        RequireInheritableStandardHandle(STD_OUTPUT_HANDLE);
        RequireInheritableStandardHandle(STD_ERROR_HANDLE);

        ProcessThreadAttributeList processAttributes(workerJob);
        STARTUPINFOEXW startupInformation {};
        startupInformation.StartupInfo.cb = sizeof(startupInformation);
        startupInformation.StartupInfo.dwFlags = STARTF_USESTDHANDLES;
        startupInformation.StartupInfo.hStdInput = GetStdHandle(STD_INPUT_HANDLE);
        startupInformation.StartupInfo.hStdOutput = GetStdHandle(STD_OUTPUT_HANDLE);
        startupInformation.StartupInfo.hStdError = GetStdHandle(STD_ERROR_HANDLE);
        startupInformation.lpAttributeList = processAttributes.Get();

        std::wstring commandLine = L"\"" + workerExecutable + L"\"";
        std::vector<wchar_t> mutableCommandLine(commandLine.begin(), commandLine.end());
        mutableCommandLine.push_back(L'\0');

        PROCESS_INFORMATION processInformation {};
        if (!CreateProcessW(
                workerExecutable.c_str(),
                mutableCommandLine.data(),
                nullptr,
                nullptr,
                TRUE,
                CREATE_SUSPENDED | CREATE_NO_WINDOW | EXTENDED_STARTUPINFO_PRESENT,
                nullptr,
                nullptr,
                &startupInformation.StartupInfo,
                &processInformation))
        {
            throw std::runtime_error(
                "Could not create the suspended worker. Windows error " +
                std::to_string(GetLastError()) +
                ".");
        }

        return processInformation;
    }

    int RunLauncher(int argumentCount, wchar_t** argumentValues)
    {
        if (argumentCount != 4)
        {
            std::cerr << "ClearDictate worker launcher requires worker and host identity arguments." << std::endl;
            return 2;
        }

        SetErrorMode(SEM_FAILCRITICALERRORS | SEM_NOGPFAULTERRORBOX | SEM_NOOPENFILEERRORBOX);
        UniqueHandle parentProcess = OpenVerifiedParentProcess(argumentValues[2], argumentValues[3]);
        UniqueHandle workerJob = CreateKillOnCloseJob();
        const PROCESS_INFORMATION processInformation = CreateSuspendedWorker(argumentValues[1], workerJob.Get());
        UniqueHandle workerProcess(processInformation.hProcess);
        UniqueHandle workerThread(processInformation.hThread);

        if (WaitForSingleObject(parentProcess.Get(), 0) != WAIT_TIMEOUT)
        {
            workerJob.Reset();
            WaitForSingleObject(workerProcess.Get(), INFINITE);
            return 0;
        }

        if (ResumeThread(workerThread.Get()) == static_cast<DWORD>(-1))
        {
            workerJob.Reset();
            WaitForSingleObject(workerProcess.Get(), INFINITE);
            throw std::runtime_error("Could not resume the bound worker.");
        }
        workerThread.Reset();

        const HANDLE monitoredHandles[] = { parentProcess.Get(), workerProcess.Get() };
        const DWORD waitResult = WaitForMultipleObjects(2, monitoredHandles, FALSE, INFINITE);
        if (waitResult == WAIT_OBJECT_0)
        {
            workerJob.Reset();
            WaitForSingleObject(workerProcess.Get(), 5'000);
            return 0;
        }

        if (waitResult != WAIT_OBJECT_0 + 1)
        {
            workerJob.Reset();
            throw std::runtime_error("The launcher lifetime wait failed.");
        }

        DWORD workerExitCode = 0;
        if (!GetExitCodeProcess(workerProcess.Get(), &workerExitCode))
        {
            throw std::runtime_error("Could not read the worker exit status.");
        }

        workerJob.Reset();
        return static_cast<int>(workerExitCode);
    }
}

int wmain(int argumentCount, wchar_t** argumentValues)
{
    try
    {
        return RunLauncher(argumentCount, argumentValues);
    }
    catch (const std::exception& exception)
    {
        std::cerr << "ClearDictate worker launcher stopped after a lifetime-boundary failure: "
                  << exception.what()
                  << std::endl;
        return 10;
    }
}
