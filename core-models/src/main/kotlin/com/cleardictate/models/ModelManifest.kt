package com.cleardictate.models

import java.nio.file.Files
import java.nio.file.Path
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/**
 * Describes one exact model artifact accepted by a versioned application schema.
 */
data class ModelManifestEntry(
    val logicalIdentifier: String,
    val sourceRepository: String,
    val sourceRevision: String,
    val sourceUri: String,
    val exactFilename: String,
    val expectedByteCount: Long,
    val sha256Digest: String,
    val licenceIdentifier: String,
    val modelSchemaVersion: Int
)

/**
 * Groups the files that must be verified together before one inference model becomes ready.
 */
data class ModelManifestGroup(
    val logicalIdentifier: String,
    val displayName: String,
    val files: List<ModelManifestEntry>
)

/**
 * Enumerates safe, non-sensitive reasons a local model file cannot be trusted.
 */
enum class ModelVerificationFailure
{
    NONE,
    FILE_NOT_FOUND,
    FILENAME_MISMATCH,
    SIZE_MISMATCH,
    DIGEST_MISMATCH,
    READ_FAILURE
}

/**
 * Reports whether a model is safe to expose to a native parser.
 */
data class ModelVerificationResult(
    val verified: Boolean,
    val failure: ModelVerificationFailure
)

/**
 * Verifies cheap metadata before streaming the complete file through Secure Hash Algorithm 256-bit.
 */
object ModelManifestVerifier
{
    /**
     * Performs filename, size, and digest validation without copying the model into memory.
     */
    fun verify(modelPath: Path, manifestEntry: ModelManifestEntry): ModelVerificationResult
    {
        try
        {
            if (!Files.isRegularFile(modelPath))
            {
                return failed(ModelVerificationFailure.FILE_NOT_FOUND)
            }

            if (modelPath.fileName.toString() != manifestEntry.exactFilename)
            {
                return failed(ModelVerificationFailure.FILENAME_MISMATCH)
            }

            FileChannel.open(modelPath, StandardOpenOption.READ).use { fileChannel ->
                return verifyOpenChannel(modelPath, fileChannel, manifestEntry)
            }
        }
        catch (_: Exception)
        {
            return failed(ModelVerificationFailure.READ_FAILURE)
        }
    }

    /**
     * Verifies the already-open handle that a model lease will retain through native ownership.
     */
    internal fun verifyOpenChannel(
        modelPath: Path,
        fileChannel: FileChannel,
        manifestEntry: ModelManifestEntry
    ): ModelVerificationResult
    {
        try
        {
            if (modelPath.fileName.toString() != manifestEntry.exactFilename)
            {
                return failed(ModelVerificationFailure.FILENAME_MISMATCH)
            }

            if (fileChannel.size() != manifestEntry.expectedByteCount)
            {
                return failed(ModelVerificationFailure.SIZE_MISMATCH)
            }

            val actualDigest = calculateSha256(fileChannel)

            if (!actualDigest.equals(manifestEntry.sha256Digest, ignoreCase = true))
            {
                return failed(ModelVerificationFailure.DIGEST_MISMATCH)
            }

            return ModelVerificationResult(verified = true, failure = ModelVerificationFailure.NONE)
        }
        catch (_: Exception)
        {
            return failed(ModelVerificationFailure.READ_FAILURE)
        }
    }

    private fun calculateSha256(fileChannel: FileChannel): String
    {
        val messageDigest = MessageDigest.getInstance("SHA-256")
        val readBuffer = java.nio.ByteBuffer.allocate(DEFAULT_BUFFER_SIZE)

        fileChannel.position(0)

        while (true)
        {
            readBuffer.clear()
            val bytesRead = fileChannel.read(readBuffer)

            if (bytesRead < 0)
            {
                break
            }

            readBuffer.flip()
            messageDigest.update(readBuffer)
        }

        fileChannel.position(0)
        return messageDigest.digest().joinToString(separator = "") { digestByte ->
            "%02x".format(digestByte)
        }
    }

    private fun failed(failure: ModelVerificationFailure): ModelVerificationResult
    {
        return ModelVerificationResult(verified = false, failure = failure)
    }
}
