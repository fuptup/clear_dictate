package com.cleardictate.domain

import java.text.Normalizer
import java.util.Locale

/**
 * Categorizes information that polishing is not allowed to remove, change, duplicate, or reorder.
 */
enum class ProtectedInformationType
{
    EMAIL_ADDRESS,
    WEB_ADDRESS,
    FILE_PATH,
    CURRENCY_AMOUNT,
    PERCENTAGE,
    DATE,
    TIME,
    VERSION,
    TELEPHONE_SEQUENCE,
    NUMBER_WITH_UNIT,
    ALPHANUMERIC_IDENTIFIER,
    NUMBER,
    NEGATION,
    QUOTED_TEXT
}

/**
 * Retains a protected value's type, normalized comparison value, and source location.
 */
data class ProtectedInformation(
    val type: ProtectedInformationType,
    val originalValue: String,
    val normalizedValue: String,
    val startIndex: Int,
    val endIndexExclusive: Int
)

/**
 * Extracts protected information in deterministic source order while preventing overlapping matches.
 */
class ProtectedInformationExtractor
{
    private data class ExtractionRule(
        val type: ProtectedInformationType,
        val pattern: Regex
    )

    private val extractionRules = listOf(
        ExtractionRule(ProtectedInformationType.EMAIL_ADDRESS, Regex("""(?iu)\b[\w.!#$%&'*+/=?^`{|}~-]+@[\w.-]+\.[\p{L}]{2,}\b""")),
        ExtractionRule(ProtectedInformationType.WEB_ADDRESS, Regex("""(?iu)\b(?:https?://|www\.)[^\s<>()]+""")),
        ExtractionRule(ProtectedInformationType.FILE_PATH, Regex("""(?iu)(?:\b[A-Z]:\\|/)(?:[^\s<>:"|?*]+[\\/])*[^\s<>:"|?*]*""")),
        ExtractionRule(ProtectedInformationType.CURRENCY_AMOUNT, Regex("""(?iu)(?:[$£€]\s*[+-]?\d[\d,]*(?:\.\d+)?|[+-]?\d[\d,]*(?:\.\d+)?\s*(?:USD|GBP|EUR))\b?""")),
        ExtractionRule(ProtectedInformationType.PERCENTAGE, Regex("""(?iu)[+-]?\d[\d,]*(?:\.\d+)?\s*(?:%|percent)\b?""")),
        ExtractionRule(ProtectedInformationType.DATE, Regex("""(?iu)\b(?:\d{1,2}[-/.]\d{1,2}[-/.]\d{2,4}|\d{1,2}\s+(?:January|February|March|April|May|June|July|August|September|October|November|December)\s+\d{4})\b""")),
        ExtractionRule(ProtectedInformationType.TIME, Regex("""(?iu)\b\d{1,2}:\d{2}(?::\d{2})?\s*(?:am|pm)?\b""")),
        ExtractionRule(ProtectedInformationType.VERSION, Regex("""(?iu)\b(?:v(?:ersion)?\s*)?\d+(?:\.\d+){1,3}(?:[-+][\w.-]+)?\b""")),
        ExtractionRule(ProtectedInformationType.TELEPHONE_SEQUENCE, Regex("""(?x)(?<!\w)(?:\+?\d[\s().-]*){7,15}(?!\w)""")),
        ExtractionRule(ProtectedInformationType.NUMBER_WITH_UNIT, Regex("""(?iu)\b[+-]?\d[\d,]*(?:\.\d+)?\s*(?:kg|g|mg|lb|oz|km|m|cm|mm|mi|ft|in|l|ml|°c|°f|hz|khz|mhz|ghz|gb|mb|kb)\b""")),
        ExtractionRule(ProtectedInformationType.ALPHANUMERIC_IDENTIFIER, Regex("""(?iu)\b(?=[\p{L}\p{N}-]*\p{L})(?=[\p{L}\p{N}-]*\d)[\p{L}\p{N}]+(?:-[\p{L}\p{N}]+)+\b""")),
        ExtractionRule(ProtectedInformationType.NUMBER, Regex("""(?<![\p{L}\p{N}.])[+-]?\d[\d,]*(?:\.\d+)?(?![\p{L}\p{N}.])""")),
        ExtractionRule(ProtectedInformationType.NEGATION, Regex("""(?iu)\b(?:not|never|no|cannot|can't|won't|didn't|isn't|aren't|wasn't|weren't|don't|doesn't|couldn't|shouldn't|wouldn't)\b""")),
        ExtractionRule(ProtectedInformationType.QUOTED_TEXT, Regex("""(?s)(?:"[^"\r\n]+"|'[^'\r\n]+')"""))
    )

    /**
     * Applies higher-specificity rules first, then returns accepted matches in source order.
     */
    fun extract(transcript: String): List<ProtectedInformation>
    {
        val acceptedMatches = mutableListOf<ProtectedInformation>()

        for (extractionRule in extractionRules)
        {
            for (match in extractionRule.pattern.findAll(transcript))
            {
                val startIndex = match.range.first
                val endIndexExclusive = match.range.last + 1
                val overlapsExistingMatch = acceptedMatches.any {
                    startIndex < it.endIndexExclusive && endIndexExclusive > it.startIndex
                }

                if (!overlapsExistingMatch)
                {
                    acceptedMatches.add(
                        ProtectedInformation(
                            type = extractionRule.type,
                            originalValue = match.value,
                            normalizedValue = normalizeProtectedValue(extractionRule.type, match.value),
                            startIndex = startIndex,
                            endIndexExclusive = endIndexExclusive
                        )
                    )
                }
            }
        }

        return acceptedMatches.sortedWith(compareBy(ProtectedInformation::startIndex, ProtectedInformation::endIndexExclusive))
    }

