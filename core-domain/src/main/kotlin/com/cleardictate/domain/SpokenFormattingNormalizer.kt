package com.cleardictate.domain

/**
 * Converts explicitly paired spoken delimiter commands without interpreting unmatched words that may be literal speech.
 */
class SpokenFormattingNormalizer
{
    private val delimiterRules = listOf(
        SpokenDelimiterRule(
            Regex("""(?iu)\bopen (?:round )?(?:bracket|brackets|parenthesis|parentheses)\b[,:]?\s*(.+?)\s*\bclose (?:round )?(?:bracket|brackets|parenthesis|parentheses)\b"""),
            "(",
            ")"
        ),
        SpokenDelimiterRule(
            Regex("""(?iu)\bopen square (?:bracket|brackets)\b[,:]?\s*(.+?)\s*\bclose square (?:bracket|brackets)\b"""),
            "[",
            "]"
        ),
        SpokenDelimiterRule(
            Regex("""(?iu)\bopen (?:curly (?:bracket|brackets)|brace|braces)\b[,:]?\s*(.+?)\s*\bclose (?:curly (?:bracket|brackets)|brace|braces)\b"""),
            "{",
            "}"
        ),
        SpokenDelimiterRule(
            Regex("""(?iu)\bopen (?:double )?(?:quote|quotes|quotation mark)\b[,:]?\s*(.+?)\s*\bclose (?:double )?(?:quote|quotes|quotation mark)\b"""),
            "\"",
            "\""
        )
    )
    private val duplicatedSentencePunctuationPattern = Regex("""([.!?])([)\]}])([.!?])""")

    /**
     * Applies only complete open/close pairs, then removes recognizer punctuation duplicated across a closing bracket.
     */
    fun normalize(transcript: String): String
    {
        var normalizedTranscript = transcript
        delimiterRules.forEach { rule ->
            normalizedTranscript = rule.pattern.replace(normalizedTranscript) { match ->
                rule.openingDelimiter + match.groupValues[1].trim() + rule.closingDelimiter
            }
        }
        return duplicatedSentencePunctuationPattern.replace(normalizedTranscript, "$2$3")
    }

    private data class SpokenDelimiterRule(val pattern: Regex, val openingDelimiter: String, val closingDelimiter: String)
}
