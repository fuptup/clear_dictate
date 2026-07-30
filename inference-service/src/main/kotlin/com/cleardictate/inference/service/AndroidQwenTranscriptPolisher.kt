package com.cleardictate.inference.service

import android.content.Context
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceConfiguration
import com.cleardictate.domain.TranscriptPolisher
import com.cleardictate.domain.TranscriptPolishingConfiguration
import com.cleardictate.domain.TranscriptPolishingRequest
import com.cleardictate.inference.CancellationAcknowledgement
import com.cleardictate.inference.InferenceFailureCategory
import com.cleardictate.inference.InferenceOperationContext
import com.cleardictate.inference.LocalInferenceException
import com.cleardictate.inference.OperationIdentifier
import com.cleardictate.models.ClearDictateModelCatalog
import com.cleardictate.models.ModelStorageLayout
import com.cleardictate.models.VerifiedModelGroupLease
import com.cleardictate.models.VerifiedModelGroupLeaseManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Retains the verified Qwen model file while a native text engine may access it.
 */
interface VerifiedTextModelLease : AutoCloseable
{
    val verifiedModelFilePath: String
}

/**
 * Acquires the complete pinned Qwen model only after byte count and digest verification.
 */
fun interface VerifiedTextModelProvider
{
    fun acquireVerifiedModel(): VerifiedTextModelLease
}

/**
 * Keeps the patched llama.cpp library behind a small, testable, transcript-redacting boundary.
 */
interface LocalTextInferenceEngine : AutoCloseable
{
    suspend fun loadModel(modelFilePath: String, configuration: TranscriptPolishingConfiguration)

    suspend fun setSystemPrompt(systemInstruction: String)

    fun generate(userMessage: String, maximumGeneratedTokens: Int): Flow<String>

    fun cancelGeneration()

    fun unloadModel()
}

fun interface LocalTextInferenceEngineFactory
{
    fun create(): LocalTextInferenceEngine
}

/**
 * Allows the owner to release a large text model only when no transcript operation can use it.
 */
interface IdleReleasableTranscriptPolisher
{
    fun releaseModelIfIdle(): IdleTextModelReleaseResult
}

enum class IdleTextModelReleaseResult
{
    NOT_LOADED,
    BUSY,
    RELEASED,
    FATAL_FAILURE
}

/**
 * Applies operating-system thermal pressure to future model loads without changing active native work.
 */
interface ThermalAwareTranscriptPolisher
{
    fun setThermalConstrained(constrained: Boolean)
}

/**
 * Serializes local Qwen operations and acknowledges cancellation only after generation drains.
 */
