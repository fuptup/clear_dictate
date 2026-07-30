#include "clear_dictate/VerifiedModelFile.h"

#define NOMINMAX
#include <Windows.h>
#include <bcrypt.h>

#include <algorithm>
#include <array>
#include <climits>
#include <cstdint>
#include <cstdio>
#include <limits>
#include <share.h>
#include <string>
#include <vector>

namespace clear_dictate
{
    namespace
    {
        constexpr std::size_t HashReadBufferBytes = 64 * 1024;

        std::wstring ConvertUtf8PathToWide(const std::string& utf8Path)
        {
            if (utf8Path.empty() || utf8Path.size() > static_cast<std::size_t>(INT_MAX))
            {
                throw ModelFileVerificationException(ModelFileVerificationFailure::InvalidPathEncoding);
            }

            const int wideCharacterCount = MultiByteToWideChar(
                CP_UTF8,
                MB_ERR_INVALID_CHARS,
                utf8Path.data(),
                static_cast<int>(utf8Path.size()),
                nullptr,
                0);

            if (wideCharacterCount <= 0)
            {
                throw ModelFileVerificationException(ModelFileVerificationFailure::InvalidPathEncoding);
            }

            std::wstring widePath(static_cast<std::size_t>(wideCharacterCount), L'\0');
            if (MultiByteToWideChar(
                    CP_UTF8,
                    MB_ERR_INVALID_CHARS,
                    utf8Path.data(),
                    static_cast<int>(utf8Path.size()),
                    widePath.data(),
                    wideCharacterCount) != wideCharacterCount)
            {
                throw ModelFileVerificationException(ModelFileVerificationFailure::InvalidPathEncoding);
            }

            return widePath;
        }

        class AlgorithmProviderLease final
        {
        public:
            AlgorithmProviderLease()
            {
                if (BCryptOpenAlgorithmProvider(&handle_, BCRYPT_SHA256_ALGORITHM, nullptr, 0) != 0)
                {
                    throw ModelFileVerificationException(ModelFileVerificationFailure::CryptographyFailed);
                }
            }

            ~AlgorithmProviderLease()
            {
                if (handle_ != nullptr)
                {
                    BCryptCloseAlgorithmProvider(handle_, 0);
                }
            }

            BCRYPT_ALG_HANDLE Get() const noexcept
            {
                return handle_;
            }

        private:
            BCRYPT_ALG_HANDLE handle_ = nullptr;
        };

        class HashLease final
        {
        public:
            explicit HashLease(BCRYPT_ALG_HANDLE algorithm)
                : hashObject_(HashObjectSize(algorithm))
            {
                if (BCryptCreateHash(
                        algorithm,
                        &handle_,
                        hashObject_.data(),
                        static_cast<ULONG>(hashObject_.size()),
                        nullptr,
                        0,
                        0) != 0)
                {
                    throw ModelFileVerificationException(ModelFileVerificationFailure::CryptographyFailed);
                }
            }

            ~HashLease()
            {
                if (handle_ != nullptr)
                {
                    BCryptDestroyHash(handle_);
                }
            }

            void Append(const std::uint8_t* bytes, std::size_t byteCount)
            {
                if (byteCount > static_cast<std::size_t>(ULONG_MAX) ||
                    BCryptHashData(handle_, const_cast<PUCHAR>(bytes), static_cast<ULONG>(byteCount), 0) != 0)
                {
                    throw ModelFileVerificationException(ModelFileVerificationFailure::CryptographyFailed);
                }
            }

            std::array<std::uint8_t, 32> Finish()
            {
                std::array<std::uint8_t, 32> digest {};
                if (BCryptFinishHash(handle_, digest.data(), static_cast<ULONG>(digest.size()), 0) != 0)
                {
                    throw ModelFileVerificationException(ModelFileVerificationFailure::CryptographyFailed);
                }

                return digest;
            }

        private:
            static std::size_t HashObjectSize(BCRYPT_ALG_HANDLE algorithm)
            {
                ULONG objectSize = 0;
                ULONG resultSize = 0;
                if (BCryptGetProperty(
                        algorithm,
                        BCRYPT_OBJECT_LENGTH,
                        reinterpret_cast<PUCHAR>(&objectSize),
                        sizeof(objectSize),
                        &resultSize,
                        0) != 0 ||
                    resultSize != sizeof(objectSize) ||
                    objectSize == 0)
                {
                    throw ModelFileVerificationException(ModelFileVerificationFailure::CryptographyFailed);
                }

                return objectSize;
            }

            BCRYPT_HASH_HANDLE handle_ = nullptr;
            std::vector<std::uint8_t> hashObject_;
        };

        std::array<std::uint8_t, 32> HashOpenFile(std::FILE* file, std::uint64_t& byteCount)
        {
            AlgorithmProviderLease algorithmProvider;
            HashLease hash(algorithmProvider.Get());
            std::array<std::uint8_t, HashReadBufferBytes> readBuffer {};
            byteCount = 0;

            while (true)
            {
                const std::size_t bytesRead = std::fread(readBuffer.data(), 1, readBuffer.size(), file);
                if (bytesRead > 0)
                {
                    if (byteCount > std::numeric_limits<std::uint64_t>::max() - bytesRead)
                    {
                        throw ModelFileVerificationException(ModelFileVerificationFailure::ReadFailed);
                    }

                    byteCount += bytesRead;
                    hash.Append(readBuffer.data(), bytesRead);
                }

                if (bytesRead < readBuffer.size())
                {
                    if (std::ferror(file) != 0)
                    {
                        throw ModelFileVerificationException(ModelFileVerificationFailure::ReadFailed);
                    }

                    break;
                }
            }

            std::fill(readBuffer.begin(), readBuffer.end(), std::uint8_t { 0 });
            return hash.Finish();
        }
    }

    ModelFileVerificationException::ModelFileVerificationException(ModelFileVerificationFailure failure)
        : std::runtime_error("Local model file verification failed."),
          failure_(failure)
    {
    }

    ModelFileVerificationFailure ModelFileVerificationException::Failure() const noexcept
    {
        return failure_;
    }

    VerifiedModelFile::VerifiedModelFile(const std::string& utf8Path, const ModelFileExpectation& expectation)
    {
        const std::wstring widePath = ConvertUtf8PathToWide(utf8Path);
        file_ = _wfsopen(widePath.c_str(), L"rb", _SH_DENYWR);
        if (file_ == nullptr)
        {
            throw ModelFileVerificationException(ModelFileVerificationFailure::OpenFailed);
        }

        try
        {
            std::uint64_t actualByteCount = 0;
            const std::array<std::uint8_t, 32> actualDigest = HashOpenFile(file_, actualByteCount);

            if (actualByteCount != expectation.expectedByteCount)
            {
                throw ModelFileVerificationException(ModelFileVerificationFailure::SizeMismatch);
            }

            if (actualDigest != expectation.expectedSha256)
            {
                throw ModelFileVerificationException(ModelFileVerificationFailure::DigestMismatch);
            }

            if (_fseeki64(file_, 0, SEEK_SET) != 0)
            {
                throw ModelFileVerificationException(ModelFileVerificationFailure::RewindFailed);
            }
        }
        catch (...)
        {
            std::fclose(file_);
            file_ = nullptr;
            throw;
        }
    }

    VerifiedModelFile::~VerifiedModelFile()
    {
        if (file_ != nullptr)
        {
            std::fclose(file_);
        }
    }

    std::FILE* VerifiedModelFile::Get() const noexcept
    {
        return file_;
    }
}
