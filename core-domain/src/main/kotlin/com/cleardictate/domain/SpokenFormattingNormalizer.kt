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
 * Describes one built-in rule in user-facing terms without exposing its regular-expression implementation.
 */
data class BuiltInSpokenFormattingRule(val spokenPhrases: String, val writtenText: String)

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
    private val builtInTokenRules = (BUILT_IN_TOKEN_DEFINITIONS + BUILT_IN_EMOJI_DEFINITIONS).map { definition ->
        spokenToken(definition.spokenPattern, definition.replacement, definition.spacing, definition.consumesRecognizerPunctuation)
    }
    private val duplicatedSentencePunctuationPattern = Regex("""[^\S\r\n]*([.!?])([)\]}"])[^\S\r\n]*([.!?])""")

    /**
     * Protects custom replacements before applying paired delimiters, standalone built-ins, and recognizer-punctuation repair.
     */
    fun normalize(transcript: String): String
    {
        var normalizedTranscript = transcript
        val protectedReplacements = mutableListOf<String>()
        customTokenRules.forEach { rule ->
            normalizedTranscript = rule.pattern.replace(normalizedTranscript) {
                val marker = customReplacementMarker(protectedReplacements.size)
                protectedReplacements.add(rule.replacement)
                marker
            }
        }
        delimiterRules.forEach { rule ->
            normalizedTranscript = rule.pattern.replace(normalizedTranscript) { match ->
                rule.openingDelimiter + match.groupValues[1].trim() + rule.closingDelimiter
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
    private data class BuiltInTokenDefinition(
        val spokenPattern: String,
        val spokenPhrases: String,
        val replacement: String,
        val spacing: SpokenFormattingSpacing = SpokenFormattingSpacing.PRESERVE,
        val consumesRecognizerPunctuation: Boolean = false,
        val displayedWrittenText: String = replacement
    )

    companion object
    {
        private val PAIRED_DELIMITER_RULES = listOf(
            BuiltInSpokenFormattingRule("open round bracket … close round bracket", "(…)"),
            BuiltInSpokenFormattingRule("open square bracket … close square bracket", "[…]"),
            BuiltInSpokenFormattingRule("open curly bracket … close curly bracket", "{…}"),
            BuiltInSpokenFormattingRule("open quote … close quote", "\"…\"")
        )
        private val BUILT_IN_TOKEN_DEFINITIONS = listOf(
            BuiltInTokenDefinition("new paragraph", "new paragraph", "\n\n", SpokenFormattingSpacing.ATTACH_BOTH, displayedWrittenText = "paragraph break"),
            BuiltInTokenDefinition("new line", "new line", "\n", SpokenFormattingSpacing.ATTACH_BOTH, displayedWrittenText = "line break"),
            BuiltInTokenDefinition("full stop|period", "full stop / period", ".", consumesRecognizerPunctuation = true),
            BuiltInTokenDefinition("question mark", "question mark", "?", consumesRecognizerPunctuation = true),
            BuiltInTokenDefinition("exclamation (?:mark|point)", "exclamation mark / exclamation point", "!", consumesRecognizerPunctuation = true),
            BuiltInTokenDefinition("comma", "comma", ",", consumesRecognizerPunctuation = true),
            BuiltInTokenDefinition("semi[ -]?colon", "semicolon / semi colon", ";", consumesRecognizerPunctuation = true),
            BuiltInTokenDefinition("colon", "colon", ":", consumesRecognizerPunctuation = true),
            BuiltInTokenDefinition("ellipsis", "ellipsis", "...", consumesRecognizerPunctuation = true),
            BuiltInTokenDefinition("percent(?: sign)?", "percent / percent sign", "%", SpokenFormattingSpacing.ATTACH_LEFT),
            BuiltInTokenDefinition("at sign", "at sign", "@", SpokenFormattingSpacing.ATTACH_BOTH),
            BuiltInTokenDefinition("hash(?: sign| tag)?|number sign|hashtag", "hash / hash sign / hash tag / number sign / hashtag", "#", SpokenFormattingSpacing.ATTACH_RIGHT),
            BuiltInTokenDefinition("ampersand", "ampersand", "&"),
            BuiltInTokenDefinition("dollar sign", "dollar sign", "\$", SpokenFormattingSpacing.ATTACH_RIGHT),
            BuiltInTokenDefinition("pound sign", "pound sign", "£", SpokenFormattingSpacing.ATTACH_RIGHT),
            BuiltInTokenDefinition("euro sign", "euro sign", "€", SpokenFormattingSpacing.ATTACH_RIGHT),
            BuiltInTokenDefinition("degree (?:sign|symbol)", "degree sign / degree symbol", "°", SpokenFormattingSpacing.ATTACH_BOTH),
            BuiltInTokenDefinition("plus(?: sign)?", "plus / plus sign", "+"),
            BuiltInTokenDefinition("minus(?: sign)?", "minus / minus sign", "-"),
            BuiltInTokenDefinition("equals(?: sign)?", "equals / equals sign", "="),
            BuiltInTokenDefinition("less than(?: sign)?", "less than / less than sign", "<"),
            BuiltInTokenDefinition("greater than(?: sign)?", "greater than / greater than sign", ">"),
            BuiltInTokenDefinition("forward slash|slash", "forward slash / slash", "/", SpokenFormattingSpacing.ATTACH_BOTH),
            BuiltInTokenDefinition("back ?slash", "backslash / back slash", "\\", SpokenFormattingSpacing.ATTACH_BOTH),
            BuiltInTokenDefinition("underscore", "underscore", "_", SpokenFormattingSpacing.ATTACH_BOTH),
            BuiltInTokenDefinition("apostrophe", "apostrophe", "'", SpokenFormattingSpacing.ATTACH_BOTH),
            BuiltInTokenDefinition("hyphen", "hyphen", "-", SpokenFormattingSpacing.ATTACH_BOTH),
            BuiltInTokenDefinition("dot", "dot", ".", SpokenFormattingSpacing.ATTACH_BOTH, consumesRecognizerPunctuation = true),
            BuiltInTokenDefinition("asterisk|star symbol", "asterisk / star symbol", "*"),
            BuiltInTokenDefinition("vertical bar|pipe symbol", "vertical bar / pipe symbol", "|"),
            BuiltInTokenDefinition("caret", "caret", "^")
        )
        private val BUILT_IN_EMOJI_DEFINITIONS = listOf(
            BuiltInTokenDefinition("lol|l[. -]+o[. -]+l|ell oh ell|laugh(?:ing)? out loud", "LOL / ell oh ell / laugh out loud", "😂"),
            BuiltInTokenDefinition("rofl|r[. -]+o[. -]+f[. -]+l|are oh eff ell|rolling on the floor laughing", "ROFL / rolling on the floor laughing", "🤣"),
            BuiltInTokenDefinition("smiley face|smiling face", "smiley face / smiling face", "😊"),
            BuiltInTokenDefinition("happy face", "happy face", "🙂"),
            BuiltInTokenDefinition("grinning face|big grin", "grinning face / big grin", "😀"),
            BuiltInTokenDefinition("winky face|wink face", "winky face / wink face", "😉"),
            BuiltInTokenDefinition("heart eyes", "heart eyes", "😍"),
            BuiltInTokenDefinition("kissy face|blowing a kiss", "kissy face / blowing a kiss", "😘"),
            BuiltInTokenDefinition("tongue[ -]?out face", "tongue-out face", "😛"),
            BuiltInTokenDefinition("silly face", "silly face", "🤪"),
            BuiltInTokenDefinition("thinking face", "thinking face", "🤔"),
            BuiltInTokenDefinition("eye[ -]?roll face", "eye-roll face", "🙄"),
            BuiltInTokenDefinition("surprised face", "surprised face", "😮"),
            BuiltInTokenDefinition("shocked face", "shocked face", "😱"),
            BuiltInTokenDefinition("sad face", "sad face", "😢"),
            BuiltInTokenDefinition("crying face", "crying face", "😭"),
            BuiltInTokenDefinition("angry face", "angry face", "😠"),
            BuiltInTokenDefinition("swearing face", "swearing face", "🤬"),
            BuiltInTokenDefinition("embarrassed face", "embarrassed face", "😳"),
            BuiltInTokenDefinition("pleading face|puppy[ -]?dog eyes", "pleading face / puppy-dog eyes", "🥺"),
            BuiltInTokenDefinition("cool face|sunglasses face", "cool face / sunglasses face", "😎"),
            BuiltInTokenDefinition("sleepy face", "sleepy face", "😴"),
            BuiltInTokenDefinition("sick face", "sick face", "🤢"),
            BuiltInTokenDefinition("party face", "party face", "🥳"),
            BuiltInTokenDefinition("devil face", "devil face", "😈"),
            BuiltInTokenDefinition("angel face", "angel face", "😇"),
            BuiltInTokenDefinition("skull emoji", "skull emoji", "💀"),
            BuiltInTokenDefinition("poop emoji", "poop emoji", "💩"),
            BuiltInTokenDefinition("thumbs up", "thumbs up", "👍"),
            BuiltInTokenDefinition("thumbs down", "thumbs down", "👎"),
            BuiltInTokenDefinition("clapping hands", "clapping hands", "👏"),
            BuiltInTokenDefinition("praying hands", "praying hands", "🙏"),
            BuiltInTokenDefinition("fingers crossed", "fingers crossed", "🤞"),
            BuiltInTokenDefinition("OK hand", "OK hand", "👌"),
            BuiltInTokenDefinition("raised hands", "raised hands", "🙌"),
            BuiltInTokenDefinition("flexed bicep", "flexed bicep", "💪"),
            BuiltInTokenDefinition("broken heart emoji", "broken heart emoji", "💔"),
            BuiltInTokenDefinition("red heart emoji|heart emoji", "heart emoji / red heart emoji", "❤️"),
            BuiltInTokenDefinition("fire emoji", "fire emoji", "🔥"),
            BuiltInTokenDefinition("hundred emoji", "hundred emoji", "💯"),
            BuiltInTokenDefinition("party popper", "party popper", "🎉")
        )

        /**
         * Returns the built-in rules in the same deterministic order used by transcript normalization.
         */
        val builtInRules: List<BuiltInSpokenFormattingRule> = PAIRED_DELIMITER_RULES + (BUILT_IN_TOKEN_DEFINITIONS + BUILT_IN_EMOJI_DEFINITIONS).map { definition ->
            BuiltInSpokenFormattingRule(definition.spokenPhrases, definition.displayedWrittenText)
        }
    }

}
