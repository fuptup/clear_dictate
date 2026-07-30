#pragma once

#include <array>
#include <cstdint>
#include <cstdio>
#include <stdexcept>
#include <string>

namespace clear_dictate
{
    struct ModelFileExpectation final
    {
        std::uint64_t expectedByteCount;
        std::array<std::uint8_t, 32> expectedSha256;
    };

    enum class ModelFileVerificationFailure
    {
        InvalidPathEncoding,
        OpenFailed,
        ReadFailed,
        SizeMismatch,
        DigestMismatch,
        CryptographyFailed,
        RewindFailed
    };

    class ModelFileVerificationException final : public std::runtime_error
    {
    public:
        explicit ModelFileVerificationException(ModelFileVerificationFailure failure);

        ModelFileVerificationFailure Failure() const noexcept;

    private:
        ModelFileVerificationFailure failure_;
    };

    /**
     * Owns one file handle from cryptographic verification through model loading.
     *
     * Keeping the same handle open prevents a path replacement between verification
     * and llama.cpp's read of the model bytes.
     */
    class VerifiedModelFile final
    {
    public:
        VerifiedModelFile(const std::string& utf8Path, const ModelFileExpectation& expectation);
        ~VerifiedModelFile();

        VerifiedModelFile(const VerifiedModelFile&) = delete;
        VerifiedModelFile& operator=(const VerifiedModelFile&) = delete;
        VerifiedModelFile(VerifiedModelFile&&) = delete;
        VerifiedModelFile& operator=(VerifiedModelFile&&) = delete;

        std::FILE* Get() const noexcept;

    private:
        std::FILE* file_ = nullptr;
    };
}