class AndroidQwenTranscriptPolisher(
    private val verifiedTextModelProvider: VerifiedTextModelProvider,
    private val textInferenceEngineFactory: LocalTextInferenceEngineFactory
) : TranscriptPolisher, IdleReleasableTranscriptPolisher, ThermalAwareTranscriptPolisher, AutoCloseable
{
    private val operationMutex = Mutex()
    private val activeOperationLock = Any()
    private val activeOperationReference = AtomicReference<ActiveTextOperation?>(null)
    private val closed = AtomicBoolean(false)
    private val nativeEnginePoisoned = AtomicBoolean(false)
    private val thermalConstrained = AtomicBoolean(false)
    private var textModelLease: VerifiedTextModelLease? = null
    private var textInferenceEngine: LocalTextInferenceEngine? = null
    private var loadedConfiguration: TranscriptPolishingConfiguration? = null

    override suspend fun polish(
        operationContext: InferenceOperationContext,
        request: TranscriptPolishingRequest
    ): String
    {
        return operationMutex.withLock {
            check(!closed.get()) {
                "Cannot polish a transcript after the local text engine is closed."
            }

            val activeOperation = ActiveTextOperation(operationContext.operationIdentifier)
            synchronized(activeOperationLock)
            {
                check(activeOperationReference.get() == null) {
                    "The local text engine already owns another transcript operation."
                }
                activeOperationReference.set(activeOperation)
            }

            try
            {
                val effectiveConfiguration = if (thermalConstrained.get())
                {
                    request.configuration.copy(
                        threadCount = minOf(
                            request.configuration.threadCount,
                            THERMALLY_CONSTRAINED_THREAD_COUNT
                        )
                    )
                }
                else
                {
                    request.configuration
                }
                val engine = prepareEngine(effectiveConfiguration)
                synchronized(activeOperationLock)
                {
                    activeOperation.engineReference.set(engine)
                    if (activeOperation.cancellationRequested.get())
                    {
                        engine.cancelGeneration()
                    }
                }
                engine.setSystemPrompt(request.systemInstruction)
                val polishedTranscript = StringBuilder()
                engine.generate(
                    userMessage = request.userMessage,
                    maximumGeneratedTokens = request.configuration.maximumGeneratedTokens
                ).collect { generatedToken ->
                    polishedTranscript.append(generatedToken)
                }

                if (activeOperation.cancellationRequested.get())
                {
                    throw CancellationException("Local text generation was cancelled.")
                }

                polishedTranscript.toString()
            }
            catch (cancellationException: CancellationException)
            {
                synchronized(activeOperationLock)
                {
                    activeOperation.cancellationRequested.set(true)
                    activeOperation.engineReference.get()?.cancelGeneration()
                }
                throw cancellationException
            }
            catch (localInferenceException: LocalInferenceException)
            {
                throw localInferenceException
            }
            catch (_: Throwable)
            {
                poisonNativeEngineAfterUnexpectedFailure()
                throw LocalInferenceException(
                    InferenceFailureCategory.NATIVE_FAILURE,
                    diagnosticCode = "ANDROID_QWEN_NATIVE_FAILURE"
                )
            }
            finally
            {
                synchronized(activeOperationLock)
                {
                    activeOperationReference.compareAndSet(activeOperation, null)
                }
                activeOperation.drained.complete(Unit)
            }
        }
    }

    /**
     * Does not acquire the operation mutex: cancellation must reach native generation immediately.
     */
    override suspend fun cancel(operationIdentifier: OperationIdentifier): CancellationAcknowledgement
    {
        val activeOperation = synchronized(activeOperationLock)
        {
            val currentOperation = activeOperationReference.get()
            if (currentOperation == null || currentOperation.operationIdentifier != operationIdentifier)
            {
                null
            }
            else
            {
                currentOperation.cancellationRequested.set(true)
                try
                {
                    currentOperation.engineReference.get()?.cancelGeneration()
                }
                catch (_: Throwable)
                {
                    // The operation must still drain before cancellation can be acknowledged.
                }
                currentOperation
            }
        }

        if (activeOperation == null)
        {
            return CancellationAcknowledgement(operationIdentifier)
        }

        activeOperation.drained.await()
        return CancellationAcknowledgement(operationIdentifier)
    }

    override fun close()
    {
        if (!closed.compareAndSet(false, true))
        {
            return
        }

        val activeOperation = synchronized(activeOperationLock)
        {
            activeOperationReference.get()?.also { currentOperation ->
                currentOperation.cancellationRequested.set(true)
                try
                {
                    currentOperation.engineReference.get()?.cancelGeneration()
                }
                catch (_: Throwable)
                {
                    // Resource disposal after the operation drains remains the containment boundary.
                }
            }
        }

        runBlocking {
            activeOperation?.drained?.await()
            operationMutex.withLock {
                try
                {
                    textInferenceEngine?.close()
                }
                finally
                {
                    textInferenceEngine = null
                    loadedConfiguration = null
                    textModelLease?.close()
                    textModelLease = null
                }
            }
        }
    }

    override fun setThermalConstrained(constrained: Boolean)
    {
        thermalConstrained.set(constrained)
    }

    /**
     * Uses the same mutex as polishing so model unload cannot race generation or prompt processing.
     */
    override fun releaseModelIfIdle(): IdleTextModelReleaseResult
    {
        if (!operationMutex.tryLock())
        {
            return IdleTextModelReleaseResult.BUSY
        }

        return try
        {
            if (activeOperationReference.get() != null || textModelLease == null)
            {
                IdleTextModelReleaseResult.NOT_LOADED
            }
            else
            {
                val currentEngine = textInferenceEngine
                var unloadSucceeded = true

                try
                {
                    currentEngine?.unloadModel()
                }
                catch (_: Throwable)
                {
                    unloadSucceeded = false
                    nativeEnginePoisoned.set(true)
                    try
                    {
                        currentEngine?.close()
                    }
                    catch (_: Throwable)
                    {
                        // The dedicated inference process is the remaining containment boundary.
                    }
                    textInferenceEngine = null
                }
                finally
                {
                    loadedConfiguration = null
                    textModelLease?.close()
                    textModelLease = null
                }
                if (unloadSucceeded)
                {
                    IdleTextModelReleaseResult.RELEASED
                }
                else
                {
                    IdleTextModelReleaseResult.FATAL_FAILURE
                }
            }
        }
        finally
        {
            operationMutex.unlock()
        }
    }

    private suspend fun prepareEngine(configuration: TranscriptPolishingConfiguration): LocalTextInferenceEngine
    {
        if (nativeEnginePoisoned.get())
        {
            throw LocalInferenceException(
                InferenceFailureCategory.NATIVE_FAILURE,
                diagnosticCode = "ANDROID_QWEN_ENGINE_POISONED"
            )
        }

        val currentEngine = textInferenceEngine

        if (currentEngine != null && textModelLease != null)
        {
            if (loadedConfiguration != configuration)
            {
                throw LocalInferenceException(
                    InferenceFailureCategory.REQUEST_REJECTED,
                    diagnosticCode = "ANDROID_QWEN_CONFIGURATION_CHANGED"
                )
            }
            return currentEngine
        }

        val acquiredLease = try
        {
            verifiedTextModelProvider.acquireVerifiedModel()
        }
        catch (_: Throwable)
        {
            throw LocalInferenceException(
                InferenceFailureCategory.MODEL_VERIFICATION_FAILED,
                diagnosticCode = "ANDROID_QWEN_MODEL_UNAVAILABLE"
            )
        }
        val openedEngine = currentEngine ?: try
        {
            textInferenceEngineFactory.create()
        }
        catch (_: Throwable)
        {
            acquiredLease.close()
            throw LocalInferenceException(
                InferenceFailureCategory.NATIVE_FAILURE,
                diagnosticCode = "ANDROID_QWEN_ENGINE_CREATION_FAILED"
            )
        }

        try
        {
            openedEngine.loadModel(acquiredLease.verifiedModelFilePath, configuration)
        }
        catch (failure: Throwable)
        {
            try
            {
                openedEngine.close()
            }
            finally
            {
                acquiredLease.close()
            }
            textInferenceEngine = null
            loadedConfiguration = null
            nativeEnginePoisoned.set(true)

            if (failure is CancellationException)
            {
                throw failure
            }
            throw LocalInferenceException(
                InferenceFailureCategory.NATIVE_FAILURE,
                diagnosticCode = "ANDROID_QWEN_MODEL_LOAD_FAILED"
            )
        }

        textModelLease = acquiredLease
        textInferenceEngine = openedEngine
        loadedConfiguration = configuration
        return openedEngine
    }

    /**
     * Disposes every handle after a returned native failure so an invalid singleton is never reused.
     */
    private fun poisonNativeEngineAfterUnexpectedFailure()
    {
        nativeEnginePoisoned.set(true)
        val failedEngine = textInferenceEngine
        textInferenceEngine = null
        loadedConfiguration = null

        try
        {
            failedEngine?.close()
        }
        catch (_: Throwable)
        {
            // The coordinator's independent polishing watchdog owns a stuck disposal.
        }
        finally
        {
            textModelLease?.close()
            textModelLease = null
        }
    }

    private data class ActiveTextOperation(
        val operationIdentifier: OperationIdentifier,
        val cancellationRequested: AtomicBoolean = AtomicBoolean(false),
        val engineReference: AtomicReference<LocalTextInferenceEngine?> = AtomicReference(null),
        val drained: CompletableDeferred<Unit> = CompletableDeferred()
    )

    private companion object
    {
        const val THERMALLY_CONSTRAINED_THREAD_COUNT = 2
    }
}

