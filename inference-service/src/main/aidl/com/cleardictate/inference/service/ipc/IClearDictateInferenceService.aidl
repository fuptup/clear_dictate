package com.cleardictate.inference.service.ipc;

import com.cleardictate.inference.service.ipc.IClearDictateInferenceCallback;

interface IClearDictateInferenceService
{
    void registerClient(String clientSessionIdentifier, IClearDictateInferenceCallback callback);
    oneway void unregisterClient(String clientSessionIdentifier);
    oneway void prepareSpeechModel();
    oneway void beginDictation(String clientSessionIdentifier, String operationIdentifier, int transcriptModeCode, int privacyCode);
    oneway void stopDictation(String clientSessionIdentifier, String operationIdentifier);
    oneway void cancelDictation(String clientSessionIdentifier, String operationIdentifier);
}
