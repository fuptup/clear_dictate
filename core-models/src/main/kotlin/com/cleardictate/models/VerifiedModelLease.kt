package com.cleardictate.models

import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reports either a live verified-file lease or a non-sensitive verification failure.
 */
data class ModelLeaseAcquisitionResult(
    val lease: VerifiedModelFileLease?,
    val failure: ModelVerificationFailure
)

/**
 * Retains the exact verified file handle while a native model may reopen its immutable path.
 */
class VerifiedModelFileLease internal constructor(
    val verifiedPath: Path,
    val manifestEntry: ModelManifestEntry,
    private val fileChannel: FileChannel,
    private val releaseRegistration: () -> Unit
) : AutoCloseable
{
    private val openState = AtomicBoolean(true)

    val isOpen: Boolean
        get() = openState.get()

    /**
     * Releases the file handle and reference count exactly once.
     */
    override fun close()
    {
        if (!openState.compareAndSet(true, false))
        {
            return
        }

        try
        {
            fileChannel.close()
        }
        finally
        {
            releaseRegistration()
        }
    }

    override fun toString(): String
    {
        return "VerifiedModelFileLease(logicalIdentifier=${manifestEntry.logicalIdentifier}, isOpen=$isOpen)"
    }
}

/**
 * Serializes verification, lease registration, and managed replacement decisions.
 */
class VerifiedModelLeaseManager
{
    private val activeLeaseCounts = mutableMapOf<Path, Int>()

    /**
     * Opens and verifies one file before publishing a lease that blocks managed mutation.
     */
    @Synchronized
    fun acquire(modelPath: Path, manifestEntry: ModelManifestEntry): ModelLeaseAcquisitionResult
    {
        val normalizedPath = modelPath.toAbsolutePath().normalize()
        val fileChannel = try
        {
            FileChannel.open(normalizedPath, StandardOpenOption.READ)
        }
        catch (_: Exception)
        {
            return failedAcquisition(ModelVerificationFailure.FILE_NOT_FOUND)
        }

        val verificationResult = ModelManifestVerifier.verifyOpenChannel(normalizedPath, fileChannel, manifestEntry)

        if (!verificationResult.verified)
        {
            fileChannel.close()
            return failedAcquisition(verificationResult.failure)
        }

        activeLeaseCounts[normalizedPath] = (activeLeaseCounts[normalizedPath] ?: 0) + 1
        val lease = VerifiedModelFileLease(
            verifiedPath = normalizedPath,
            manifestEntry = manifestEntry,
            fileChannel = fileChannel,
            releaseRegistration = { release(normalizedPath) }
        )

        return ModelLeaseAcquisitionResult(lease = lease, failure = ModelVerificationFailure.NONE)
    }

    /**
     * Allows the model manager to mutate only paths with no verified native owners.
     */
    @Synchronized
    fun isReplacementAllowed(modelPath: Path): Boolean
    {
        val normalizedPath = modelPath.toAbsolutePath().normalize()
        return activeLeaseCounts[normalizedPath] == null
    }

    @Synchronized
    private fun release(normalizedPath: Path)
    {
        val remainingLeaseCount = (activeLeaseCounts[normalizedPath] ?: return) - 1

        if (remainingLeaseCount <= 0)
        {
            activeLeaseCounts.remove(normalizedPath)
        }
        else
        {
            activeLeaseCounts[normalizedPath] = remainingLeaseCount
        }
    }

    private fun failedAcquisition(failure: ModelVerificationFailure): ModelLeaseAcquisitionResult
    {
        return ModelLeaseAcquisitionResult(lease = null, failure = failure)
    }
}
