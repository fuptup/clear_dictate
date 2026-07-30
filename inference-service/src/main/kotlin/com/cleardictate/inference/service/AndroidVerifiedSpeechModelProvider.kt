package com.cleardictate.inference.service

import android.content.Context
import com.cleardictate.models.ClearDictateModelCatalog
import com.cleardictate.models.ModelStorageLayout
import com.cleardictate.models.VerifiedModelGroupLease
import com.cleardictate.models.VerifiedModelGroupLeaseManager
import java.io.File

/**
 * Resolves only ClearDictate's immutable app-private Moonshine directory and verifies all files.
 */
class AndroidVerifiedSpeechModelProvider(
    context: Context,
    private val groupLeaseManager: VerifiedModelGroupLeaseManager = VerifiedModelGroupLeaseManager()
) : VerifiedSpeechModelProvider
{
    private val applicationContext = context.applicationContext

    override fun acquireVerifiedModel(cancellationSignal: InferenceCancellationSignal): VerifiedSpeechModelLease
    {
        check(!cancellationSignal.isCancellationRequested) { "Speech model acquisition was cancelled." }

        val manifestGroup = ClearDictateModelCatalog.moonshineTinyStreamingEnglish
        val modelDirectory = installedModelDirectory(applicationContext)
        val acquisition = groupLeaseManager.acquire(modelDirectory.toPath(), manifestGroup)
        val groupLease = acquisition.lease
            ?: throw SpeechModelUnavailableException(acquisition.failure.name, acquisition.failedFilename)

        if (cancellationSignal.isCancellationRequested)
        {
            groupLease.close()
            throw IllegalStateException("Speech model acquisition was cancelled.")
        }

        return AndroidVerifiedSpeechModelLease(groupLease)
    }

    companion object
    {
        fun installedModelDirectory(context: Context): File
        {
            val manifestGroup = ClearDictateModelCatalog.moonshineTinyStreamingEnglish
            val modelRoot = File(
                context.noBackupFilesDir ?: context.filesDir,
                "models/${manifestGroup.logicalIdentifier}"
            )
            return File(modelRoot, ModelStorageLayout.versionDirectoryName(manifestGroup))
        }
    }
}

/**
 * Avoids putting model paths or file contents into failure messages that may cross process logs.
 */
class SpeechModelUnavailableException(
    val verificationFailure: String,
    val failedFilename: String?
) : IllegalStateException("The required local speech model is unavailable or failed verification.")

private class AndroidVerifiedSpeechModelLease(
    private val groupLease: VerifiedModelGroupLease
) : VerifiedSpeechModelLease
{
    override val verifiedModelDirectoryPath: String
        get() = groupLease.verifiedModelDirectory.toString()

    override fun close()
    {
        groupLease.close()
    }
}
