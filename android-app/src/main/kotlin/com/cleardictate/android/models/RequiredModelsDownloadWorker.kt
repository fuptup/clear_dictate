package com.cleardictate.android.models

import android.annotation.SuppressLint
import android.content.Context
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import com.cleardictate.models.ClearDictateModelCatalog
import com.cleardictate.models.ModelArtifactDownload
import com.cleardictate.models.ModelArtifactSource
import com.cleardictate.models.ModelArtifactSourceException
import com.cleardictate.models.ModelGroupInstaller
import com.cleardictate.models.ModelInstallationObserver
import com.cleardictate.models.ModelInstallationStatus
import com.cleardictate.models.ModelManifestEntry
import com.cleardictate.models.ModelManifestGroup
import com.cleardictate.models.VerifiedModelGroupLeaseManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID

/**
 * Schedules one durable, unique installation of every pinned model required by ClearDictate.
 */
object RequiredModelsDownloadScheduler
{
    const val UNIQUE_WORK_NAME = "install-cleardictate-required-models"

    fun enqueue(context: Context): UUID
    {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresStorageNotLow(true)
            .build()
        val workRequest = OneTimeWorkRequestBuilder<RequiredModelsDownloadWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(UNIQUE_WORK_NAME)
            .build()

        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit {
                putString(CURRENT_WORK_IDENTIFIER, workRequest.id.toString())
            }
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
        return workRequest.id
    }

    fun cancel(context: Context)
    {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
    }

    fun currentWorkIdentifier(context: Context): UUID?
    {
        val value = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getString(CURRENT_WORK_IDENTIFIER, null)
            ?: return null
        return try
        {
            UUID.fromString(value)
        }
        catch (_: IllegalArgumentException)
        {
            null
        }
    }

    private const val PREFERENCES_NAME = "model_download_scheduler"
    private const val CURRENT_WORK_IDENTIFIER = "current_work_identifier"
}

/**
 * Delegates trust decisions to ModelGroupInstaller and supplies only durable scheduling/networking.
 */
