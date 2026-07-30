package com.cleardictate.domain

import com.cleardictate.inference.InferenceOperationContext
import com.cleardictate.inference.OperationPrivacy

/**
 * Contains the complete transcript lineage and non-sensitive timing metadata for one saved session.
 */
data class DictationSession(
    val operationContext: InferenceOperationContext,
    val createdAtEpochMilliseconds: Long,
    val exactRawTranscript: String,
    val cleanTranscript: String,
    val polishedTranscript: String?,
    val selectedMode: TranscriptMode,
    val processingDurationMilliseconds: Long,
    val recognitionDurationMilliseconds: Long,
    val cleanupDurationMilliseconds: Long,
    val semanticValidationFallback: Boolean
)
{
    override fun toString(): String
    {
        return "DictationSession(operationContext=$operationContext, createdAtEpochMilliseconds=$createdAtEpochMilliseconds, transcripts=<redacted>, selectedMode=$selectedMode, " +
            "processingDurationMilliseconds=$processingDurationMilliseconds, recognitionDurationMilliseconds=$recognitionDurationMilliseconds, " +
            "cleanupDurationMilliseconds=$cleanupDurationMilliseconds, semanticValidationFallback=$semanticValidationFallback)"
    }
}

/**
 * Reports whether persistence occurred without exposing transcript or editor information.
 */
enum class SessionSaveResult
{
    SAVED,
    REJECTED_PRIVATE_SESSION
}

/**
 * Defines the persistence boundary exposed to application and keyboard coordinators.
 */
fun interface DictationSessionRepository
{
    suspend fun save(session: DictationSession): SessionSaveResult
}

/**
 * Represents the platform database operation hidden behind mandatory privacy enforcement.
 */
fun interface DictationSessionStorage
{
    suspend fun save(session: DictationSession)
}

/**
 * Prevents private sessions from reaching Room, desktop storage, or any future persistence adapter.
 *
 * The private check deliberately lives beside the final write boundary so a mistaken caller cannot
 * bypass policy by invoking an otherwise ordinary repository method.
 */
class PrivacyEnforcingSessionRepository(
    private val storage: DictationSessionStorage
) : DictationSessionRepository
{
    override suspend fun save(session: DictationSession): SessionSaveResult
    {
        if (session.operationContext.privacy == OperationPrivacy.PRIVATE)
        {
            return SessionSaveResult.REJECTED_PRIVATE_SESSION
        }

        storage.save(session)
        return SessionSaveResult.SAVED
    }
}
