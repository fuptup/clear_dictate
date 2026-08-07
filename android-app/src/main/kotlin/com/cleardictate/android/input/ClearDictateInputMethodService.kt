package com.cleardictate.android.input

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.cleardictate.android.MainActivity
import com.cleardictate.domain.TranscriptMode
import com.cleardictate.inference.OperationPrivacy
import com.cleardictate.inference.service.ClientRecordingState
import com.cleardictate.inference.service.InferenceClientState
import com.cleardictate.inference.service.InferenceServiceClient
import com.cleardictate.inference.service.InferenceConnectionState
import com.cleardictate.inference.service.SpeechModelState
import com.cleardictate.input.EditorContext
import com.cleardictate.input.EditorSafetyDecision
import com.cleardictate.input.EditorSafetyPolicy
import com.cleardictate.input.EditorSessionGuard
import com.cleardictate.input.EditorSessionIdentifier
import com.cleardictate.input.LastInsertion
import com.cleardictate.input.LastInsertionUndoPolicy
import com.cleardictate.input.InsertionRetentionPolicy
import com.cleardictate.input.TranscriptInsertionPolicy
import java.util.UUID

/**
 * Provides system-wide, local dictation through Android's standard input-method contract.
 */
class ClearDictateInputMethodService : android.inputmethodservice.InputMethodService()
{
    private lateinit var inferenceServiceClient: InferenceServiceClient
    private var composeLifecycleOwner: InputMethodComposeLifecycleOwner? = null
    private val editorInfoInspector = AndroidEditorInfoInspector()
    private val editorSafetyPolicy = EditorSafetyPolicy()
    private val transcriptInsertionPolicy = TranscriptInsertionPolicy()
    private val lastInsertionUndoPolicy = LastInsertionUndoPolicy()

    private var currentEditorSessionIdentifier: EditorSessionIdentifier? = null
    private var recordingEditorSessionIdentifier: EditorSessionIdentifier? = null
    private var currentSelectionStart = -1
    private var currentSelectionEnd = -1
    private var currentSafetyDecision by mutableStateOf(EditorSafetyDecision(
        dictationAllowed = false,
        surroundingTextInspectionAllowed = false,
        historyAllowed = false,
        retainTranscriptAfterInsertion = false
    ))
    private var lastInsertion by mutableStateOf<LastInsertion?>(null)
    private var lastHandledOperationIdentifier: String? = null
    private var pendingInsertionTranscript by mutableStateOf("")
    private var localStatusMessage by mutableStateOf<String?>(null)
    private var activeRecordingReviewRequired = false
    private var microphonePermissionGranted by mutableStateOf(false)
    private var notificationPermissionGranted by mutableStateOf(false)

    override fun onCreate()
    {
        super.onCreate()
        refreshRecordingPermissions()
        inferenceServiceClient = InferenceServiceClient(this)
        inferenceServiceClient.bind()
    }

