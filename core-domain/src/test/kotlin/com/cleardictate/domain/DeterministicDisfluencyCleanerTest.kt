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

        assertEquals("Set it to fifty%.", result.cleanedTranscript)
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
    fun `removes duplicated sentence punctuation across a spoken closing quote`()
    {
        val result = cleaner.clean("He said open quote hello full stop close quote full stop.")

        assertEquals("He said \"hello\".", result.cleanedTranscript)
    }

    @Test
    fun `preserves unmatched delimiter words as literal speech`()
    {
        val source = "The label says open brackets."

        assertEquals(source, cleaner.clean(source).cleanedTranscript)
    }

    @Test
    fun `converts spoken percent and punctuation with written spacing`()
    {
        val result = cleaner.clean("Set it to 50 percent comma, not 60 percent full stop.")

        assertEquals("Set it to 50%, not 60%.", result.cleanedTranscript)
        assertTrue(result.report.transformations.contains(CleanupTransformation.SPOKEN_FORMATTING_APPLIED))
    }

    @Test
    fun `converts spoken symbols using context-appropriate spacing`()
    {
        val result = cleaner.clean(
            "Email alex at sign example dot com comma then use hash tag demo underscore 1 slash 2 and pay pound sign 50 plus dollar sign 5."
        )

        assertEquals("Email alex@example.com, then use #demo_1/2 and pay £50 + \$5.", result.cleanedTranscript)
    }

    @Test
    fun `converts spoken punctuation operators and line structure`()
    {
        val result = cleaner.clean(
            "Is two plus two equals four question mark new paragraph yes exclamation mark new line temperature 20 degree sign C full stop"
        )

        assertEquals("Is two + two = four?\n\nYes!\nTemperature 20°C.", result.cleanedTranscript)
    }

    @Test
    fun `does not convert symbol command words embedded inside longer words`()
    {
        val source = "Percentage points and periodic work remain literal."

        assertEquals(source, cleaner.clean(source).cleanedTranscript)
    }

    @Test
    fun `custom literal rules apply before built-ins and honor spacing`()
    {
        val customCleaner = DeterministicDisfluencyCleaner(
            SpokenFormattingNormalizer(
                listOf(
                    SpokenFormattingRule("per cent", "%", SpokenFormattingSpacing.ATTACH_LEFT, true),
                    SpokenFormattingRule("custom separator", "~", SpokenFormattingSpacing.PRESERVE, false),
                    SpokenFormattingRule("percent", "pct", SpokenFormattingSpacing.PRESERVE, false)
                )
            )
        )

        val result = customCleaner.clean("Use 50 per cent, custom separator 60 percent.")

        assertEquals("Use 50% ~ 60 pct.", result.cleanedTranscript)
    }

    @Test
    fun `custom phrases are literal case-insensitive and cannot match inside words`()
    {
        val customCleaner = DeterministicDisfluencyCleaner(
            SpokenFormattingNormalizer(
                listOf(SpokenFormattingRule("C plus plus", "C++", SpokenFormattingSpacing.PRESERVE, false))
            )
        )

        assertEquals("Use C++ and cplusplus.", customCleaner.clean("Use c PLUS plus and cplusplus.").cleanedTranscript)
    }

    @Test
    fun `custom replacement is not reinterpreted as a built in command`()
    {
        val normalizer = SpokenFormattingNormalizer(
            listOf(SpokenFormattingRule("special rate", "percent", SpokenFormattingSpacing.PRESERVE, false))
        )

        assertEquals("Use percent today.", normalizer.normalize("Use special rate today."))
    }
}
