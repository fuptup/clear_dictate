package com.cleardictate.inference.service

/**
 * Pins primitive Binder codes without exposing generated Android types to domain code.
 */
object InferenceProtocolCodes
{
    const val TRANSCRIPT_MODE_RAW = 0
    const val TRANSCRIPT_MODE_CLEAN = 1
    const val TRANSCRIPT_MODE_POLISHED = 2

    const val PRIVACY_STANDARD = 0
    const val PRIVACY_PRIVATE = 1

    const val MODEL_NOT_PREPARED = 0
    const val MODEL_VERIFYING_AND_LOADING = 1
    const val MODEL_READY = 2
    const val MODEL_FAILED = 3

    const val RECORDING_PREPARING = 0
    const val RECORDING_LISTENING = 1
    const val RECORDING_SPEECH_DETECTED = 2
    const val RECORDING_FINALIZING = 3

    const val FAILURE_MODEL_UNAVAILABLE = 0
    const val FAILURE_SPEECH_ENGINE = 1
    const val FAILURE_SERVICE_CLOSED = 2
    const val FAILURE_INVALID_REQUEST = 3
    const val FAILURE_FOREGROUND_NOT_AUTHORIZED = 4
}
