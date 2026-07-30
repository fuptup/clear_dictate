package com.cleardictate.models

import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reports either a complete multi-file lease or the first transcript-free verification failure.
 */
data class ModelGroupLeaseAcquisitionResult(
    val lease: VerifiedModelGroupLease?,
    val failure: ModelVerificationFailure,
    val failedFilename: String?
)

/**
 * Retains every verified component handle for one native model and releases them in reverse order.
 */
class VerifiedModelGroupLease internal constructor(
    val verifiedModelDirectory: Path,
    val manifestGroup: ModelManifestGroup,
    private val componentLeases: List<VerifiedModelFileLease>
) : AutoCloseable
{
    private val openState = AtomicBoolean(true)

    val isOpen: Boolean
        get() = openState.get()

    override fun close()
    {
        if (!openState.compareAndSet(true, false))
        {
            return
        }

        componentLeases.asReversed().forEach { componentLease ->
            componentLease.close()
        }
    }

    override fun toString(): String
    {
        return "VerifiedModelGroupLease(logicalIdentifier=${manifestGroup.logicalIdentifier}, componentCount=${componentLeases.size}, isOpen=$isOpen)"
    }
}

/**
 * Makes multi-file verification atomic from the perspective of native model ownership.
 */
class VerifiedModelGroupLeaseManager(
    private val fileLeaseManager: VerifiedModelLeaseManager = VerifiedModelLeaseManager()
)
{
    /**
     * Acquires every declared component or rolls back all earlier leases before returning failure.
     */
    fun acquire(modelDirectory: Path, manifestGroup: ModelManifestGroup): ModelGroupLeaseAcquisitionResult
    {
        val normalizedDirectory = modelDirectory.toAbsolutePath().normalize()
        val acquiredLeases = mutableListOf<VerifiedModelFileLease>()

        for (manifestEntry in manifestGroup.files)
        {
            val componentPath = resolveComponentPath(normalizedDirectory, manifestEntry.exactFilename)

            if (componentPath == null)
            {
                closeInReverseOrder(acquiredLeases)
                return failed(ModelVerificationFailure.FILENAME_MISMATCH, manifestEntry.exactFilename)
            }

            val acquisition = fileLeaseManager.acquire(componentPath, manifestEntry)
            val componentLease = acquisition.lease

            if (componentLease == null)
            {
                closeInReverseOrder(acquiredLeases)
                return failed(acquisition.failure, manifestEntry.exactFilename)
            }

            acquiredLeases += componentLease
        }

        return ModelGroupLeaseAcquisitionResult(
            lease = VerifiedModelGroupLease(
                verifiedModelDirectory = normalizedDirectory,
                manifestGroup = manifestGroup,
                componentLeases = acquiredLeases.toList()
            ),
            failure = ModelVerificationFailure.NONE,
            failedFilename = null
        )
    }

    private fun resolveComponentPath(normalizedDirectory: Path, exactFilename: String): Path?
    {
        val filenamePath = try
        {
            Paths.get(exactFilename)
        }
        catch (_: Exception)
        {
            return null
        }

        if (filenamePath.nameCount != 1 || filenamePath.fileName.toString() != exactFilename)
        {
            return null
        }

        val componentPath = normalizedDirectory.resolve(filenamePath).normalize()
        return componentPath.takeIf { resolvedPath -> resolvedPath.parent == normalizedDirectory }
    }

    private fun closeInReverseOrder(acquiredLeases: List<VerifiedModelFileLease>)
    {
        acquiredLeases.asReversed().forEach { componentLease ->
            componentLease.close()
        }
    }

    private fun failed(failure: ModelVerificationFailure, filename: String): ModelGroupLeaseAcquisitionResult
    {
        return ModelGroupLeaseAcquisitionResult(
            lease = null,
            failure = failure,
            failedFilename = filename
        )
    }
}