class RequiredModelsDownloadWorker(
    applicationContext: Context,
    workerParameters: WorkerParameters
) : CoroutineWorker(applicationContext, workerParameters)
{
    private val artifactSource = HttpsModelArtifactSource()
    private val verifiedModelGroupLeaseManager = VerifiedModelGroupLeaseManager()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO)
    {
        setForeground(createForegroundInfo())
        val manifestGroups = ClearDictateModelCatalog.requiredModelGroups
        val totalRequiredBytes = manifestGroups.sumOf { manifestGroup ->
            manifestGroup.files.sumOf(ModelManifestEntry::expectedByteCount)
        }
        var completedManifestBytes = 0L
        var atLeastOneGroupInstalled = false
        val speechModelReady = AtomicBoolean(false)

        DownloadCancellationMonitor(
            cancellationRequested = { isStopped },
            cancelActiveDownload = artifactSource::cancelActiveRequest
        ).use {
            for (manifestGroup in manifestGroups)
            {
                val installationRoot = installationRoot(applicationContext, manifestGroup)
                val storagePreparationFailure = prepareModelInstallationStorage(
                    installationRoot = installationRoot,
                    manifestGroup = manifestGroup,
                    createDirectories = Files::createDirectories,
                    hasSufficientStorage = ::hasSufficientStorage
                )
                if (storagePreparationFailure != null)
                {
                    return@withContext failureOrRetry(
                        status = storagePreparationFailure,
                        speechModelReady = speechModelReady.get()
                    )
                }

                val installationResult = ModelGroupInstaller().install(
                    installationRoot = installationRoot,
                    manifestGroup = manifestGroup,
                    artifactSource = artifactSource,
                    observer = WorkManagerInstallationObserver(
                        worker = this@RequiredModelsDownloadWorker,
                        previouslyCompletedBytes = completedManifestBytes,
                        totalRequiredBytes = totalRequiredBytes,
                        speechModelReady = speechModelReady::get
                    )
                )
                val output = workDataOf(
                    OUTPUT_STATUS to installationResult.status.name,
                    OUTPUT_FAILED_FILENAME to installationResult.failedFilename.orEmpty(),
                    OUTPUT_SPEECH_MODEL_READY to speechModelReady.get()
                )

                when (installationResult.status)
                {
                    ModelInstallationStatus.INSTALLED ->
                    {
                        atLeastOneGroupInstalled = true
                    }
                    ModelInstallationStatus.ALREADY_INSTALLED -> Unit
                    ModelInstallationStatus.CANCELLED -> return@withContext Result.failure(output)
                    ModelInstallationStatus.TRANSIENT_INPUT_OUTPUT_FAILURE,
                    ModelInstallationStatus.INSTALLATION_BUSY ->
                    {
                        return@withContext if (runAttemptCount < MAXIMUM_RETRY_ATTEMPTS)
                        {
                            Result.retry()
                        }
                        else
                        {
                            Result.failure(output)
                        }
                    }
                    ModelInstallationStatus.INPUT_OUTPUT_FAILURE,
                    ModelInstallationStatus.VERIFICATION_FAILED,
                    ModelInstallationStatus.TARGET_DIRECTORY_CORRUPT ->
                        return@withContext Result.failure(output)
                }

                completedManifestBytes += manifestGroup.files.sumOf(ModelManifestEntry::expectedByteCount)
                if (manifestGroup.logicalIdentifier == ClearDictateModelCatalog.moonshineTinyStreamingEnglish.logicalIdentifier)
                {
                    speechModelReady.set(true)
                }
                setProgress(
                    workDataOf(
                        PROGRESS_BYTES_INSTALLED to completedManifestBytes,
                        PROGRESS_TOTAL_BYTES to totalRequiredBytes,
                        PROGRESS_FILENAME to "",
                        PROGRESS_SPEECH_MODEL_READY to speechModelReady.get()
                    )
                )
            }
        }

        Result.success(
            workDataOf(
                OUTPUT_STATUS to if (atLeastOneGroupInstalled)
                {
                    ModelInstallationStatus.INSTALLED.name
                }
                else
                {
                    ModelInstallationStatus.ALREADY_INSTALLED.name
                },
                OUTPUT_SPEECH_MODEL_READY to speechModelReady.get()
            )
        )
    }

    override suspend fun getForegroundInfo(): ForegroundInfo
    {
        return createForegroundInfo()
    }

    private fun createForegroundInfo(): ForegroundInfo
    {
        val notificationManager = applicationContext.getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(
                DOWNLOAD_NOTIFICATION_CHANNEL,
                "Model downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress while ClearDictate installs local model files."
                setSound(null, null)
                enableVibration(false)
            }
        )
        val cancelIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val notification = NotificationCompat.Builder(applicationContext, DOWNLOAD_NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading ClearDictate local models")
            .setContentText("Verified speech and writing models are being installed.")
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .addAction(android.R.drawable.ic_delete, "Cancel", cancelIntent)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        {
            ForegroundInfo(
                DOWNLOAD_NOTIFICATION_IDENTIFIER,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        }
        else
        {
            ForegroundInfo(DOWNLOAD_NOTIFICATION_IDENTIFIER, notification)
        }
    }

    /**
     * Uses currently usable private storage as a conservative preflight. ClearDictate must not
     * evict unrelated application cache data merely to make a model installation appear possible.
     */
    @SuppressLint("UsableSpace")
    private fun hasSufficientStorage(installationRoot: Path, manifestGroup: ModelManifestGroup): Boolean
    {
        val targetDirectory = installationRoot.resolve(
            com.cleardictate.models.ModelStorageLayout.versionDirectoryName(manifestGroup)
        )

        if (Files.isDirectory(targetDirectory))
        {
            val acquisition = verifiedModelGroupLeaseManager.acquire(targetDirectory, manifestGroup)
            val verifiedLease = acquisition.lease

            if (verifiedLease != null)
            {
                verifiedLease.close()
                return true
            }
        }

        val stagingDirectory = ModelGroupInstaller.stagingDirectory(installationRoot, manifestGroup)
        val remainingModelBytes = manifestGroup.files.sumOf { manifestEntry ->
            val completedPath = stagingDirectory.resolve(manifestEntry.exactFilename)
            val partialPath = stagingDirectory.resolve("${manifestEntry.exactFilename}.partial")
            val existingBytes = when
            {
                Files.isRegularFile(completedPath) -> Files.size(completedPath)
                Files.isRegularFile(partialPath) -> Files.size(partialPath)
                else -> 0L
            }.coerceIn(0L, manifestEntry.expectedByteCount)
            manifestEntry.expectedByteCount - existingBytes
        }
        return installationRoot.toFile().usableSpace >= remainingModelBytes + STORAGE_SAFETY_RESERVE_BYTES
    }

    private fun failureOrRetry(status: ModelInstallationStatus, speechModelReady: Boolean): Result
    {
        val output = workDataOf(
            OUTPUT_STATUS to status.name,
            OUTPUT_SPEECH_MODEL_READY to speechModelReady
        )
        return if (status == ModelInstallationStatus.TRANSIENT_INPUT_OUTPUT_FAILURE &&
            runAttemptCount < MAXIMUM_RETRY_ATTEMPTS)
        {
            Result.retry()
        }
        else
        {
            Result.failure(output)
        }
    }

    companion object
    {
        const val PROGRESS_BYTES_INSTALLED = "bytes_installed"
        const val PROGRESS_TOTAL_BYTES = "total_bytes"
        const val PROGRESS_FILENAME = "filename"
        const val PROGRESS_SPEECH_MODEL_READY = "speech_model_ready"
        const val OUTPUT_STATUS = "installation_status"
        const val OUTPUT_FAILED_FILENAME = "failed_filename"
        const val OUTPUT_SPEECH_MODEL_READY = "speech_model_ready"
        private const val STORAGE_SAFETY_RESERVE_BYTES = 100L * 1024L * 1024L
        private const val MAXIMUM_RETRY_ATTEMPTS = 5
        private const val DOWNLOAD_NOTIFICATION_CHANNEL = "model_downloads"
        private const val DOWNLOAD_NOTIFICATION_IDENTIFIER = 4102

        fun installationRoot(context: Context, manifestGroup: ModelManifestGroup): Path
        {
            val privateBaseDirectory = context.noBackupFilesDir ?: context.filesDir
            return privateBaseDirectory.toPath()
                .resolve("models")
                .resolve(manifestGroup.logicalIdentifier)
        }
    }
}