    private fun normalizeProtectedValue(type: ProtectedInformationType, value: String): String
    {
        var normalizedValue = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .replace('’', '\'')
            .lowercase(Locale.ROOT)
            .trim()

        if (
            type == ProtectedInformationType.CURRENCY_AMOUNT ||
            type == ProtectedInformationType.PERCENTAGE ||
            type == ProtectedInformationType.VERSION ||
            type == ProtectedInformationType.NUMBER_WITH_UNIT ||
            type == ProtectedInformationType.NUMBER
        )
        {
            normalizedValue = normalizedValue.replace(",", "")
        }

        normalizedValue = normalizedValue.replace(Regex("""\s+"""), " ")
        return normalizedValue
    }
}

/**
 * Enumerates non-sensitive reasons why a polished transcript was rejected.
 */
enum class IntegrityFailureReason
{
    NONE,
    EMPTY_RESULT,
    PROTECTED_VALUE_CHANGED,
    NEGATION_CHANGED,
    MODEL_COMMENTARY,
    UNREQUESTED_MARKUP,
    IMPLAUSIBLE_EXPANSION
}

/**
 * Returns a fail-closed decision without retaining transcript contents in the reason.
 */
data class TranscriptIntegrityResult(
    val accepted: Boolean,
    val failureReason: IntegrityFailureReason
)

/**
 * Reduces common small-language-model meaning drift by validating protected information and shape.
 *
 * This validator is a safeguard rather than a proof of semantic equivalence. Rejected results must
 * fall back to the exact deterministic Clean-mode output.
 */
class TranscriptIntegrityValidator(
    private val protectedInformationExtractor: ProtectedInformationExtractor = ProtectedInformationExtractor()
)
{
    private val commentaryPatterns = listOf(
        Regex("""(?iu)^\s*(?:here(?:'s| is)|edited transcript|the edited|sure[,!:])"""),
        Regex("""(?iu)\b(?:as an ai|i (?:have|made|edited)|explanation:)\b""")
    )
    private val markupPattern = Regex("""(?s)<[^>]+>|```|^\s*#{1,6}\s+""")

    /**
     * Applies inexpensive shape checks first, then compares protected values in order and multiplicity.
     */
    fun validate(sourceTranscript: String, polishedTranscript: String): TranscriptIntegrityResult
    {
        if (polishedTranscript.isBlank())
        {
            return rejected(IntegrityFailureReason.EMPTY_RESULT)
        }

        if (commentaryPatterns.any { it.containsMatchIn(polishedTranscript) })
        {
            return rejected(IntegrityFailureReason.MODEL_COMMENTARY)
        }

        if (markupPattern.containsMatchIn(polishedTranscript) && !markupPattern.containsMatchIn(sourceTranscript))
        {
            return rejected(IntegrityFailureReason.UNREQUESTED_MARKUP)
        }

        if (isImplausiblyExpanded(sourceTranscript, polishedTranscript))
        {
            return rejected(IntegrityFailureReason.IMPLAUSIBLE_EXPANSION)
        }

        val sourceProtectedInformation = protectedInformationExtractor.extract(sourceTranscript)
        val polishedProtectedInformation = protectedInformationExtractor.extract(polishedTranscript)
        val sourceNegations = sourceProtectedInformation.filter { it.type == ProtectedInformationType.NEGATION }
        val polishedNegations = polishedProtectedInformation.filter { it.type == ProtectedInformationType.NEGATION }

        if (normalizedSequence(sourceNegations) != normalizedSequence(polishedNegations))
        {
            return rejected(IntegrityFailureReason.NEGATION_CHANGED)
        }

        val sourceNonNegationValues = sourceProtectedInformation.filter { it.type != ProtectedInformationType.NEGATION }
        val polishedNonNegationValues = polishedProtectedInformation.filter { it.type != ProtectedInformationType.NEGATION }

        if (normalizedSequence(sourceNonNegationValues) != normalizedSequence(polishedNonNegationValues))
        {
            return rejected(IntegrityFailureReason.PROTECTED_VALUE_CHANGED)
        }

        return TranscriptIntegrityResult(accepted = true, failureReason = IntegrityFailureReason.NONE)
    }

    private fun normalizedSequence(values: List<ProtectedInformation>): List<Pair<ProtectedInformationType, String>>
    {
        return values.map {
            it.type to it.normalizedValue
        }
    }

    private fun isImplausiblyExpanded(sourceTranscript: String, polishedTranscript: String): Boolean
    {
        val sourceWordCount = sourceTranscript.trim().split(Regex("""\s+""")).count { it.isNotBlank() }
        val polishedWordCount = polishedTranscript.trim().split(Regex("""\s+""")).count { it.isNotBlank() }
        val permittedWordCount = (sourceWordCount * 2) + 8
        return polishedWordCount > permittedWordCount
    }

    private fun rejected(reason: IntegrityFailureReason): TranscriptIntegrityResult
    {
        return TranscriptIntegrityResult(accepted = false, failureReason = reason)
    }
}