/**
 * Resolves only the immutable application-private Qwen directory and verifies its single file.
 */
class AndroidVerifiedTextModelProvider(
    context: Context,
    private val groupLeaseManager: VerifiedModelGroupLeaseManager = VerifiedModelGroupLeaseManager()
) : VerifiedTextModelProvider
{
    private val applicationContext = context.applicationContext

    override fun acquireVerifiedModel(): VerifiedTextModelLease
    {
        val manifestGroup = ClearDictateModelCatalog.qwenTranscriptPolisher
        val modelDirectory = installedModelDirectory(applicationContext)
        val acquisition = groupLeaseManager.acquire(modelDirectory.toPath(), manifestGroup)
        val groupLease = acquisition.lease
            ?: throw TextModelUnavailableException(acquisition.failure.name, acquisition.failedFilename)
        val modelFile = File(modelDirectory, manifestGroup.files.single().exactFilename)
        return AndroidVerifiedTextModelLease(groupLease, modelFile)
    }

    companion object
    {
        fun installedModelDirectory(context: Context): File
        {
            val manifestGroup = ClearDictateModelCatalog.qwenTranscriptPolisher
            val modelRoot = File(
                context.noBackupFilesDir ?: context.filesDir,
                "models/${manifestGroup.logicalIdentifier}"
            )
            return File(modelRoot, ModelStorageLayout.versionDirectoryName(manifestGroup))
        }
    }
}

