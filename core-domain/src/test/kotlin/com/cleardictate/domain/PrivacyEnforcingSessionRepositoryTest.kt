package com.cleardictate.domain

import com.cleardictate.inference.ClientSessionIdentifier
import com.cleardictate.inference.InferenceOperationContext
import com.cleardictate.inference.OperationIdentifier
import com.cleardictate.inference.OperationPrivacy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Specifies that privacy policy is enforced where persistence occurs, not only by callers.
 */
class PrivacyEnforcingSessionRepositoryTest
{
    @Test
    fun `private sessions never reach persistent storage`() = runTest {
        val storage = RecordingSessionStorage()
        val repository = PrivacyEnforcingSessionRepository(storage)

        val result = repository.save(session(OperationPrivacy.PRIVATE))

        assertEquals(SessionSaveResult.REJECTED_PRIVATE_SESSION, result)
        assertEquals(0, storage.savedSessions.size)
    }

    @Test
    fun `ordinary sessions persist exact raw and clean transcripts`() = runTest {
        val storage = RecordingSessionStorage()
        val repository = PrivacyEnforcingSessionRepository(storage)
        val session = session(OperationPrivacy.STANDARD)

        val result = repository.save(session)

        assertEquals(SessionSaveResult.SAVED, result)
        assertEquals(listOf(session), storage.savedSessions)
    }

    @Test
    fun `session diagnostic rendering never contains transcript text`()
    {
        val session = session(OperationPrivacy.STANDARD)

        val diagnosticText = session.toString()

        kotlin.test.assertFalse(diagnosticText.contains("um  raw"))
        kotlin.test.assertFalse(diagnosticText.contains("Raw"))
    }

    private fun session(privacy: OperationPrivacy): DictationSession
    {
        return DictationSession(
            operationContext = InferenceOperationContext(
                clientSessionIdentifier = ClientSessionIdentifier("client-1"),
                operationIdentifier = OperationIdentifier("session-1"),
                privacy = privacy
            ),
            createdAtEpochMilliseconds = 1_753_892_800_000L,
            exactRawTranscript = " um  raw ",
            cleanTranscript = "Raw",
            polishedTranscript = null,
            selectedMode = TranscriptMode.CLEAN,
            processingDurationMilliseconds = 3,
            recognitionDurationMilliseconds = 2,
            cleanupDurationMilliseconds = 1,
            semanticValidationFallback = false
        )
    }

    private class RecordingSessionStorage : DictationSessionStorage
    {
        val savedSessions = mutableListOf<DictationSession>()

        override suspend fun save(session: DictationSession)
        {
            savedSessions.add(session)
        }
    }
}
