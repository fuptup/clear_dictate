package com.cleardictate.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Specifies fail-closed validation for language-model transcript polishing.
 */
class TranscriptIntegrityValidatorTest
{
    private val validator = TranscriptIntegrityValidator()
    private val extractor = ProtectedInformationExtractor()

    @Test
    fun `accepts harmless punctuation changes while preserving protected information`()
    {
        val result = validator.validate(
            sourceTranscript = "Email alex@example.com at 10:30 on 12 July 2026; the price is £1,250.50, not £1,500.",
            polishedTranscript = "Email alex@example.com at 10:30 on 12 July 2026. The price is £1250.50, not £1500."
        )

        assertTrue(result.accepted, "Unexpected rejection: ${result.failureReason}")
        assertEquals(IntegrityFailureReason.NONE, result.failureReason)
    }

    @Test
    fun `rejects a changed number`()
    {
        val result = validator.validate(
            sourceTranscript = "Use version 2.2, not 2.3.",
            polishedTranscript = "Use version 2.3, not 2.3."
        )

        assertFalse(result.accepted)
        assertEquals(IntegrityFailureReason.PROTECTED_VALUE_CHANGED, result.failureReason)
    }

    @Test
    fun `rejects a removed negation`()
    {
        val result = validator.validate(
            sourceTranscript = "Do not publish the draft.",
            polishedTranscript = "Publish the draft."
        )

        assertFalse(result.accepted)
        assertEquals(IntegrityFailureReason.NEGATION_CHANGED, result.failureReason)
    }

    @Test
    fun `rejects model commentary and unrequested markup`()
    {
        val commentaryResult = validator.validate("Please review this.", "Here is the edited transcript: Please review this.")
        val markupResult = validator.validate("Please review this.", "<answer>Please review this.</answer>")

        assertEquals(IntegrityFailureReason.MODEL_COMMENTARY, commentaryResult.failureReason)
        assertEquals(IntegrityFailureReason.UNREQUESTED_MARKUP, markupResult.failureReason)
    }

    @Test
    fun `rejects an implausibly expanded result`()
    {
        val result = validator.validate(
            sourceTranscript = "Send it today.",
            polishedTranscript = "Please carefully prepare the complete material and make absolutely certain that the entire package is formally sent to everyone before the end of the business day today."
        )

        assertFalse(result.accepted)
        assertEquals(IntegrityFailureReason.IMPLAUSIBLE_EXPANSION, result.failureReason)
    }

    @Test
    fun `protects sentence-final numbers compact identifiers month-first dates and short times`()
    {
        val values = extractor.extract("Use AB123 on July 30, 2026 at 7 pm. Keep 42.")
            .map { information -> information.normalizedValue }

        assertTrue(values.contains("ab123"))
        assertTrue(values.contains("july 30, 2026"))
        assertTrue(values.contains("7 pm"))
        assertTrue(values.contains("42"))
    }

    @Test
    fun `protects currency and percentage symbols without accepting longer unit names`()
    {
        val values = extractor.extract("Charge £12.50 or 15 EUR, then add 20% but ignore 30 percentagePoints.")
            .map { information -> information.normalizedValue }

        assertTrue(values.contains("£12.50"))
        assertTrue(values.contains("15 eur"))
        assertTrue(values.contains("20%"))
        assertFalse(values.contains("30 percentage"))
    }

    @Test
    fun `protects typographic-apostrophe negations`()
    {
        val result = validator.validate(
            sourceTranscript = "We can’t publish this.",
            polishedTranscript = "We can publish this."
        )

        assertEquals(IntegrityFailureReason.NEGATION_CHANGED, result.failureReason)
    }

    @Test
    fun `rejects negation moved to a different protected value`()
    {
        val result = validator.validate(
            sourceTranscript = "Use 2.2, not 2.3.",
            polishedTranscript = "Do not use 2.2; use 2.3."
        )

        assertEquals(IntegrityFailureReason.NEGATION_CHANGED, result.failureReason)
    }

    @Test
    fun `protects complete quoted text even when it contains a protected time`()
    {
        val result = validator.validate(
            sourceTranscript = """Keep "Call Alice at 10:30" exactly.""",
            polishedTranscript = """Keep "Email Bob at 10:30" exactly."""
        )

        assertEquals(IntegrityFailureReason.PROTECTED_VALUE_CHANGED, result.failureReason)
    }

    @Test
    fun `rejects removed uncertainty and changed capitalized terms`()
    {
        val uncertaintyResult = validator.validate(
            sourceTranscript = "Alice may approve the release.",
            polishedTranscript = "Alice will approve the release."
        )
        val nameResult = validator.validate(
            sourceTranscript = "Email Alice tomorrow.",
            polishedTranscript = "Email Bob tomorrow."
        )

        assertEquals(IntegrityFailureReason.PROTECTED_VALUE_CHANGED, uncertaintyResult.failureReason)
        assertEquals(IntegrityFailureReason.PROTECTED_VALUE_CHANGED, nameResult.failureReason)
    }

    @Test
    fun `rejects an answer that replaces an editing request`()
    {
        val result = validator.validate(
            sourceTranscript = "Tell me the capital of France.",
            polishedTranscript = "Paris is the capital of France."
        )

        assertEquals(IntegrityFailureReason.ANSWERED_TRANSCRIPT, result.failureReason)
    }

    @Test
    fun `rejects changed markup even when source already contains markup`()
    {
        val result = validator.validate(
            sourceTranscript = "Keep <code>AB123</code>.",
            polishedTranscript = "Keep <answer>AB123</answer>."
        )

        assertEquals(IntegrityFailureReason.UNREQUESTED_MARKUP, result.failureReason)
    }

    @Test
    fun `rejects removal or reordering of explicit structural delimiters`()
    {
        val removedResult = validator.validate("My name is (Buckland).", "My name is Buckland.")
        val reorderedResult = validator.validate("Use [alpha] then {beta}.", "Use ]alpha[ then {beta}.")

        assertEquals(IntegrityFailureReason.DELIMITER_CHANGED, removedResult.failureReason)
        assertEquals(IntegrityFailureReason.DELIMITER_CHANGED, reorderedResult.failureReason)
    }

    @Test
    fun `rejects removal or replacement of explicit nonverbal symbols`()
    {
        val removedResult = validator.validate("Set it to 50%.", "Set it to 50 percent.")
        val replacedResult = validator.validate("Email alex@example.com.", "Email alex at example.com.")

        assertEquals(IntegrityFailureReason.DELIMITER_CHANGED, removedResult.failureReason)
        assertEquals(IntegrityFailureReason.DELIMITER_CHANGED, replacedResult.failureReason)
    }
}
