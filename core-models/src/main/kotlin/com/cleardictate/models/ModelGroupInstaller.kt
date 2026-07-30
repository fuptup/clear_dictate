package com.cleardictate.models

import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Comparator

/**
 * Represents one resumable source response and the byte offset that the source actually honored.
 */
class ModelArtifactDownload(
    val inputStream: InputStream,
    val acceptedOffset: Long,
    private val closeAction: () -> Unit = {}
) : AutoCloseable
{
    override fun close()
    {
        try
        {
            inputStream.close()
        }
        finally
        {
            closeAction()
        }
    }
}

fun interface ModelArtifactSource
{
    fun open(manifestEntry: ModelManifestEntry, requestedOffset: Long): ModelArtifactDownload
}

class ModelArtifactSourceException(
    message: String,
    val retryable: Boolean,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * Receives bounded progress and exposes cooperative cancellation to durable schedulers.
 */
interface ModelInstallationObserver
{
    val isCancellationRequested: Boolean

    fun onProgress(bytesInstalled: Long, totalBytes: Long, filename: String)

    companion object
    {
        val NONE = object : ModelInstallationObserver
        {
            override val isCancellationRequested = false

            override fun onProgress(bytesInstalled: Long, totalBytes: Long, filename: String)
            {
            }
        }
    }
}

enum class ModelInstallationStatus
{
    INSTALLED,
    ALREADY_INSTALLED,
    CANCELLED,
    VERIFICATION_FAILED,
    INPUT_OUTPUT_FAILURE,
    TRANSIENT_INPUT_OUTPUT_FAILURE,
    TARGET_DIRECTORY_CORRUPT,
    INSTALLATION_BUSY
}

data class ModelInstallationResult(
    val status: ModelInstallationStatus,
    val failedFilename: String? = null
)

/**
 * Downloads into a resumable sibling directory and atomically exposes only a completely verified model.
 */
class ModelGroupInstaller(
    private val groupLeaseManager: VerifiedModelGroupLeaseManager = VerifiedModelGroupLeaseManager()
)
{
    fun install(
        installationRoot: Path,
        manifestGroup: ModelManifestGroup,
        artifactSource: ModelArtifactSource,
        observer: ModelInstallationObserver = ModelInstallationObserver.NONE
    ): ModelInstallationResult
    {
        return try
        {
            installChecked(installationRoot.toAbsolutePath().normalize(), manifestGroup, artifactSource, observer)
        }
        catch (failure: ModelArtifactSourceException)
        {
            ModelInstallationResult(
                if (failure.retryable)
                {
                    ModelInstallationStatus.TRANSIENT_INPUT_OUTPUT_FAILURE
                }
                else
                {
                    ModelInstallationStatus.INPUT_OUTPUT_FAILURE
                }
            )
        }
        catch (_: Exception)
        {
            ModelInstallationResult(ModelInstallationStatus.INPUT_OUTPUT_FAILURE)
        }
    }

    private fun installChecked(
        installationRoot: Path,
        manifestGroup: ModelManifestGroup,
        artifactSource: ModelArtifactSource,
        observer: ModelInstallationObserver
    ): ModelInstallationResult
    {
        Files.createDirectories(installationRoot)
        val versionDirectoryName = ModelStorageLayout.versionDirectoryName(manifestGroup)
        val lockPath = installationRoot.resolve(".$versionDirectoryName.lock")

        FileChannel.open(
            lockPath,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE
        ).use { lockChannel ->
            val installationLock = try
            {
                lockChannel.tryLock()
            }
            catch (_: OverlappingFileLockException)
            {
                null
            } ?: return ModelInstallationResult(ModelInstallationStatus.INSTALLATION_BUSY)

            installationLock.use {
                return installWithExclusiveLock(
                    installationRoot,
                    manifestGroup,
                    artifactSource,
                    observer
                )
            }
        }
    }

    private fun installWithExclusiveLock(
        installationRoot: Path,
        manifestGroup: ModelManifestGroup,
        artifactSource: ModelArtifactSource,
        observer: ModelInstallationObserver
    ): ModelInstallationResult
    {
        val targetDirectory = installationRoot.resolve(ModelStorageLayout.versionDirectoryName(manifestGroup))

        if (Files.exists(targetDirectory))
        {
            if (verifyCompleteGroup(targetDirectory, manifestGroup))
            {
                deleteObsoleteQuarantines(installationRoot, targetDirectory.fileName.toString())
                return ModelInstallationResult(ModelInstallationStatus.ALREADY_INSTALLED)
            }

            val quarantineDirectory = installationRoot.resolve(
                ".${targetDirectory.fileName}.corrupt-${System.nanoTime()}"
            )

            try
            {
                Files.move(targetDirectory, quarantineDirectory, StandardCopyOption.ATOMIC_MOVE)
            }
            catch (_: AtomicMoveNotSupportedException)
            {
                return ModelInstallationResult(ModelInstallationStatus.TARGET_DIRECTORY_CORRUPT)
            }
        }

        val stagingDirectory = stagingDirectory(installationRoot, manifestGroup)
        Files.createDirectories(stagingDirectory)
        val totalBytes = manifestGroup.files.sumOf { manifestEntry ->
            manifestEntry.expectedByteCount
        }
        var completedBytes = 0L

        for (manifestEntry in manifestGroup.files)
        {
            if (observer.isCancellationRequested)
            {
                return ModelInstallationResult(ModelInstallationStatus.CANCELLED)
            }

            val completedComponentPath = stagingDirectory.resolve(manifestEntry.exactFilename)
            val completedVerification = ModelManifestVerifier.verify(completedComponentPath, manifestEntry)

            if (completedVerification.verified)
            {
                completedBytes += manifestEntry.expectedByteCount
                observer.onProgress(completedBytes, totalBytes, manifestEntry.exactFilename)
                continue
            }

            if (Files.exists(completedComponentPath))
            {
                Files.delete(completedComponentPath)
            }

            val componentResult = downloadComponent(
                stagingDirectory = stagingDirectory,
                manifestEntry = manifestEntry,
                artifactSource = artifactSource,
                observer = observer,
                completedGroupBytes = completedBytes,
                totalGroupBytes = totalBytes
            )

            if (componentResult != null)
            {
                return componentResult
            }

            completedBytes += manifestEntry.expectedByteCount
        }

        if (!verifyCompleteGroup(stagingDirectory, manifestGroup))
        {
            return ModelInstallationResult(ModelInstallationStatus.VERIFICATION_FAILED)
        }

        try
        {
            Files.move(stagingDirectory, targetDirectory, StandardCopyOption.ATOMIC_MOVE)
        }
        catch (_: AtomicMoveNotSupportedException)
        {
            return ModelInstallationResult(ModelInstallationStatus.INPUT_OUTPUT_FAILURE)
        }

        deleteObsoleteQuarantines(installationRoot, targetDirectory.fileName.toString())
        return ModelInstallationResult(ModelInstallationStatus.INSTALLED)
    }

    /**
     * Removes model-sized corrupt snapshots after a verified immutable replacement is active.
     */
    private fun deleteObsoleteQuarantines(installationRoot: Path, targetDirectoryName: String)
    {
        val quarantinePrefix = ".$targetDirectoryName.corrupt-"

        try
        {
            Files.list(installationRoot).use { candidates ->
                candidates
                    .filter { candidate -> candidate.fileName.toString().startsWith(quarantinePrefix) }
                    .forEach(::deleteDirectoryTree)
            }
        }
        catch (_: IOException)
        {
            // The verified active model remains usable; cleanup will be retried on the next check.
        }
    }

    private fun deleteDirectoryTree(directory: Path)
    {
        Files.walk(directory).use { descendants ->
            descendants
                .sorted(Comparator.reverseOrder())
                .forEach { descendant ->
                    Files.deleteIfExists(descendant)
                }
        }
    }

    private fun downloadComponent(
        stagingDirectory: Path,
        manifestEntry: ModelManifestEntry,
        artifactSource: ModelArtifactSource,
        observer: ModelInstallationObserver,
        completedGroupBytes: Long,
        totalGroupBytes: Long
    ): ModelInstallationResult?
    {
        val partialPath = stagingDirectory.resolve("${manifestEntry.exactFilename}.partial")
        var requestedOffset = if (Files.isRegularFile(partialPath))
        {
            Files.size(partialPath)
        }
        else
        {
            0L
        }

        if (requestedOffset > manifestEntry.expectedByteCount)
        {
            Files.deleteIfExists(partialPath)
            requestedOffset = 0L
        }
        else if (requestedOffset == manifestEntry.expectedByteCount)
        {
            val completedComponentPath = stagingDirectory.resolve(manifestEntry.exactFilename)
            promoteCompletedPartial(partialPath, completedComponentPath)
            val completedPartialVerification = ModelManifestVerifier.verify(completedComponentPath, manifestEntry)

            if (completedPartialVerification.verified)
            {
                observer.onProgress(
                    completedGroupBytes + manifestEntry.expectedByteCount,
                    totalGroupBytes,
                    manifestEntry.exactFilename
                )
                return null
            }

            Files.deleteIfExists(completedComponentPath)
            requestedOffset = 0L
        }

        artifactSource.open(manifestEntry, requestedOffset).use { download ->
            val appendExistingBytes = download.acceptedOffset == requestedOffset && requestedOffset > 0L
            val actualStartingOffset = if (appendExistingBytes) requestedOffset else 0L

            if (!appendExistingBytes)
            {
                Files.deleteIfExists(partialPath)
            }

            val messageDigest = MessageDigest.getInstance("SHA-256")

            if (appendExistingBytes)
            {
                updateDigestFromFile(messageDigest, partialPath)
            }

            val fileChannelOptions = if (appendExistingBytes)
            {
                arrayOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)
            }
            else
            {
                arrayOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
            }
            var downloadedByteCount = actualStartingOffset
            var exceededExpectedByteCount = false

            FileChannel.open(partialPath, *fileChannelOptions).use { outputChannel ->
                val readBuffer = ByteArray(DOWNLOAD_BUFFER_BYTES)

                while (true)
                {
                    if (observer.isCancellationRequested)
                    {
                        return ModelInstallationResult(
                            ModelInstallationStatus.CANCELLED,
                            manifestEntry.exactFilename
                        )
                    }

                    val bytesRead = download.inputStream.read(readBuffer)

                    if (bytesRead < 0)
                    {
                        break
                    }

                    if (bytesRead == 0)
                    {
                        continue
                    }

                    downloadedByteCount += bytesRead

                    if (downloadedByteCount > manifestEntry.expectedByteCount)
                    {
                        exceededExpectedByteCount = true
                        break
                    }

                    messageDigest.update(readBuffer, 0, bytesRead)
                    writeCompletely(outputChannel, ByteBuffer.wrap(readBuffer, 0, bytesRead))
                    observer.onProgress(
                        completedGroupBytes + downloadedByteCount,
                        totalGroupBytes,
                        manifestEntry.exactFilename
                    )
                }

                outputChannel.force(true)
            }

            if (exceededExpectedByteCount)
            {
                Files.deleteIfExists(partialPath)
                return ModelInstallationResult(
                    ModelInstallationStatus.VERIFICATION_FAILED,
                    manifestEntry.exactFilename
                )
            }

            val actualDigest = messageDigest.digest().joinToString(separator = "") { digestByte ->
                "%02x".format(digestByte)
            }

            if (downloadedByteCount != manifestEntry.expectedByteCount ||
                !actualDigest.equals(manifestEntry.sha256Digest, ignoreCase = true))
            {
                Files.deleteIfExists(partialPath)
                return ModelInstallationResult(
                    ModelInstallationStatus.VERIFICATION_FAILED,
                    manifestEntry.exactFilename
                )
            }
        }

        promoteCompletedPartial(
            partialPath,
            stagingDirectory.resolve(manifestEntry.exactFilename)
        )
        return null
    }

    private fun promoteCompletedPartial(partialPath: Path, completedComponentPath: Path)
    {
        try
        {
            Files.move(
                partialPath,
                completedComponentPath,
                StandardCopyOption.ATOMIC_MOVE
            )
        }
        catch (_: AtomicMoveNotSupportedException)
        {
            Files.move(partialPath, completedComponentPath)
        }
    }

    private fun verifyCompleteGroup(directory: Path, manifestGroup: ModelManifestGroup): Boolean
    {
        val acquisition = groupLeaseManager.acquire(directory, manifestGroup)
        val lease = acquisition.lease ?: return false
        lease.close()
        return true
    }

    private fun updateDigestFromFile(messageDigest: MessageDigest, path: Path)
    {
        FileChannel.open(path, StandardOpenOption.READ).use { inputChannel ->
            val readBuffer = ByteBuffer.allocate(DOWNLOAD_BUFFER_BYTES)

            while (true)
            {
                readBuffer.clear()
                val bytesRead = inputChannel.read(readBuffer)

                if (bytesRead < 0)
                {
                    break
                }

                readBuffer.flip()
                messageDigest.update(readBuffer)
            }
        }
    }

    private fun writeCompletely(outputChannel: FileChannel, source: ByteBuffer)
    {
        while (source.hasRemaining())
        {
            outputChannel.write(source)
        }
    }

    companion object
    {
        private const val DOWNLOAD_BUFFER_BYTES = 256 * 1024

        fun stagingDirectory(installationRoot: Path, manifestGroup: ModelManifestGroup): Path
        {
            val versionDirectoryName = ModelStorageLayout.versionDirectoryName(manifestGroup)
            return installationRoot.toAbsolutePath().normalize().resolve(".$versionDirectoryName.installing")
        }
    }
}
