package com.cleardictate.domain

import java.text.Normalizer
import java.util.Locale

/**
 * Identifies every deterministic transformation that Clean mode may report to callers.
 */
enum class CleanupTransformation
{
    UNICODE_NORMALIZED,
    WHITESPACE_NORMALIZED,
    HESITATIONS_REMOVED,
    REPEATED_WORDS_COLLAPSED,
    REPEATED_PHRASES_COLLAPSED,
    PUNCTUATION_REPAIRED,
    CAPITALIZATION_RESTORED
}

/**
 * Describes exactly which conservative transformations were applied to a transcript.
 */
data class CleanupReport(
    val transformations: Set<CleanupTransformation>
)

/**
 * Keeps the recognizer output available beside the derived Clean-mode transcript.
 */
data class TranscriptCleanupResult(
    val originalTranscript: String,
    val cleanedTranscript: String,
    val report: CleanupReport
)

/**
 * Performs the deterministic Clean-mode pipeline as explicit, independently readable stages.
 *
 * The cleaner deliberately leaves ambiguous spoken repetition intact. It is intended to remove
 * obvious recognition duplication and hesitation tokens, not infer the speaker's intent.
 */
class DeterministicDisfluencyCleaner
{
    private val hesitationTokenPattern = Regex(
        pattern = """(?iu)(?<![\p{L}\p{N}])(?:um+|uh+|ah|erm|hmm)(?![\p{L}\p{N}])"""
    )
    private val repeatedPhrasePattern = Regex(
        pattern = """(?iu)\b((?:[\p{L}\p{N}][\p{L}\p{N}'’.-]*\s+){1,4}[\p{L}\p{N}][\p{L}\p{N}'’.-]*)\s*,\s*\1\b"""
    )
    private val repeatedWordPattern = Regex(
        pattern = """(?iu)\b([\p{L}\p{N}][\p{L}\p{N}'’.-]*)\b(\s*,\s*|\s+)\1\b"""
    )
    private val intentionalRepetitionWords = setOf(
        "very",
        "really",
        "so",
        "many",
        "much",
        "far",
        "long",
        "little"
    )

    /**
     * Runs every deterministic cleanup stage in a fixed order and reports material changes.
     */
    fun clean(rawTranscript: String): TranscriptCleanupResult
    {
        val appliedTransformations = linkedSetOf<CleanupTransformation>()

        var currentTranscript = applyStage(
            transcript = rawTranscript,
            transformation = CleanupTransformation.UNICODE_NORMALIZED,
            appliedTransformations = appliedTransformations,
            operation = ::normalizeUnicode
        )
        currentTranscript = applyStage(
            transcript = currentTranscript,
            transformation = CleanupTransformation.WHITESPACE_NORMALIZED,
            appliedTransformations = appliedTransformations,
            operation = ::normalizeWhitespace
        )
        currentTranscript = applyStage(
            transcript = currentTranscript,
            transformation = CleanupTransformation.HESITATIONS_REMOVED,
            appliedTransformations = appliedTransformations,
            operation = ::removeStandaloneHesitations
        )
        currentTranscript = applyStage(
            transcript = currentTranscript,
            transformation = CleanupTransformation.REPEATED_PHRASES_COLLAPSED,
            appliedTransformations = appliedTransformations,
            operation = ::collapseExactRepeatedShortPhrases
        )
        currentTranscript = applyStage(
            transcript = currentTranscript,
            transformation = CleanupTransformation.REPEATED_WORDS_COLLAPSED,
            appliedTransformations = appliedTransformations,
            operation = ::collapseImmediateRepeatedWords
        )
        currentTranscript = applyStage(
            transcript = currentTranscript,
            transformation = CleanupTransformation.PUNCTUATION_REPAIRED,
            appliedTransformations = appliedTransformations,
            operation = ::repairPunctuationSpacing
        )
        currentTranscript = applyStage(
            transcript = currentTranscript,
            transformation = CleanupTransformation.CAPITALIZATION_RESTORED,
            appliedTransformations = appliedTransformations,
            operation = ::restoreConservativeSentenceCapitalization
        )

        return TranscriptCleanupResult(
            originalTranscript = rawTranscript,
            cleanedTranscript = currentTranscript,
            report = CleanupReport(appliedTransformations)
        )
    }