/**
 * Disconnects a blocking network read promptly when WorkManager stops the owning worker.
 */
internal class DownloadCancellationMonitor(
    private val cancellationRequested: () -> Boolean,
    private val cancelActiveDownload: () -> Unit,
    pollIntervalMilliseconds: Long = 50L
) : AutoCloseable
{
    private val monitorFinished = AtomicBoolean(false)

    init
    {
        require(pollIntervalMilliseconds > 0L) {
            "The cancellation polling interval must be positive."
        }
    }

    private val monitorThread = Thread(
        {
            while (!monitorFinished.get())
            {
                if (cancellationRequested())
                {
                    cancelActiveDownload()
                    return@Thread
                }

                try
                {
                    Thread.sleep(pollIntervalMilliseconds)
                }
                catch (_: InterruptedException)
                {
                    Thread.currentThread().interrupt()
                    return@Thread
                }
            }
        },
        "cleardictate-download-cancellation"
    ).apply {
        isDaemon = true
        start()
    }

    override fun close()
    {
        monitorFinished.set(true)
        monitorThread.interrupt()
        monitorThread.join(MONITOR_JOIN_TIMEOUT_MILLISECONDS)
    }

    private companion object
    {
        const val MONITOR_JOIN_TIMEOUT_MILLISECONDS = 500L
    }
}

private class WorkManagerInstallationObserver(
    private val worker: RequiredModelsDownloadWorker,
    private val previouslyCompletedBytes: Long,
    private val totalRequiredBytes: Long,
    private val speechModelReady: () -> Boolean
) : ModelInstallationObserver
{
    private var lastProgressUpdateNanoseconds = 0L

    override val isCancellationRequested: Boolean
        get() = worker.isStopped

    override fun onProgress(bytesInstalled: Long, totalBytes: Long, filename: String)
    {
        val currentNanoseconds = System.nanoTime()

        if (bytesInstalled < totalBytes &&
            currentNanoseconds - lastProgressUpdateNanoseconds < PROGRESS_UPDATE_INTERVAL_NANOSECONDS)
        {
            return
        }

        lastProgressUpdateNanoseconds = currentNanoseconds
        worker.setProgressAsync(
            workDataOf(
                RequiredModelsDownloadWorker.PROGRESS_BYTES_INSTALLED to
                    previouslyCompletedBytes + bytesInstalled,
                RequiredModelsDownloadWorker.PROGRESS_TOTAL_BYTES to totalRequiredBytes,
                RequiredModelsDownloadWorker.PROGRESS_FILENAME to filename,
                RequiredModelsDownloadWorker.PROGRESS_SPEECH_MODEL_READY to speechModelReady()
            )
        )
    }

    private companion object
    {
        const val PROGRESS_UPDATE_INTERVAL_NANOSECONDS = 500_000_000L
    }
}

/**
 * Maps private-directory setup failures into the same stable installation statuses as downloads.
 */
internal fun prepareModelInstallationStorage(
    installationRoot: Path,
    manifestGroup: ModelManifestGroup,
    createDirectories: (Path) -> Path,
    hasSufficientStorage: (Path, ModelManifestGroup) -> Boolean
): ModelInstallationStatus?
{
    return try
    {
        createDirectories(installationRoot)
        if (hasSufficientStorage(installationRoot, manifestGroup))
        {
            null
        }
        else
        {
            ModelInstallationStatus.INPUT_OUTPUT_FAILURE
        }
    }
    catch (_: IOException)
    {
        ModelInstallationStatus.TRANSIENT_INPUT_OUTPUT_FAILURE
    }
    catch (_: SecurityException)
    {
        ModelInstallationStatus.INPUT_OUTPUT_FAILURE
    }
}

