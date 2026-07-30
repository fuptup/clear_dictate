package com.cleardictate.inference

/**
 * Enumerates transcript-free failure categories that callers may log safely.
 */
enum class InferenceFailureCategory
{
    MODEL_NOT_READY,
    MODEL_VERIFICATION_FAILED,
    CONTEXT_LIMIT_EXCEEDED,
    REQUEST_REJECTED,
    NATIVE_FAILURE,
    PROCESS_DIED,
    PROTOCOL_FAILURE,
    CANCELLED,
    CANCELLATION_NOT_ACKNOWLEDGED,
    TIMEOUT
}

/**
 * Represents a native or service failure without accepting transcript text in its message.
 */
class LocalInferenceException(
    val category: InferenceFailureCategory,
    val diagnosticCode: String? = null
) : Exception(buildSafeMessage(category, diagnosticCode))
{
    init
    {
        require(diagnosticCode == null || SAFE_DIAGNOSTIC_CODE.matches(diagnosticCode)) {
            "Diagnostic code must contain only uppercase letters, digits, and underscores."
        }
    }

    private companion object
    {
        val SAFE_DIAGNOSTIC_CODE = Regex("[A-Z0-9_]{1,64}")

        fun buildSafeMessage(category: InferenceFailureCategory, diagnosticCode: String?): String
        {
            return if (diagnosticCode == null)
            {
                "Local inference failed: $category"
            }
            else
            {
                "Local inference failed: $category ($diagnosticCode)"
            }
        }
    }
}

/**
 * Confirms that the engine stopped the exact requested operation.
 */
data class CancellationAcknowledgement(
    val operationIdentifier: OperationIdentifier
)
