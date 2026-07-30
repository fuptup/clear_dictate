#include "clear_dictate/VerifiedModelFile.h"

#include <array>
#include <exception>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <share.h>
#include <stdexcept>
#include <string>

namespace
{
    void Require(bool condition, const std::string& failureMessage)
    {
        if (!condition)
        {
            throw std::runtime_error(failureMessage);
        }
    }

    class ScopedTestFile final
    {
    public:
        explicit ScopedTestFile(std::filesystem::path path)
            : path_(std::move(path))
        {
            std::ofstream output(path_, std::ios::binary | std::ios::trunc);
            output << "abc";

            if (!output)
            {
                throw std::runtime_error("Could not create the model-verification test file.");
            }
        }

        ~ScopedTestFile()
        {
            std::error_code ignoredError;
            std::filesystem::remove(path_, ignoredError);
        }

        const std::filesystem::path& Path() const noexcept
        {
            return path_;
        }

    private:
        std::filesystem::path path_;
    };

    std::array<std::uint8_t, 32> Sha256OfAbc()
    {
        return
        {
            0xBA, 0x78, 0x16, 0xBF, 0x8F, 0x01, 0xCF, 0xEA,
            0x41, 0x41, 0x40, 0xDE, 0x5D, 0xAE, 0x22, 0x23,
            0xB0, 0x03, 0x61, 0xA3, 0x96, 0x17, 0x7A, 0x9C,
            0xB4, 0x10, 0xFF, 0x61, 0xF2, 0x00, 0x15, 0xAD
        };
    }

    void TestVerifiedFileKeepsTheExactOpenHandle()
    {
        const ScopedTestFile testFile(std::filesystem::current_path() / L"clear_dictate_模型_verification_test.bin");
        const clear_dictate::ModelFileExpectation expectation { 3, Sha256OfAbc() };
        clear_dictate::VerifiedModelFile verifiedFile(testFile.Path().u8string(), expectation);

        Require(verifiedFile.Get() != nullptr, "A correctly verified model file must remain open.");
        Require(std::fgetc(verifiedFile.Get()) == 'a', "The verified file must be rewound before model loading.");
        Require(std::fseek(verifiedFile.Get(), 0, SEEK_SET) == 0, "The verified file must remain seekable.");
    }

    void TestWrongDigestFailsClosed()
    {
        const ScopedTestFile testFile(std::filesystem::current_path() / L"clear_dictate_wrong_digest_test.bin");
        std::array<std::uint8_t, 32> wrongDigest {};
        bool rejected = false;

        try
        {
            const clear_dictate::ModelFileExpectation expectation { 3, wrongDigest };
            clear_dictate::VerifiedModelFile verifiedFile(testFile.Path().u8string(), expectation);
            static_cast<void>(verifiedFile);
        }
        catch (const clear_dictate::ModelFileVerificationException&)
        {
            rejected = true;
        }

        Require(rejected, "A model with the wrong digest must be rejected.");
    }

    void TestVerifiedHandleDeniesConcurrentModificationAndDeletion()
    {
        const ScopedTestFile testFile(std::filesystem::current_path() / L"clear_dictate_share_lock_test.bin");
        const clear_dictate::ModelFileExpectation expectation { 3, Sha256OfAbc() };
        clear_dictate::VerifiedModelFile verifiedFile(testFile.Path().u8string(), expectation);

        std::FILE* concurrentWriter = _wfsopen(
            testFile.Path().c_str(),
            L"r+b",
            _SH_DENYNO);
        Require(concurrentWriter == nullptr, "A verified model file must deny concurrent writers.");

        std::error_code deleteError;
        const bool fileWasDeleted = std::filesystem::remove(testFile.Path(), deleteError);
        Require(!fileWasDeleted && static_cast<bool>(deleteError), "A verified model file must not be replaceable through deletion while it is loaded.");
    }

    int RunAllTests()
    {
        TestVerifiedFileKeepsTheExactOpenHandle();
        TestWrongDigestFailsClosed();
        TestVerifiedHandleDeniesConcurrentModificationAndDeletion();
        return 0;
    }
}

int main()
{
    try
    {
        return RunAllTests();
    }
    catch (const std::exception& exception)
    {
        std::cerr << "ClearDictate verified model file tests failed: " << exception.what() << '\n';
        return 1;
    }
}