/**
 * Opens only manifest-pinned secure web addresses and honors byte-range resume when available.
 */
private class HttpsModelArtifactSource : ModelArtifactSource
{
    private val cancellationRequested = AtomicBoolean(false)
    private val activeConnection = AtomicReference<HttpURLConnection?>(null)

    fun cancelActiveRequest()
    {
        cancellationRequested.set(true)
        activeConnection.getAndSet(null)?.disconnect()
    }

    override fun open(manifestEntry: ModelManifestEntry, requestedOffset: Long): ModelArtifactDownload
    {
        try
        {
            return openChecked(manifestEntry, requestedOffset)
        }
        catch (failure: ModelArtifactSourceException)
        {
            throw failure
        }
        catch (failure: IOException)
        {
            throw ModelArtifactSourceException(
                "The model server could not be reached.",
                retryable = true,
                cause = failure
            )
        }
        catch (failure: Exception)
        {
            throw ModelArtifactSourceException(
                "The model download configuration is invalid.",
                retryable = false,
                cause = failure
            )
        }
    }

    private fun openChecked(manifestEntry: ModelManifestEntry, requestedOffset: Long): ModelArtifactDownload
    {
        val sourceUri = URI(manifestEntry.sourceUri)

        if (!sourceUri.scheme.equals("https", ignoreCase = true))
        {
            throw ModelArtifactSourceException(
                "Model downloads require a secure web address.",
                retryable = false
            )
        }
        val connection = sourceUri.toURL().openConnection() as HttpURLConnection
        check(!cancellationRequested.get()) { "The model download was cancelled." }
        activeConnection.set(connection)
        try
        {
            ensureNotCancelled(connection)
            connection.connectTimeout = CONNECTION_TIMEOUT_MILLISECONDS
            connection.readTimeout = READ_TIMEOUT_MILLISECONDS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "ClearDictate-Android/0.1")

            if (requestedOffset > 0L)
            {
                connection.setRequestProperty("Range", "bytes=$requestedOffset-")
            }

            connection.connect()
            ensureNotCancelled(connection)
            val responseCode = connection.responseCode

            if (!connection.url.protocol.equals("https", ignoreCase = true))
            {
                throw ModelArtifactSourceException(
                    "Model download redirected to an insecure address.",
                    retryable = false
                )
            }

            val acceptedOffset = when (responseCode)
            {
                HttpURLConnection.HTTP_PARTIAL -> parseAcceptedRangeOffset(connection)
                HttpURLConnection.HTTP_OK -> 0L
                else -> throw ModelArtifactSourceException(
                    "Model server returned HTTP status $responseCode.",
                    retryable = responseCode == HTTP_REQUEST_TIMEOUT ||
                        responseCode == HTTP_TOO_MANY_REQUESTS ||
                        responseCode in 500..599
                )
            }

            if (acceptedOffset != requestedOffset && acceptedOffset != 0L)
            {
                throw ModelArtifactSourceException(
                    "Model server resumed from an unexpected byte offset.",
                    retryable = false
                )
            }

            ensureNotCancelled(connection)
            return ModelArtifactDownload(
                inputStream = BufferedInputStream(connection.inputStream, NETWORK_BUFFER_BYTES),
                acceptedOffset = acceptedOffset,
                closeAction = {
                    activeConnection.compareAndSet(connection, null)
                    connection.disconnect()
                }
            )
        }
        catch (failure: Throwable)
        {
            activeConnection.compareAndSet(connection, null)
            connection.disconnect()
            throw failure
        }
    }

    private fun ensureNotCancelled(connection: HttpURLConnection)
    {
        if (cancellationRequested.get())
        {
            activeConnection.compareAndSet(connection, null)
            connection.disconnect()
            throw IOException("The model download was cancelled.")
        }
    }

    private fun parseAcceptedRangeOffset(connection: HttpURLConnection): Long
    {
        val contentRange = connection.getHeaderField("Content-Range").orEmpty()
        val match = CONTENT_RANGE_PATTERN.matchEntire(contentRange)
            ?: throw IOException("Model server returned an invalid partial-content range.")
        return match.groupValues[1].toLong()
    }

    private companion object
    {
        const val CONNECTION_TIMEOUT_MILLISECONDS = 20_000
        const val READ_TIMEOUT_MILLISECONDS = 30_000
        const val NETWORK_BUFFER_BYTES = 256 * 1024
        const val HTTP_REQUEST_TIMEOUT = 408
        const val HTTP_TOO_MANY_REQUESTS = 429
        val CONTENT_RANGE_PATTERN = Regex("""bytes ([0-9]+)-[0-9]+/(?:[0-9]+|\*)""")
    }
}
