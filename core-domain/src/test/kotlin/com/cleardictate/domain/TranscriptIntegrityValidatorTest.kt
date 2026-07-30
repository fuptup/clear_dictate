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

    @Test
    fun `accepts harmless punctuation changes while preserving protected information`()
    {
        val result = validator.validate(
            sourceTranscript = "Email alex@example.com at 10:30 on 12 July 2026; the price is £1,250.50, not £1,500.",
            polishedTranscript = "Email alex@example.com at 10:30 on 12 July 2026. The price is £1250.50, not £1500."
        )

        assertTrue(result.accepted)
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
}
