package com.cleardictate.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Specifies the conservative behaviour required of deterministic Clean mode.
 */
class DeterministicDisfluencyCleanerTest
{
    private val cleaner = DeterministicDisfluencyCleaner()

    @Test
    fun `removes standalone hesitation tokens and repairs punctuation`()
    {
        val result = cleaner.clean("Um, I think, uh, we should release it on Friday.")

        assertEquals("I think we should release it on Friday.", result.cleanedTranscript)
        assertTrue(result.report.transformations.contains(CleanupTransformation.HESITATIONS_REMOVED))
        assertTrue(result.report.transformations.contains(CleanupTransformation.PUNCTUATION_REPAIRED))
    }

    @Test
    fun `collapses recognition repetition when punctuation indicates a restart`()
    {
        val result = cleaner.clean("Set it to fifty, fifty percent.")

        assertEquals("Set it to fifty percent.", result.cleanedTranscript)
        assertTrue(result.report.transformations.contains(CleanupTransformation.REPEATED_WORDS_COLLAPSED))
    }

    @Test
    fun `preserves intentional emphatic repetition`()
    {
        val result = cleaner.clean("It was very, very difficult.")

        assertEquals("It was very, very difficult.", result.cleanedTranscript)
        assertFalse(result.report.transformations.contains(CleanupTransformation.REPEATED_WORDS_COLLAPSED))
    }

    @Test
    fun `collapses exact duplicated short phrases`()
    {
        val result = cleaner.clean("We should release it, we should release it tomorrow.")

        assertEquals("We should release it tomorrow.", result.cleanedTranscript)
        assertTrue(result.report.transformations.contains(CleanupTransformation.REPEATED_PHRASES_COLLAPSED))
    }

    @Test
    fun `preserves meaningful discourse words`()
    {
        val result = cleaner.clean("Well, I actually like it, so keep it.")

        assertEquals("Well, I actually like it, so keep it.", result.cleanedTranscript)
    }

    @Test
    fun `normalizes whitespace punctuation and sentence capitalization`()
    {
        val result = cleaner.clean("  hello   world  !!   this is clear. ")

        assertEquals("Hello world! This is clear.", result.cleanedTranscript)
        assertTrue(result.report.transformations.contains(CleanupTransformation.WHITESPACE_NORMALIZED))
        assertTrue(result.report.transformations.contains(CleanupTransformation.CAPITALIZATION_RESTORED))
    }
}