    private fun applyStage(
        transcript: String,
        transformation: CleanupTransformation,
        appliedTransformations: MutableSet<CleanupTransformation>,
        operation: (String) -> String
    ): String
    {
        val transformedTranscript = operation(transcript)

        if (transformedTranscript != transcript)
        {
            appliedTransformations.add(transformation)
        }

        return transformedTranscript
    }

    private fun normalizeUnicode(transcript: String): String
    {
        return Normalizer.normalize(transcript, Normalizer.Form.NFKC)
    }

    private fun normalizeWhitespace(transcript: String): String
    {
        return transcript.replace(Regex("""\s+"""), " ").trim()
    }

    private fun removeStandaloneHesitations(transcript: String): String
    {
        return hesitationTokenPattern.replace(transcript)
        {
            ""
        }
    }

    private fun collapseExactRepeatedShortPhrases(transcript: String): String
    {
        var currentTranscript = transcript

        while (true)
        {
            val repeatedPhrase = repeatedPhrasePattern.find(currentTranscript) ?: break
            val retainedPhrase = repeatedPhrase.groupValues[1]
            currentTranscript = currentTranscript.replaceRange(repeatedPhrase.range, retainedPhrase)
        }

        return currentTranscript
    }

    private fun collapseImmediateRepeatedWords(transcript: String): String
    {
        var currentTranscript = transcript
        var searchStartIndex = 0

        while (searchStartIndex < currentTranscript.length)
        {
            val repetition = repeatedWordPattern.find(currentTranscript, searchStartIndex) ?: break
            val repeatedWord = repetition.groupValues[1]
            val separator = repetition.groupValues[2]
            val isPunctuationSeparated = separator.contains(',')
            val isIntentionalEmphasis = isPunctuationSeparated &&
                intentionalRepetitionWords.contains(repeatedWord.lowercase(Locale.ROOT))

            if (isIntentionalEmphasis)
            {
                searchStartIndex = repetition.range.last + 1
                continue
            }

            currentTranscript = currentTranscript.replaceRange(repetition.range, repeatedWord)
            searchStartIndex = repetition.range.first + repeatedWord.length
        }

        return currentTranscript
    }

    private fun repairPunctuationSpacing(transcript: String): String
    {
        var repairedTranscript = transcript
        repairedTranscript = repairedTranscript.replace(Regex("""\s+([,.;:!?])"""), "$1")
        repairedTranscript = repairedTranscript.replace(Regex("""([,;:])(?=\S)"""), "$1 ")
        repairedTranscript = repairedTranscript.replace(Regex("""!{2,}"""), "!")
        repairedTranscript = repairedTranscript.replace(Regex("""\?{2,}"""), "?")
        repairedTranscript = repairedTranscript.replace(Regex("""\.{4,}"""), "...")
        repairedTranscript = repairedTranscript.replace(Regex(""",\s*,"""), " ")
        repairedTranscript = repairedTranscript.replace(Regex(""",\s*([.!?])"""), "$1")
        repairedTranscript = repairedTranscript.replace(Regex("""^\s*[,;:]\s*"""), "")
        repairedTranscript = repairedTranscript.replace(Regex("""\s+"""), " ")
        return repairedTranscript.trim()
    }

    private fun restoreConservativeSentenceCapitalization(transcript: String): String
    {
        val characters = transcript.toCharArray()
        var capitalizeNextLetter = true

        for (characterIndex in characters.indices)
        {
            val currentCharacter = characters[characterIndex]

            if (capitalizeNextLetter && currentCharacter.isLetter())
            {
                characters[characterIndex] = currentCharacter.titlecaseChar()
                capitalizeNextLetter = false
            }
            else if (currentCharacter == '.' || currentCharacter == '!' || currentCharacter == '?')
            {
                capitalizeNextLetter = true
            }
            else if (currentCharacter.isLetterOrDigit())
            {
                capitalizeNextLetter = false
            }
        }

        return String(characters)
    }
}
