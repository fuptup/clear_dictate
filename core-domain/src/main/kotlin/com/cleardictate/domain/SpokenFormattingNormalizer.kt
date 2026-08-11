package com.cleardictate.domain

/**
 * Converts explicit spoken formatting commands into written delimiters, symbols, punctuation, and line structure.
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
    private val tokenRules = listOf(
        spokenToken("new paragraph", "\n\n", SpokenTokenSpacing.TIGHT_BOTH),
        spokenToken("new line", "\n", SpokenTokenSpacing.TIGHT_BOTH),
        spokenToken("full stop|period", ".", consumesRecognizerPunctuation = true),
        spokenToken("question mark", "?", consumesRecognizerPunctuation = true),
        spokenToken("exclamation (?:mark|point)", "!", consumesRecognizerPunctuation = true),
        spokenToken("comma", ",", consumesRecognizerPunctuation = true),
        spokenToken("semi[ -]?colon", ";", consumesRecognizerPunctuation = true),
        spokenToken("colon", ":", consumesRecognizerPunctuation = true),
        spokenToken("ellipsis", "...", consumesRecognizerPunctuation = true),
        spokenToken("percent(?: sign)?", "%", SpokenTokenSpacing.TIGHT_LEFT),
        spokenToken("at sign", "@", SpokenTokenSpacing.TIGHT_BOTH),
        spokenToken("hash(?: sign| tag)?|number sign|hashtag", "#", SpokenTokenSpacing.TIGHT_RIGHT),
        spokenToken("ampersand", "&"),
        spokenToken("dollar sign", "\$", SpokenTokenSpacing.TIGHT_RIGHT),
        spokenToken("pound sign", "£", SpokenTokenSpacing.TIGHT_RIGHT),
        spokenToken("euro sign", "€", SpokenTokenSpacing.TIGHT_RIGHT),
        spokenToken("degree (?:sign|symbol)", "°", SpokenTokenSpacing.TIGHT_BOTH),
        spokenToken("plus(?: sign)?", "+"),
        spokenToken("minus(?: sign)?", "-"),
        spokenToken("equals(?: sign)?", "="),
        spokenToken("less than(?: sign)?", "<"),
        spokenToken("greater than(?: sign)?", ">"),
        spokenToken("forward slash|slash", "/", SpokenTokenSpacing.TIGHT_BOTH),
        spokenToken("back ?slash", "\\", SpokenTokenSpacing.TIGHT_BOTH),
        spokenToken("underscore", "_", SpokenTokenSpacing.TIGHT_BOTH),
        spokenToken("apostrophe", "'", SpokenTokenSpacing.TIGHT_BOTH),
        spokenToken("hyphen", "-", SpokenTokenSpacing.TIGHT_BOTH),
        spokenToken("dot", ".", SpokenTokenSpacing.TIGHT_BOTH, consumesRecognizerPunctuation = true),
        spokenToken("asterisk|star symbol", "*"),
        spokenToken("vertical bar|pipe symbol", "|"),
        spokenToken("caret", "^")
    )
    private val duplicatedSentencePunctuationPattern = Regex("""[^\S\r\n]*([.!?])([)\]}"])[^\S\r\n]*([.!?])""")

    /**
     * Applies paired delimiters first, then standalone commands and recognizer-punctuation repair in deterministic order.
     */
    fun normalize(transcript: String): String
    {
        var normalizedTranscript = transcript
        delimiterRules.forEach { rule ->
            normalizedTranscript = rule.pattern.replace(normalizedTranscript) { match ->
                rule.openingDelimiter + match.groupValues[1].trim() + rule.closingDelimiter
            }
        }
        tokenRules.forEach { rule ->
            normalizedTranscript = rule.pattern.replace(normalizedTranscript) { rule.replacement }
        }
        return duplicatedSentencePunctuationPattern.replace(normalizedTranscript, "$2$3")
    }

    private fun spokenToken(
        spokenPattern: String,
        replacement: String,
        spacing: SpokenTokenSpacing = SpokenTokenSpacing.PRESERVE,
        consumesRecognizerPunctuation: Boolean = false
    ): SpokenTokenRule
    {
        val leadingSpacingPattern = if (spacing == SpokenTokenSpacing.TIGHT_LEFT || spacing == SpokenTokenSpacing.TIGHT_BOTH) "[^\\S\\r\\n]*" else ""
        val trailingPunctuationPattern = if (consumesRecognizerPunctuation) "[,.!?;:]?" else ""
        val trailingSpacingPattern = if (spacing == SpokenTokenSpacing.TIGHT_RIGHT || spacing == SpokenTokenSpacing.TIGHT_BOTH) "[^\\S\\r\\n]*" else ""
        return SpokenTokenRule(
            Regex("(?iu)$leadingSpacingPattern\\b(?:$spokenPattern)\\b$trailingPunctuationPattern$trailingSpacingPattern"),
            replacement
        )
    }

    private data class SpokenDelimiterRule(val pattern: Regex, val openingDelimiter: String, val closingDelimiter: String)
    private data class SpokenTokenRule(val pattern: Regex, val replacement: String)

    private enum class SpokenTokenSpacing
    {
        PRESERVE,
        TIGHT_LEFT,
        TIGHT_RIGHT,
        TIGHT_BOTH
    }
}
