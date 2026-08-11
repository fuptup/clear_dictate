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

    @Test
    fun `does not corrupt times currency file paths or web addresses`()
    {
        val source = """Meet at 10:30; pay £1,250.50; open C:\work\file.txt; visit https://example.com."""

        val result = cleaner.clean(source)

        assertEquals(source, result.cleanedTranscript)
    }

    @Test
    fun `preserves intentional line breaks while normalizing horizontal whitespace`()
    {
        val result = cleaner.clean("  first   line  \r\n  second   line  ")

        assertEquals("First line\nSecond line", result.cleanedTranscript)
    }

    @Test
    fun `removes hmm only in an explicit hesitation position`()
    {
        val hesitation = cleaner.clean("Hmm, I think this is ready.")
        val meaningfulUse = cleaner.clean("The machine made a hmm sound.")

        assertEquals("I think this is ready.", hesitation.cleanedTranscript)
        assertEquals("The machine made a hmm sound.", meaningfulUse.cleanedTranscript)
    }

    @Test
    fun `preserves ambiguous repeated words without restart punctuation`()
    {
        val intentionalExamples = listOf(
            "No no, that is deliberate.",
            "Bye bye for now.",
            "I had had enough.",
            "It was very very difficult."
        )

        intentionalExamples.forEach { source ->
            assertEquals(source, cleaner.clean(source).cleanedTranscript)
        }
    }

    @Test
    fun `converts paired spoken brackets and removes duplicated sentence punctuation`()
    {
        val result = cleaner.clean("My name is Open brackets Buckland. Close brackets.")

        assertEquals("My name is (Buckland).", result.cleanedTranscript)
        assertTrue(result.report.transformations.contains(CleanupTransformation.SPOKEN_FORMATTING_APPLIED))
    }

    @Test
    fun `converts square curly and quoted delimiter pairs`()
    {
        val result = cleaner.clean("Use open square bracket alpha close square bracket, open curly bracket beta close curly bracket, and open quote gamma close quote.")

        assertEquals("Use [alpha], {beta}, and \"gamma\".", result.cleanedTranscript)
    }

    @Test
    fun `preserves unmatched delimiter words as literal speech`()
    {
        val source = "The label says open brackets."

        assertEquals(source, cleaner.clean(source).cleanedTranscript)
    }
}
