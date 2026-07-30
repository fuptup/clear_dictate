package com.cleardictate.inference

/**
 * Identifies one application or keyboard client session without revealing its target application.
 */
@JvmInline
value class ClientSessionIdentifier(val value: String)
{
    init
    {
        require(OPAQUE_IDENTIFIER_PATTERN.matches(value)) {
            "Client session identifier must contain 1 to 64 opaque identifier characters."
        }
    }
}

/**
 * Identifies one recording, recognition, or polishing operation across process boundaries.
 */
@JvmInline
value class OperationIdentifier(val value: String)
{
    init
    {
        require(OPAQUE_IDENTIFIER_PATTERN.matches(value)) {
            "Operation identifier must contain 1 to 64 opaque identifier characters."
        }
    }
}

private val OPAQUE_IDENTIFIER_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")

/**
 * Carries the immutable privacy classification that began with an editor or application session.
 */
enum class OperationPrivacy
{
    STANDARD,
    PRIVATE;

    /**
     * Combines classifications without permitting a private operation to become public later.
     */
    fun restrictWith(other: OperationPrivacy): OperationPrivacy
    {
        return if (this == PRIVATE || other == PRIVATE)
        {
            PRIVATE
        }
        else
        {
            STANDARD
        }
    }
}

/**
 * Provides mandatory identity and privacy for every potentially long-running inference request.
 */
data class InferenceOperationContext(
    val clientSessionIdentifier: ClientSessionIdentifier,
    val operationIdentifier: OperationIdentifier,
    val privacy: OperationPrivacy
)
