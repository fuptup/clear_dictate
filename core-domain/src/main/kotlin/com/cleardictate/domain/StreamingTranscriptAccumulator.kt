package com.cleardictate.domain

/**
 * Immutable view of the raw transcript assembled from revisable streaming recognition events.
 */
data class StreamingTranscriptSnapshot(
    val completedRawTranscript: String,
    val partialTranscript: String
)
{
    val visibleRawTranscript: String
        get() = joinTranscriptSegments(completedRawTranscript, partialTranscript)

    companion object
    {
        val EMPTY = StreamingTranscriptSnapshot(
            completedRawTranscript = "",
            partialTranscript = ""
        )
    }
}

/**
 * Converts revisable streaming-recognition callbacks into one ordered raw transcript.
 *
 * A monotonically increasing session identifier makes stale native callbacks harmless after
 * cancellation or replacement. Completed line identifiers are retained so duplicate native
 * completion callbacks cannot duplicate text.
 */
class StreamingTranscriptAccumulator(
    private val maximumVisibleCharacterCount: Int = DEFAULT_MAXIMUM_VISIBLE_CHARACTER_COUNT
)
{
    private var nextSessionIdentifier = 1L
    private var activeSessionIdentifier: Long? = null
    private val completedLinesByIdentifier = linkedMapOf<Long, String>()
    private var partialLineIdentifier: Long? = null
    private var partialLineText = ""
    private var completedCharacterCount = 0
    private var limitExceeded = false

    /**
     * Starts a new isolated recording session and clears every prior transcript buffer.
     */
    @Synchronized
    fun beginSession(): Long
    {
        val sessionIdentifier = nextSessionIdentifier
        nextSessionIdentifier += 1L
        activeSessionIdentifier = sessionIdentifier
        clearTranscriptBuffers()
        return sessionIdentifier
    }

    /**
     * Replaces the current revisable line when the callback belongs to the active session.
     */
    @Synchronized
    fun acceptPartial(sessionIdentifier: Long, lineIdentifier: Long, text: String): Boolean
    {
        if (!isActive(sessionIdentifier) || completedLinesByIdentifier.containsKey(lineIdentifier))
        {
            return false
        }

        val revisedText = text.trim()

        if (projectedVisibleCharacterCount(revisedText) > maximumVisibleCharacterCount)
        {
            limitExceeded = true
            return false
        }

        partialLineIdentifier = lineIdentifier
        partialLineText = revisedText
        return true
    }

    /**
     * Commits one line once and removes the corresponding revisable line.
     */
    @Synchronized
    fun acceptCompleted(sessionIdentifier: Long, lineIdentifier: Long, text: String): Boolean
    {
        if (!isActive(sessionIdentifier) || completedLinesByIdentifier.containsKey(lineIdentifier))
        {
            return false
        }

        val completedText = text.trim()

        if (completedText.isNotEmpty())
        {
            val separatingSpaceCount = if (completedLinesByIdentifier.isEmpty()) 0 else 1

            if (completedCharacterCount + separatingSpaceCount + completedText.length >
                maximumVisibleCharacterCount)
            {
                limitExceeded = true
                return false
            }

            completedLinesByIdentifier[lineIdentifier] = completedText
            completedCharacterCount += separatingSpaceCount + completedText.length
        }

        if (partialLineIdentifier == lineIdentifier)
        {
            partialLineIdentifier = null
            partialLineText = ""
        }

        return true
    }

    /**
     * Invalidates the operation identity before clearing sensitive in-memory transcript state.
     */
    @Synchronized
    fun cancelSession(sessionIdentifier: Long): Boolean
    {
        if (!isActive(sessionIdentifier))
        {
            return false
        }

        activeSessionIdentifier = null
        clearTranscriptBuffers()
        return true
    }

    /**
     * Returns a stable copy suitable for publishing through observable presentation state.
     */
    @Synchronized
    fun snapshot(): StreamingTranscriptSnapshot
    {
        return StreamingTranscriptSnapshot(
            completedRawTranscript = completedLinesByIdentifier.values.joinToString(separator = " "),
            partialTranscript = partialLineText
        )
    }

    @Synchronized
    fun hasExceededLimit(sessionIdentifier: Long): Boolean
    {
        return isActive(sessionIdentifier) && limitExceeded
    }

    private fun isActive(sessionIdentifier: Long): Boolean
    {
        return activeSessionIdentifier == sessionIdentifier
    }

    private fun clearTranscriptBuffers()
    {
        completedLinesByIdentifier.clear()
        partialLineIdentifier = null
        partialLineText = ""
        completedCharacterCount = 0
        limitExceeded = false
    }

    private fun projectedVisibleCharacterCount(partialText: String): Int
    {
        val separatingSpaceCount = if (completedCharacterCount > 0 && partialText.isNotEmpty()) 1 else 0
        return completedCharacterCount + separatingSpaceCount + partialText.length
    }

    private companion object
    {
        const val DEFAULT_MAXIMUM_VISIBLE_CHARACTER_COUNT = 32_000
    }
}

private fun joinTranscriptSegments(completedTranscript: String, partialTranscript: String): String
{
    return when
    {
        completedTranscript.isEmpty() -> partialTranscript
        partialTranscript.isEmpty() -> completedTranscript
        else -> "$completedTranscript $partialTranscript"
    }
}