    override fun onCreateInputView(): View
    {
        composeLifecycleOwner?.destroy()
        val lifecycleOwner = InputMethodComposeLifecycleOwner().also { owner ->
            owner.create()
            owner.start()
        }
        composeLifecycleOwner = lifecycleOwner

        // Compose creates its window recomposer from the IME window hierarchy, so the owners must be available on the decor view before the input view is attached.
        window?.window?.decorView?.apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)
        }

        return ComposeView(this).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                MaterialTheme {
                    InputMethodKeyboard()
                }
            }
        }
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean)
    {
        super.onStartInput(attribute, restarting)
        invalidateEditorSession()

        if (attribute == null)
        {
            localStatusMessage = "No compatible text field is active."
            return
        }

        currentEditorSessionIdentifier = EditorSessionIdentifier(UUID.randomUUID().toString())
        currentSelectionStart = attribute.initialSelStart
        currentSelectionEnd = attribute.initialSelEnd
        currentSafetyDecision = editorSafetyPolicy.evaluate(
            editorInfoInspector.inspectSecuritySignals(attribute)
        )
        localStatusMessage = if (currentSafetyDecision.dictationAllowed)
        {
            null
        }
        else
        {
            "Dictation is disabled for this sensitive field."
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean)
    {
        super.onStartInputView(info, restarting)
        refreshRecordingPermissions()
        composeLifecycleOwner?.resume()
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    )
    {
        super.onUpdateSelection(
            oldSelStart,
            oldSelEnd,
            newSelStart,
            newSelEnd,
            candidatesStart,
            candidatesEnd
        )
        currentSelectionStart = newSelStart
        currentSelectionEnd = newSelEnd
    }

    override fun onFinishInputView(finishingInput: Boolean)
    {
        stopForEditorLifecycleChange()
        composeLifecycleOwner?.pause()
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput()
    {
        stopForEditorLifecycleChange()
        invalidateEditorSession()
        super.onFinishInput()
    }

    override fun onWindowHidden()
    {
        stopForEditorLifecycleChange()
        super.onWindowHidden()
    }

    override fun onDestroy()
    {
        stopForEditorLifecycleChange()
        inferenceServiceClient.close()
        composeLifecycleOwner?.destroy()
        composeLifecycleOwner = null
        super.onDestroy()
    }

    @Composable
    private fun InputMethodKeyboard()
    {
        val clientState by inferenceServiceClient.state.collectAsStateWithLifecycle()
        val recordingActive = clientState.recordingState == ClientRecordingState.PREPARING ||
            clientState.recordingState == ClientRecordingState.LISTENING ||
            clientState.recordingState == ClientRecordingState.SPEECH_DETECTED ||
            clientState.recordingState == ClientRecordingState.FINALIZING
        val dictationReady = microphonePermissionGranted &&
            notificationPermissionGranted &&
            currentSafetyDecision.dictationAllowed &&
            clientState.speechModelState == SpeechModelState.READY &&
            clientState.connectionState == InferenceConnectionState.CONNECTED

        HandleCompletedTranscript(clientState)
        InputMethodKeyboardContent(clientState, recordingActive, dictationReady)
    }

    /**
     * Applies a completed operation exactly once and fences it against editor-session changes.
     */
    @Composable
    private fun HandleCompletedTranscript(clientState: InferenceClientState)
    {
        LaunchedEffect(clientState.completedOperationIdentifier)
        {
            val completedOperationIdentifier = clientState.completedOperationIdentifier

            if (completedOperationIdentifier == null ||
                completedOperationIdentifier == lastHandledOperationIdentifier)
            {
                return@LaunchedEffect
            }

            lastHandledOperationIdentifier = completedOperationIdentifier
            val recordingSession = recordingEditorSessionIdentifier
            val currentSession = currentEditorSessionIdentifier
            val selectedTranscript = clientState.selectedTranscript

            if (recordingSession == null ||
                currentSession == null ||
                !EditorSessionGuard.matches(recordingSession, currentSession))
            {
                pendingInsertionTranscript = ""
                localStatusMessage = "The target field changed, so the transcript was not inserted."
                inferenceServiceClient.clearCompletedTranscript()
                return@LaunchedEffect
            }

            if (activeRecordingReviewRequired || clientState.selectedMode == TranscriptMode.POLISHED)
            {
                pendingInsertionTranscript = selectedTranscript
                localStatusMessage = if (clientState.usedDeterministicFallback)
                {
                    "Review deterministic fallback before inserting."
                }
                else
                {
                    "Review the transcript, then press Insert."
                }
            }
            else
            {
                commitTranscript(selectedTranscript, recordingSession)
            }
        }
    }

    /**
     * Keeps the keyboard layout as a short sequence of independently readable controls.
     */
    @Composable
    private fun InputMethodKeyboardContent(
        clientState: InferenceClientState,
        recordingActive: Boolean,
        dictationReady: Boolean
    )
    {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 190.dp)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                InputMethodHeader(clientState)
                InputMethodTranscriptStatus(clientState)
                InputMethodPrimaryActions(clientState, recordingActive, dictationReady)
                BasicEditingControls()
            }
        }
    }

    @Composable
    private fun InputMethodHeader(clientState: InferenceClientState)
    {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("ClearDictate", style = MaterialTheme.typography.titleLarge)
            Text("PC: ${clientState.speechModelState.name.lowercase().replace('_', ' ')}")
        }
    }

    @Composable
    private fun InputMethodTranscriptStatus(clientState: InferenceClientState)
    {
        if (clientState.recordingState == ClientRecordingState.FINALIZING)
        {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Recording is being processed on the PC" }
            )
        }
        else
        {
            LinearProgressIndicator(
                progress = { clientState.normalizedAudioLevel },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Current microphone input level" }
            )
        }
        Text(
            pendingInsertionTranscript.ifEmpty {
                clientState.failureMessage ?: localStatusMessage ?: when (clientState.recordingState)
                {
                    ClientRecordingState.FINALIZING -> "Transcribing and polishing on your PC…"
                    ClientRecordingState.LISTENING, ClientRecordingState.SPEECH_DETECTED -> "Listening… release when finished."
                    else -> "Ready for PC-polished dictation."
                }
            },
            maxLines = 3
        )
    }

    @Composable
    private fun InputMethodPrimaryActions(
        clientState: InferenceClientState,
        recordingActive: Boolean,
        dictationReady: Boolean
    )
    {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HoldToTalkControl(
                enabled = dictationReady &&
                    clientState.recordingState != ClientRecordingState.FINALIZING,
                recordingActive = recordingActive
            )

            if (clientState.connectionState == InferenceConnectionState.CONNECTED && clientState.speechModelState == SpeechModelState.FAILED)
            {
                OutlinedButton(onClick = inferenceServiceClient::retrySpeechModelPreparation) {
                    Text("Retry PC")
                }
            }

            if (recordingActive)
            {
                OutlinedButton(onClick = inferenceServiceClient::cancelDictation) {
                    Text("Cancel")
                }
            }
            if (pendingInsertionTranscript.isNotEmpty())
            {
                Button(onClick = ::insertPendingTranscript) {
                    Text("Insert")
                }
            }
            if (!microphonePermissionGranted || !notificationPermissionGranted)
            {
                OutlinedButton(onClick = ::openSetupActivity) {
                    Text("Setup")
                }
            }
        }
    }

    /**
     * Starts on press and finalizes on release, while retaining a toggle action for accessibility services.
     */
    @Composable
    private fun HoldToTalkControl(enabled: Boolean, recordingActive: Boolean)
    {
        val label = if (recordingActive) "Release to process" else "Hold to talk"
        Surface(
            color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .pointerInput(enabled) {
                    awaitEachGesture {
                        awaitFirstDown()
                        if (!enabled)
                        {
                            waitForUpOrCancellation()
                            return@awaitEachGesture
                        }
                        startRecording()
                        if (waitForUpOrCancellation() == null)
                        {
                            inferenceServiceClient.cancelDictation()
                        }
                        else
                        {
                            inferenceServiceClient.stopDictation()
                        }
                    }
                }
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    contentDescription = label
                    onClick {
                        if (recordingActive)
                        {
                            inferenceServiceClient.stopDictation()
                        }
                        else
                        {
                            startRecording()
                        }
                        true
                    }
                }
        ) {
            Box(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                Text(label)
            }
        }
    }

    private fun insertPendingTranscript()
    {
        recordingEditorSessionIdentifier?.let { recordingSession ->
            commitTranscript(pendingInsertionTranscript, recordingSession)
        }
    }

    @Composable
    private fun BasicEditingControls()
    {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedButton(onClick = ::undoLastInsertion, enabled = lastInsertion != null) {
                Text("Undo")
            }
            OutlinedButton(onClick = ::sendBackspace) {
                Text("Backspace")
            }
            OutlinedButton(onClick = ::sendSpace) {
                Text("Space")
            }
            OutlinedButton(onClick = ::performCurrentEditorAction) {
                Text(editorActionLabel(currentInputEditorInfo))
            }
            OutlinedButton(onClick = ::switchToNextKeyboard) {
                Text("Keyboard")
            }
            OutlinedButton(onClick = ::openSetupActivity) {
                Text("Settings")
            }
        }
    }

    private fun startRecording()
    {
        val currentSession = currentEditorSessionIdentifier ?: return

        if (!currentSafetyDecision.dictationAllowed)
        {
            localStatusMessage = "Dictation is disabled for this sensitive field."
            return
        }

        if (!microphonePermissionGranted || !notificationPermissionGranted)
        {
            localStatusMessage = "Open Setup and grant microphone and recording notification permissions."
            return
        }

        recordingEditorSessionIdentifier = currentSession
        activeRecordingReviewRequired = true
        pendingInsertionTranscript = ""
        lastHandledOperationIdentifier = null
        val privacy = if (currentSafetyDecision.historyAllowed)
        {
            OperationPrivacy.STANDARD
        }
        else
        {
            OperationPrivacy.PRIVATE
        }

        if (!inferenceServiceClient.startDictation(privacy))
        {
            recordingEditorSessionIdentifier = null
        }
    }

    private fun commitTranscript(
        transcript: String,
        recordingSessionIdentifier: EditorSessionIdentifier
    )
    {
        val inputConnection = currentInputConnection ?: run {
            clearRejectedInsertionState("The target editor is no longer connected.")
            return
        }
        val currentSession = currentEditorSessionIdentifier ?: run {
            clearRejectedInsertionState("The target editor session ended.")
            return
        }
        val editorContext = buildEditorContext(inputConnection, currentSession)
        val decision = transcriptInsertionPolicy.decide(
            editorContext = editorContext,
            transcript = transcript,
            recordingEditorSessionIdentifier = recordingSessionIdentifier
        )

        if (!decision.insertionAllowed)
        {
            clearRejectedInsertionState(
                "The transcript was not inserted because the editor context changed or is sensitive."
            )
            return
        }

        inputConnection.beginBatchEdit()

        try
        {
            inputConnection.finishComposingText()
            val insertionAccepted = inputConnection.commitText(decision.textToInsert, 1)

            if (!insertionAccepted)
            {
                pendingInsertionTranscript = ""
                recordingEditorSessionIdentifier = null
                lastInsertion = null
                inferenceServiceClient.clearCompletedTranscript()
                localStatusMessage = "The target editor rejected the transcript."
                return
            }
        }
        finally
        {
            inputConnection.endBatchEdit()
        }

        lastInsertion = InsertionRetentionPolicy.createUndoRecord(
            currentSession,
            decision.textToInsert,
            currentSafetyDecision.retainTranscriptAfterInsertion
        )
        pendingInsertionTranscript = ""
        recordingEditorSessionIdentifier = null
        localStatusMessage = "Transcript inserted."

        if (!currentSafetyDecision.retainTranscriptAfterInsertion)
        {
            pendingInsertionTranscript = ""
            inferenceServiceClient.clearCompletedTranscript()
        }
    }

    private fun buildEditorContext(
        inputConnection: InputConnection,
        editorSessionIdentifier: EditorSessionIdentifier
    ): EditorContext
    {
        if (!currentSafetyDecision.surroundingTextInspectionAllowed)
        {
            val selectionIsUnknown = currentSelectionStart < 0 || currentSelectionEnd < 0
            return EditorContext(
                editorSessionIdentifier = editorSessionIdentifier,
                hasSelection = selectionIsUnknown || currentSelectionStart != currentSelectionEnd,
                isSensitive = !currentSafetyDecision.dictationAllowed,
                isPrivate = !currentSafetyDecision.historyAllowed
            )
        }

        val textBeforeCursor = inputConnection.getTextBeforeCursor(MAXIMUM_SURROUNDING_CHARACTERS, 0)
            ?.toString()
            .orEmpty()
        val textAfterCursor = inputConnection.getTextAfterCursor(MAXIMUM_SURROUNDING_CHARACTERS, 0)
            ?.toString()
            .orEmpty()
        val hasSelection = !inputConnection.getSelectedText(0).isNullOrEmpty()

        return EditorContext(
            editorSessionIdentifier = editorSessionIdentifier,
            textBeforeCursor = textBeforeCursor,
            textAfterCursor = textAfterCursor,
            hasSelection = hasSelection,
            replaceSelectionEnabled = false,
            isMultiline = currentInputEditorInfo.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0,
            isSensitive = !currentSafetyDecision.dictationAllowed,
            isPrivate = !currentSafetyDecision.historyAllowed
        )
    }

    private fun undoLastInsertion()
    {
        val insertion = lastInsertion ?: return
        val inputConnection = currentInputConnection ?: return
        val currentSession = currentEditorSessionIdentifier ?: return
        val textBeforeCursor = inputConnection.getTextBeforeCursor(insertion.insertedText.length, 0)
            ?.toString()
            .orEmpty()
        val charactersToDelete = lastInsertionUndoPolicy.charactersToDelete(
            textBeforeCursor,
            currentSession,
            insertion
        ) ?: run {
            localStatusMessage = "Undo was refused because the inserted text has changed."
            lastInsertion = null
            return
        }

        if (inputConnection.deleteSurroundingText(charactersToDelete, 0))
        {
            lastInsertion = null
            localStatusMessage = "Last ClearDictate insertion removed."
        }
        else
        {
            lastInsertion = null
            localStatusMessage = "The target editor refused the undo request."
        }
    }

    private fun sendBackspace()
    {
        currentInputConnection?.deleteSurroundingTextInCodePoints(1, 0)
        lastInsertion = null
    }

    private fun sendSpace()
    {
        currentInputConnection?.commitText(" ", 1)
        lastInsertion = null
    }

    private fun performCurrentEditorAction()
    {
        val editorInfo = currentInputEditorInfo
        val requestedAction = editorInfo.imeOptions and EditorInfo.IME_MASK_ACTION
        val isMultiline = editorInfo.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0

        val unspecifiedAction = requestedAction == EditorInfo.IME_ACTION_NONE ||
            requestedAction == EditorInfo.IME_ACTION_UNSPECIFIED

        if (unspecifiedAction && isMultiline)
        {
            currentInputConnection?.commitText("\n", 1)
        }
        else
        {
            val action = if (unspecifiedAction)
            {
                EditorInfo.IME_ACTION_DONE
            }
            else
            {
                requestedAction
            }
            currentInputConnection?.performEditorAction(action)
        }
        lastInsertion = null
    }

    private fun switchToNextKeyboard()
    {
        stopForEditorLifecycleChange()
        switchToNextInputMethod(false)
    }

    private fun openSetupActivity()
    {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    private fun stopForEditorLifecycleChange()
    {
        inferenceServiceClient.cancelDictation()
        inferenceServiceClient.clearCompletedTranscript()
        pendingInsertionTranscript = ""
        recordingEditorSessionIdentifier = null
        lastInsertion = null
        activeRecordingReviewRequired = false
    }

    private fun invalidateEditorSession()
    {
        stopForEditorLifecycleChange()
        currentEditorSessionIdentifier = null
        currentSelectionStart = -1
        currentSelectionEnd = -1
        currentSafetyDecision = EditorSafetyDecision(
            dictationAllowed = false,
            surroundingTextInspectionAllowed = false,
            historyAllowed = false,
            retainTranscriptAfterInsertion = false
        )
    }

    private fun editorActionLabel(editorInfo: EditorInfo): String
    {
        return when (editorInfo.imeOptions and EditorInfo.IME_MASK_ACTION)
        {
            EditorInfo.IME_ACTION_GO -> "Go"
            EditorInfo.IME_ACTION_NEXT -> "Next"
            EditorInfo.IME_ACTION_PREVIOUS -> "Previous"
            EditorInfo.IME_ACTION_SEARCH -> "Search"
            EditorInfo.IME_ACTION_SEND -> "Send"
            EditorInfo.IME_ACTION_DONE -> "Done"
            else -> if (editorInfo.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0)
            {
                "Enter"
            }
            else
            {
                "Done"
            }
        }
    }

    private fun refreshRecordingPermissions()
    {
        microphonePermissionGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        notificationPermissionGranted =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
    }

    private fun clearRejectedInsertionState(message: String)
    {
        pendingInsertionTranscript = ""
        recordingEditorSessionIdentifier = null
        lastInsertion = null
        inferenceServiceClient.clearCompletedTranscript()
        localStatusMessage = message
    }

    private companion object
    {
        const val MAXIMUM_SURROUNDING_CHARACTERS = 2
    }
}

/**
 * Supplies the lifecycle and saved-state owners that ComposeView needs outside an Activity.
 */
private class InputMethodComposeLifecycleOwner :
    LifecycleOwner,
    SavedStateRegistryOwner,
    ViewModelStoreOwner
{
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    override val viewModelStore = ViewModelStore()

    init
    {
        savedStateController.performAttach()
    }

    fun create()
    {
        savedStateController.performRestore(Bundle.EMPTY)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    fun start()
    {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    }

    fun resume()
    {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun pause()
    {
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.RESUMED))
        {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        }
    }

    fun destroy()
    {
        if (lifecycleRegistry.currentState != Lifecycle.State.DESTROYED)
        {
            pause()

            if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED))
            {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            }

            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
        viewModelStore.clear()
    }
}
