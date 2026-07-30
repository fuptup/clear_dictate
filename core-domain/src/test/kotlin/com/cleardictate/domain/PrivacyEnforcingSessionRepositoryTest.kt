package com.cleardictate.domain

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

        val result = repository.save(session(isPrivate = true))

        assertEquals(SessionSaveResult.REJECTED_PRIVATE_SESSION, result)
        assertEquals(0, storage.savedSessions.size)
    }

    @Test
    fun `ordinary sessions persist exact raw and clean transcripts`() = runTest {
        val storage = RecordingSessionStorage()
        val repository = PrivacyEnforcingSessionRepository(storage)
        val session = session(isPrivate = false)

        val result = repository.save(session)

        assertEquals(SessionSaveResult.SAVED, result)
        assertEquals(listOf(session), storage.savedSessions)
    }

    private fun session(isPrivate: Boolean): DictationSession
    {
        return DictationSession(
            identifier = "session-1",
            createdAtEpochMilliseconds = 1_753_892_800_000L,
            exactRawTranscript = " um  raw ",
            cleanTranscript = "Raw",
            polishedTranscript = null,
            selectedMode = TranscriptMode.CLEAN,
            isPrivate = isPrivate,
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