/**
 * Avoids model paths in error text that could later cross a process or log boundary.
 */
class TextModelUnavailableException(
    val verificationFailure: String,
    val failedFilename: String?
) : IllegalStateException("The required local text model is unavailable or failed verification.")

private class AndroidVerifiedTextModelLease(
    private val groupLease: VerifiedModelGroupLease,
    private val verifiedModelFile: File
) : VerifiedTextModelLease
{
    override val verifiedModelFilePath: String
        get() = verifiedModelFile.absolutePath

    override fun close()
    {
        groupLease.close()
    }
}

/**
 * Adapts the pinned llama.cpp Android archive without exposing its singleton outside this process.
 */
class LlamaAndroidTextInferenceEngineFactory(
    context: Context
) : LocalTextInferenceEngineFactory
{
    private val applicationContext = context.applicationContext

    override fun create(): LocalTextInferenceEngine
    {
        return LlamaAndroidTextInferenceEngine(applicationContext)
    }
}

private class LlamaAndroidTextInferenceEngine(
    context: Context
) : LocalTextInferenceEngine
{
    private val inferenceEngine = AiChat.getInferenceEngine(context)

    override suspend fun loadModel(
        modelFilePath: String,
        configuration: TranscriptPolishingConfiguration
    )
    {
        inferenceEngine.loadModel(
            modelFilePath,
            InferenceConfiguration(
                contextSizeTokens = configuration.contextSizeTokens,
                threadCount = configuration.threadCount,
                temperature = configuration.temperature,
                topP = configuration.topP,
                deterministicSeed = configuration.deterministicSeed
            )
        )
    }

    override suspend fun setSystemPrompt(systemInstruction: String)
    {
        inferenceEngine.setSystemPrompt(systemInstruction)
    }

    override fun generate(userMessage: String, maximumGeneratedTokens: Int): Flow<String>
    {
        return inferenceEngine.sendUserPrompt(userMessage, maximumGeneratedTokens)
    }

    override fun cancelGeneration()
    {
        inferenceEngine.cancelGeneration()
    }

    override fun unloadModel()
    {
        inferenceEngine.cleanUp()
    }

    override fun close()
    {
        inferenceEngine.destroy()
    }
}
