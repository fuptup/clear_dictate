package com.cleardictate.domain

/**
 * Controls how whitespace surrounding a custom spoken rule is retained in written output.
 */
enum class SpokenFormattingSpacing
{
    PRESERVE,
    ATTACH_LEFT,
    ATTACH_RIGHT,
    ATTACH_BOTH
}

/**
 * Defines one literal, case-insensitive spoken phrase replacement without exposing regular-expression execution to users.
 */
data class SpokenFormattingRule(
    val spokenPhrase: String,
    val replacement: String,
    val spacing: SpokenFormattingSpacing,
    val consumesRecognizerPunctuation: Boolean
)
{
    init
    {
        require(spokenPhrase.isNotBlank()) { "A spoken formatting phrase cannot be blank." }
        require(replacement.isNotEmpty()) { "A spoken formatting replacement cannot be empty." }
    }
}

/**
 * Converts explicit spoken formatting commands into written delimiters, symbols, punctuation, and line structure.
 */
class SpokenFormattingNormalizer(customRules: List<SpokenFormattingRule> = emptyList())
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
    private val customTokenRules = customRules.sortedByDescending { rule -> rule.spokenPhrase.length }.map(::spokenLiteralToken)
    private val builtInTokenRules = listOf(
        spokenToken("new paragraph", "\n\n", SpokenFormattingSpacing.ATTACH_BOTH),
        spokenToken("new line", "\n", SpokenFormattingSpacing.ATTACH_BOTH),
        spokenToken("full stop|period", ".", consumesRecognizerPunctuation = true),
        spokenToken("question mark", "?", consumesRecognizerPunctuation = true),
        spokenToken("exclamation (?:mark|point)", "!", consumesRecognizerPunctuation = true),
        spokenToken("comma", ",", consumesRecognizerPunctuation = true),
        spokenToken("semi[ -]?colon", ";", consumesRecognizerPunctuation = true),
        spokenToken("colon", ":", consumesRecognizerPunctuation = true),
        spokenToken("ellipsis", "...", consumesRecognizerPunctuation = true),
        spokenToken("percent(?: sign)?", "%", SpokenFormattingSpacing.ATTACH_LEFT),
        spokenToken("at sign", "@", SpokenFormattingSpacing.ATTACH_BOTH),
        spokenToken("hash(?: sign| tag)?|number sign|hashtag", "#", SpokenFormattingSpacing.ATTACH_RIGHT),
        spokenToken("ampersand", "&"),
        spokenToken("dollar sign", "\$", SpokenFormattingSpacing.ATTACH_RIGHT),
        spokenToken("pound sign", "£", SpokenFormattingSpacing.ATTACH_RIGHT),
        spokenToken("euro sign", "€", SpokenFormattingSpacing.ATTACH_RIGHT),
        spokenToken("degree (?:sign|symbol)", "°", SpokenFormattingSpacing.ATTACH_BOTH),
        spokenToken("plus(?: sign)?", "+"),
        spokenToken("minus(?: sign)?", "-"),
        spokenToken("equals(?: sign)?", "="),
        spokenToken("less than(?: sign)?", "<"),
        spokenToken("greater than(?: sign)?", ">"),
        spokenToken("forward slash|slash", "/", SpokenFormattingSpacing.ATTACH_BOTH),
        spokenToken("back ?slash", "\\", SpokenFormattingSpacing.ATTACH_BOTH),
        spokenToken("underscore", "_", SpokenFormattingSpacing.ATTACH_BOTH),
        spokenToken("apostrophe", "'", SpokenFormattingSpacing.ATTACH_BOTH),
        spokenToken("hyphen", "-", SpokenFormattingSpacing.ATTACH_BOTH),
        spokenToken("dot", ".", SpokenFormattingSpacing.ATTACH_BOTH, consumesRecognizerPunctuation = true),
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
        val protectedReplacements = mutableListOf<String>()
        customTokenRules.forEach { rule ->
            normalizedTranscript = rule.pattern.replace(normalizedTranscript) {
                val marker = customReplacementMarker(protectedReplacements.size)
                protectedReplacements.add(rule.replacement)
                marker
            }
        }
        builtInTokenRules.forEach { rule ->
            normalizedTranscript = rule.pattern.replace(normalizedTranscript) { rule.replacement }
        }
        protectedReplacements.forEachIndexed { index, replacement ->
            normalizedTranscript = normalizedTranscript.replace(customReplacementMarker(index), replacement)
        }
        return duplicatedSentencePunctuationPattern.replace(normalizedTranscript, "$2$3")
    }

    /**
     * Produces a private-use marker that prevents a custom replacement from being interpreted as another spoken command.
     */
    private fun customReplacementMarker(index: Int): String
    {
        return "\uE000$index\uE001"
    }

    private fun spokenToken(
        spokenPattern: String,
        replacement: String,
        spacing: SpokenFormattingSpacing = SpokenFormattingSpacing.PRESERVE,
        consumesRecognizerPunctuation: Boolean = false
    ): SpokenTokenRule
    {
        return spokenToken(spokenPattern, replacement, spacing, consumesRecognizerPunctuation, includesBoundaries = false)
    }

    /**
     * Escapes every user-provided word before joining it with flexible horizontal whitespace, ensuring custom rules remain literal.
     */
    private fun spokenLiteralToken(rule: SpokenFormattingRule): SpokenTokenRule
    {
        val literalPhrasePattern = rule.spokenPhrase.trim().split(Regex("""\s+""")).joinToString("[^\\S\\r\\n]+", transform = Regex::escape)
        val boundedPattern = "(?<![\\p{L}\\p{N}])(?:$literalPhrasePattern)(?![\\p{L}\\p{N}])"
        return spokenToken(
            spokenPattern = boundedPattern,
            replacement = rule.replacement,
            spacing = rule.spacing,
            consumesRecognizerPunctuation = rule.consumesRecognizerPunctuation,
            includesBoundaries = true
        )
    }

    private fun spokenToken(
        spokenPattern: String,
        replacement: String,
        spacing: SpokenFormattingSpacing,
        consumesRecognizerPunctuation: Boolean,
        includesBoundaries: Boolean
    ): SpokenTokenRule
    {
        val leadingSpacingPattern = if (spacing == SpokenFormattingSpacing.ATTACH_LEFT || spacing == SpokenFormattingSpacing.ATTACH_BOTH) "[^\\S\\r\\n]*" else ""
        val trailingPunctuationPattern = if (consumesRecognizerPunctuation) "[,.!?;:]?" else ""
        val trailingSpacingPattern = if (spacing == SpokenFormattingSpacing.ATTACH_RIGHT || spacing == SpokenFormattingSpacing.ATTACH_BOTH) "[^\\S\\r\\n]*" else ""
        val boundedSpokenPattern = if (includesBoundaries) spokenPattern else "\\b(?:$spokenPattern)\\b"
        return SpokenTokenRule(Regex("(?iu)$leadingSpacingPattern$boundedSpokenPattern$trailingPunctuationPattern$trailingSpacingPattern"), replacement)
    }

    private data class SpokenDelimiterRule(val pattern: Regex, val openingDelimiter: String, val closingDelimiter: String)
    private data class SpokenTokenRule(val pattern: Regex, val replacement: String)

}
