package com.cleardictate.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Specifies how revisable Moonshine output becomes one stable raw transcript.
 */
class StreamingTranscriptAccumulatorTest
{
    @Test
    fun `partial revisions replace the visible partial instead of duplicating it`()
    {
        val accumulator = StreamingTranscriptAccumulator()
        val sessionIdentifier = accumulator.beginSession()

        assertTrue(accumulator.acceptPartial(sessionIdentifier, lineIdentifier = 10L, text = "Hello"))
        assertTrue(accumulator.acceptPartial(sessionIdentifier, lineIdentifier = 10L, text = "Hello world"))

        assertEquals("Hello world", accumulator.snapshot().visibleRawTranscript)
    }

    @Test
    fun `completed line becomes stable and clears its matching partial`()
    {
        val accumulator = StreamingTranscriptAccumulator()
        val sessionIdentifier = accumulator.beginSession()

        accumulator.acceptPartial(sessionIdentifier, lineIdentifier = 10L, text = "Hello wor")
        assertTrue(accumulator.acceptCompleted(sessionIdentifier, lineIdentifier = 10L, text = "Hello world."))

        val snapshot = accumulator.snapshot()
        assertEquals("Hello world.", snapshot.visibleRawTranscript)
        assertEquals("Hello world.", snapshot.completedRawTranscript)
        assertEquals("", snapshot.partialTranscript)
    }

    @Test
    fun `duplicate completed callbacks are ignored`()
    {
        val accumulator = StreamingTranscriptAccumulator()
        val sessionIdentifier = accumulator.beginSession()

        assertTrue(accumulator.acceptCompleted(sessionIdentifier, lineIdentifier = 10L, text = "First line."))
        assertFalse(accumulator.acceptCompleted(sessionIdentifier, lineIdentifier = 10L, text = "First line."))

        assertEquals("First line.", accumulator.snapshot().completedRawTranscript)
    }

    @Test
    fun `completed lines retain deterministic callback order`()
    {
        val accumulator = StreamingTranscriptAccumulator()
        val sessionIdentifier = accumulator.beginSession()

        accumulator.acceptCompleted(sessionIdentifier, lineIdentifier = 20L, text = "First.")
        accumulator.acceptCompleted(sessionIdentifier, lineIdentifier = 10L, text = "Second.")

        assertEquals("First. Second.", accumulator.snapshot().completedRawTranscript)
    }

    @Test
    fun `callbacks from a replaced session cannot alter the active transcript`()
    {
        val accumulator = StreamingTranscriptAccumulator()
        val staleSessionIdentifier = accumulator.beginSession()
        accumulator.acceptPartial(staleSessionIdentifier, lineIdentifier = 10L, text = "Old")

        val activeSessionIdentifier = accumulator.beginSession()

        assertFalse(accumulator.acceptCompleted(staleSessionIdentifier, lineIdentifier = 10L, text = "Old final"))
        assertTrue(accumulator.acceptPartial(activeSessionIdentifier, lineIdentifier = 20L, text = "New"))
        assertEquals("New", accumulator.snapshot().visibleRawTranscript)
    }

    @Test
    fun `cancelling clears all transcript buffers and rejects late callbacks`()
    {
        val accumulator = StreamingTranscriptAccumulator()
        val sessionIdentifier = accumulator.beginSession()
        accumulator.acceptCompleted(sessionIdentifier, lineIdentifier = 10L, text = "Private words")

        assertTrue(accumulator.cancelSession(sessionIdentifier))
        assertFalse(accumulator.acceptPartial(sessionIdentifier, lineIdentifier = 11L, text = "Late words"))
        assertEquals(StreamingTranscriptSnapshot.EMPTY, accumulator.snapshot())
    }

    @Test
    fun `transcript text beyond the configured transport ceiling is rejected without retention`()
    {
        val accumulator = StreamingTranscriptAccumulator(maximumVisibleCharacterCount = 12)
        val sessionIdentifier = accumulator.beginSession()
        accumulator.acceptCompleted(sessionIdentifier, lineIdentifier = 1L, text = "Hello")

        assertFalse(accumulator.acceptPartial(sessionIdentifier, lineIdentifier = 2L, text = "worldwide"))
        assertTrue(accumulator.hasExceededLimit(sessionIdentifier))
        assertEquals("Hello", accumulator.snapshot().visibleRawTranscript)
    }
}
