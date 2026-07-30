package com.cleardictate.inference.service.ipc;

oneway interface IClearDictateInferenceCallback
{
    void onSpeechModelStateChanged(int stateCode);
    void onOperationAccepted(String operationIdentifier);
    void onOperationBusy(String operationIdentifier);
    void onRecordingStateChanged(String operationIdentifier, int stateCode);
    void onAudioLevel(String operationIdentifier, float normalizedLevel);
    void onPartialTranscript(String operationIdentifier, String rawPartialTranscript);
    void onFinalTranscript(String operationIdentifier, String rawTranscript, String cleanTranscript, String polishedTranscript, String selectedTranscript, int selectedModeCode, boolean usedDeterministicFallback, int fallbackReasonCode);
    void onOperationCancelled(String operationIdentifier);
    void onFailure(String operationIdentifier, int failureCode);
}
