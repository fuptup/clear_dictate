package com.cleardictate.android.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Bundle
import android.os.PersistableBundle
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import com.cleardictate.inference.OperationPrivacy
import com.cleardictate.inference.service.ClientRecordingState
import com.cleardictate.inference.service.InferenceClientState
import com.cleardictate.inference.service.InferenceConnectionState
import com.cleardictate.inference.service.InferenceServiceClient
import com.cleardictate.inference.service.PcConnectionState
import com.cleardictate.inference.service.SpeechModelState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the user's chosen keyboard active while offering hold-to-talk dictation over any supported editable field.
 */
class ClearDictateAccessibilityService : AccessibilityService()
{
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val securityInspector = AccessibilityEditorSecurityInspector()
    private val insertionPlanner = AccessibilityTextInsertionPlanner()
    private val insertionUndoPlanner = AccessibilityInsertionUndoPlanner()
    private lateinit var inferenceServiceClient: InferenceServiceClient
    private lateinit var floatingControl: FloatingDictationControlView
    private lateinit var floatingUndoControl: FloatingUndoControlView
    private lateinit var windowManager: WindowManager
    private lateinit var floatingControlLayoutParameters: WindowManager.LayoutParams
    private lateinit var floatingControlDragTracker: FloatingControlDragTracker
    private lateinit var floatingControlPositionStore: FloatingControlPositionStore
    private var floatingControlPosition = FloatingControlPosition(0, 0)
    private var latestClientState = InferenceClientState()
    private var focusedEditorIdentity: AccessibilityFieldIdentity? = null
    private var recordingField: AccessibilityFieldIdentity? = null
    private var lastInsertionUndo: AccessibilityInsertionUndoRecord? = null
    private var pendingPaste: AccessibilityPendingPaste? = null
    private var recordingTouchActive = false
    private var lastHandledOperationIdentifier: String? = null
    private var closed = false
    private val lockFloatingControlForDictation = Runnable {
        if (::floatingControlDragTracker.isInitialized)
        {
            floatingControlDragTracker.lockForDictation()
        }
    }

    override fun onServiceConnected()
    {
        super.onServiceConnected()
        inferenceServiceClient = InferenceServiceClient(this)
        createFloatingControls()
        serviceScope.launch {
            inferenceServiceClient.state.collectLatest(::handleClientState)
        }
        inferenceServiceClient.bind()
        refreshControlPresentation()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?)
    {
        val eventEditor = event?.source?.takeIf(::isSupportedEditor)
        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED)
        {
            focusedEditorIdentity = eventEditor?.let(::createIdentity)
        }
        else if (eventEditor != null)
        {
            focusedEditorIdentity = createIdentity(eventEditor)
        }
        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED && eventEditor != null)
        {
            captureNativePaste(eventEditor, event)
        }
        val originalField = recordingField
        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED && eventEditor != null && originalField != null && createIdentity(eventEditor) != originalField)
        {
            cancelRecording()
            showMessage("The target field changed, so dictation was cancelled.")
        }
        val undoRecord = lastInsertionUndo
        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED && eventEditor != null && undoRecord != null &&
            !undoRecord.fieldIdentity.representsSameEditor(createIdentity(eventEditor)))
        {
            clearUndoAvailability()
        }

        if (!latestClientState.recordingState.isActive() || recordingField == null)
        {
            refreshControlPresentation(eventEditor ?: findFocusedEditor())
        }
    }

    override fun onInterrupt()
    {
        cancelRecording()
    }

    override fun onDestroy()
    {
        closeService()
        super.onDestroy()
    }

    /**
     * Adds one non-focusable accessibility overlay so touches outside the microphone continue to reach the active app and keyboard.
     */
    private fun createFloatingControls()
    {
        windowManager = getSystemService(WindowManager::class.java)
        floatingControl = FloatingDictationControlView(this).apply {
            visibility = View.GONE
            setOnTouchListener { _, event -> handleControlTouch(event) }
        }
        val size = densityIndependentPixels(68)
        val displayWidth = resources.displayMetrics.widthPixels
        val displayHeight = resources.displayMetrics.heightPixels
        floatingControlPositionStore = FloatingControlPositionStore(this)
        floatingControlDragTracker = FloatingControlDragTracker(ViewConfiguration.get(this).scaledTouchSlop)
        floatingControlPosition = clampFloatingControlPosition(
            floatingControlPositionStore.load() ?: FloatingControlPosition(displayWidth - size - densityIndependentPixels(10), (displayHeight - size) / 2),
            displayWidth,
            displayHeight,
            size
        )
        floatingControlLayoutParameters = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            x = floatingControlPosition.x
            y = floatingControlPosition.y
            title = "ClearDictate floating microphone"
        }
        windowManager.addView(floatingControl, floatingControlLayoutParameters)

        floatingUndoControl = FloatingUndoControlView(this).apply {
            visibility = View.GONE
            setOnClickListener { control ->
                control.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                undoLastInsertion()
            }
        }
        val undoSize = densityIndependentPixels(52)
        val undoLayoutParameters = WindowManager.LayoutParams(
            undoSize,
            undoSize,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = densityIndependentPixels(84)
            title = "ClearDictate remove last dictation"
        }
        windowManager.addView(floatingUndoControl, undoLayoutParameters)
    }

    private fun handleControlTouch(event: MotionEvent): Boolean
    {
        return when (event.actionMasked)
        {
            MotionEvent.ACTION_DOWN ->
            {
                floatingControl.removeCallbacks(lockFloatingControlForDictation)
                floatingControlDragTracker.start(event.rawX, event.rawY, floatingControlPosition)
                recordingTouchActive = beginRecording()
                if (recordingTouchActive)
                {
                    floatingControl.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    floatingControl.postDelayed(lockFloatingControlForDictation, ViewConfiguration.getTapTimeout().toLong())
                }
                true
            }
            MotionEvent.ACTION_MOVE ->
            {
                moveFloatingControl(event)
                true
            }
            MotionEvent.ACTION_UP ->
            {
                floatingControl.removeCallbacks(lockFloatingControlForDictation)
                moveFloatingControl(event)
                if (floatingControlDragTracker.finish())
                {
                    floatingControlPositionStore.save(floatingControlPosition)
                    floatingControl.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY_RELEASE)
                    true
                }
                else if (recordingTouchActive)
                {
                    recordingTouchActive = false
                    floatingControl.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY_RELEASE)
                    floatingControl.performClick()
                    inferenceServiceClient.stopDictation()
                    true
                }
                else
                {
                    true
                }
            }
            MotionEvent.ACTION_CANCEL ->
            {
                floatingControl.removeCallbacks(lockFloatingControlForDictation)
                floatingControlDragTracker.finish()
                if (recordingTouchActive)
                {
                    cancelRecording()
                }
                true
            }
            else -> true
        }
    }

    /**
     * Converts a deliberate drag into a clamped overlay update and cancels the provisional recording exactly once when dragging begins.
     */
    private fun moveFloatingControl(event: MotionEvent)
    {
        val wasDragging = floatingControlDragTracker.isDragging
        val requestedPosition = floatingControlDragTracker.move(event.rawX, event.rawY) ?: return
        if (!wasDragging && recordingTouchActive)
        {
            floatingControl.removeCallbacks(lockFloatingControlForDictation)
            cancelRecording()
        }
        floatingControlPosition = clampFloatingControlPosition(
            requestedPosition,
            resources.displayMetrics.widthPixels,
            resources.displayMetrics.heightPixels,
            floatingControlLayoutParameters.width
        )
        floatingControlLayoutParameters.x = floatingControlPosition.x
        floatingControlLayoutParameters.y = floatingControlPosition.y
        windowManager.updateViewLayout(floatingControl, floatingControlLayoutParameters)
    }

    /**
     * Captures an identity-only fence before microphone activation so completed text cannot drift to a different editor.
     */
    private fun beginRecording(): Boolean
    {
        if (!latestClientState.isReadyForDictation())
        {
            if (latestClientState.speechModelState == SpeechModelState.FAILED)
            {
                inferenceServiceClient.retrySpeechModelPreparation()
            }
            showMessage("ClearDictate is reconnecting to the paired PC.")
            return false
        }

        val focusedEditor = findFocusedEditor()
        if (focusedEditor == null)
        {
            showMessage("Focus a supported text field first.")
            return false
        }
        if (!inspectSafety(focusedEditor).dictationAllowed)
        {
            showMessage("ClearDictate is disabled for this sensitive field.")
            return false
        }

        recordingField = createIdentity(focusedEditor)
        lastHandledOperationIdentifier = null
        if (!inferenceServiceClient.startDictation(OperationPrivacy.PRIVATE))
        {
            recordingField = null
            showMessage(latestClientState.failureMessage ?: "ClearDictate could not start recording.")
            return false
        }
        return true
    }

    /**
     * Updates the visible control and consumes each completed operation exactly once.
     */
    private fun handleClientState(clientState: InferenceClientState)
    {
        latestClientState = clientState
        val completedOperationIdentifier = clientState.completedOperationIdentifier
        if (completedOperationIdentifier != null && completedOperationIdentifier != lastHandledOperationIdentifier)
        {
            lastHandledOperationIdentifier = completedOperationIdentifier
            insertCompletedTranscript(clientState)
        }
        refreshControlPresentation()
    }

    /**
     * Revalidates sensitivity and field identity before transiently reading the text needed for Android's complete-value replacement action.
     */
    private fun insertCompletedTranscript(clientState: InferenceClientState)
    {
        val originalField = recordingField
        val currentEditor = findFocusedEditor()
        val currentEditorSafety = currentEditor?.let(::inspectSafety)
        val inserted = if (originalField == null || currentEditor == null || currentEditorSafety?.dictationAllowed != true || clientState.usedDeterministicFallback)
        {
            false
        }
        else
        {
            clearUndoAvailability()
            insertTranscript(currentEditor, originalField, clientState.selectedTranscript)
        }
        showMessage(
            when
            {
                inserted -> "Dictation inserted."
                clientState.usedDeterministicFallback -> "PC polishing failed, so the transcript was not inserted."
                else -> "The text field changed or does not support direct insertion."
            }
        )
        recordingField = null
        inferenceServiceClient.clearCompletedTranscript()
    }

    /**
     * Uses native paste when an editor hides its cursor, then relies on its text-change event to capture the true inserted range. Editors exposing a cursor retain the
     * direct set-text path so selection replacement and spacing remain deterministic without touching the clipboard.
     */
    private fun insertTranscript(node: AccessibilityNodeInfo, originalField: AccessibilityFieldIdentity, transcript: String): Boolean
    {
        val whatsappComposerEmpty = isWhatsAppComposerEmpty(node)
        if (!whatsappComposerEmpty && shouldUseNativePaste(
                selectionStart = node.textSelectionStart,
                selectionEnd = node.textSelectionEnd,
                pasteSupported = supportsAction(node, AccessibilityNodeInfo.ACTION_PASTE),
                setTextSupported = supportsAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT),
                isEditable = node.isEditable))
        {
            return performNativePaste(node, originalField, transcript)
        }
        val currentText = if (whatsappComposerEmpty) "" else editableText(node)
        val selectionStart = if (whatsappComposerEmpty) 0 else node.textSelectionStart
        val selectionEnd = if (whatsappComposerEmpty) 0 else node.textSelectionEnd
        val replacement = insertionPlanner.plan(
            recordingField = originalField,
            currentField = AccessibilityEditableText(
                identity = createIdentity(node),
                text = currentText,
                selectionStart = selectionStart,
                selectionEnd = selectionEnd,
                isSensitive = false
            ),
            transcript = transcript
        ) ?: return false
        val actionSucceeded = performTextReplacement(node, replacement)
        if (actionSucceeded)
        {
            lastInsertionUndo = insertionUndoPlanner.capture(createIdentity(node), replacement)
        }
        return actionSucceeded
    }

    /**
     * Uses WhatsApp's visible voice-note control as the authoritative empty-composer signal because its accessibility text can still report the visual placeholder.
     */
    private fun isWhatsAppComposerEmpty(node: AccessibilityNodeInfo): Boolean
    {
        val voiceNoteControlVisible = rootInActiveWindow
            ?.findAccessibilityNodeInfosByViewId(WHATSAPP_VOICE_NOTE_VIEW_IDENTIFIER)
            ?.any { control -> control.isVisibleToUser } == true
        return isWhatsAppEmptyComposer(
            packageName = node.packageName?.toString().orEmpty(),
            viewIdentifier = node.viewIdResourceName.orEmpty(),
            voiceNoteControlVisible = voiceNoteControlVisible
        )
    }

    /**
     * Temporarily places privacy-marked text on Android's clipboard because ACTION_PASTE has no direct text argument, and restores the prior clipboard immediately.
     */
    private fun performNativePaste(node: AccessibilityNodeInfo, originalField: AccessibilityFieldIdentity, transcript: String): Boolean
    {
        val currentIdentity = createIdentity(node)
        if (!originalField.representsSameEditor(currentIdentity))
        {
            return false
        }
        val expectedPaste = insertionUndoPlanner.expectPaste(currentIdentity, transcript) ?: return false
        val clipboard = getSystemService(ClipboardManager::class.java)
        val hadPrimaryClip = clipboard.hasPrimaryClip()
        val previousClip = if (hadPrimaryClip) clipboard.primaryClip ?: return false else null
        val privateClip = ClipData.newPlainText("ClearDictate private dictation", transcript).apply {
            description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        pendingPaste = expectedPaste
        var pasteSucceeded = false
        try
        {
            clipboard.setPrimaryClip(privateClip)
            pasteSucceeded = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }
        catch (_: SecurityException)
        {
            pasteSucceeded = false
        }
        finally
        {
            val clipboardRestored = runCatching {
                if (previousClip != null)
                {
                    clipboard.setPrimaryClip(previousClip)
                }
                else
                {
                    clipboard.clearPrimaryClip()
                }
            }.isSuccess
            if (!clipboardRestored)
            {
                Log.w(SERVICE_LOG_TAG, "Android did not restore the clipboard after native paste.")
            }
        }
        if (!pasteSucceeded)
        {
            pendingPaste = null
        }
        return pasteSucceeded
    }

    /**
     * Verifies the event produced by native paste, applies shared boundary spacing if needed, and exposes privacy-safe undo for the exact inserted range.
     */
    private fun captureNativePaste(node: AccessibilityNodeInfo, event: AccessibilityEvent)
    {
        val expectedPaste = pendingPaste ?: return
        val currentField = AccessibilityEditableText(
            identity = createIdentity(node),
            text = editableText(node),
            selectionStart = node.textSelectionStart,
            selectionEnd = node.textSelectionEnd,
            isSensitive = !inspectSafety(node).dictationAllowed
        )
        val rawRecord = insertionUndoPlanner.capturePaste(
            pendingPaste = expectedPaste,
            currentField = currentField,
            insertedTextStart = event.fromIndex,
            addedTextLength = event.addedCount,
            removedTextLength = event.removedCount
        )
        pendingPaste = null
        if (rawRecord == null)
        {
            return
        }
        val insertedTextEnd = rawRecord.insertedTextStart + rawRecord.insertedTextLength
        val normalizedReplacement = insertionPlanner.normalizePastedRange(currentField, rawRecord.insertedTextStart, insertedTextEnd)
        lastInsertionUndo = if (normalizedReplacement != null && normalizedReplacement.text != currentField.text && performTextReplacement(node, normalizedReplacement))
        {
            insertionUndoPlanner.capture(currentField.identity, normalizedReplacement)
        }
        else
        {
            rawRecord
        }
    }

    /**
     * Applies the planned complete value and then restores the cursor immediately after the inserted transcript.
     */
    private fun performTextReplacement(node: AccessibilityNodeInfo?, replacement: AccessibilityTextReplacement): Boolean
    {
        return performCompleteTextReplacement(node, replacement.text, replacement.cursorPosition)
    }

    /**
     * Applies one complete field value through the only direct replacement action exposed to an accessibility service.
     */
    private fun performCompleteTextReplacement(node: AccessibilityNodeInfo?, text: String, cursorPosition: Int): Boolean
    {
        if (node == null)
        {
            return false
        }
        val textArguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, textArguments))
        {
            return false
        }
        val selectionArguments = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursorPosition)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursorPosition)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectionArguments)
        return true
    }

    /**
     * Removes the last insertion only when the same field still contains the unchanged hashed segment.
     */
    private fun undoLastInsertion()
    {
        val record = lastInsertionUndo
        val currentEditor = findFocusedEditor()
        val safety = currentEditor?.let(::inspectSafety)
        val replacement = if (record == null || currentEditor == null || safety?.dictationAllowed != true)
        {
            null
        }
        else
        {
            insertionUndoPlanner.plan(
                record,
                AccessibilityEditableText(
                    identity = createIdentity(currentEditor),
                    text = editableText(currentEditor),
                    selectionStart = currentEditor.textSelectionStart,
                    selectionEnd = currentEditor.textSelectionEnd,
                    isSensitive = false
                )
            )
        }
        val removed = replacement != null && performCompleteTextReplacement(currentEditor, replacement.text, replacement.cursorPosition)
        showMessage(if (removed) "Last dictation removed." else "The last dictation changed, so nothing was removed.")
        clearUndoAvailability()
        refreshControlPresentation(currentEditor)
    }

    /**
     * Searches application windows rather than overlay or input-method windows. Custom document editors can temporarily stop answering Android's focus query after an
     * overlay window event, so the text-free identity from their focus event is used to resolve the same node again in the active application window.
     */
    private fun findFocusedEditor(): AccessibilityNodeInfo?
    {
        val applicationWindowEditor = windows.asSequence()
            .filter { window -> window.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .mapNotNull { window -> window.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }
            .firstOrNull(::isSupportedEditor)
        val activeWindowEditor = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf(::isSupportedEditor)
        return applicationWindowEditor ?: activeWindowEditor ?: resolveRememberedEditor()
    }

    /**
     * Resolves the remembered field without reading or retaining its text.
     */
    private fun resolveRememberedEditor(): AccessibilityNodeInfo?
    {
        val rememberedIdentity = focusedEditorIdentity ?: return null
        return windows.asSequence()
            .filter { window -> window.type == AccessibilityWindowInfo.TYPE_APPLICATION && (window.isActive || window.isFocused) }
            .mapNotNull(AccessibilityWindowInfo::getRoot)
            .mapNotNull { root -> findMatchingEditor(root, rememberedIdentity) }
            .firstOrNull()
    }

    private fun findMatchingEditor(root: AccessibilityNodeInfo, identity: AccessibilityFieldIdentity): AccessibilityNodeInfo?
    {
        val pendingNodes = ArrayDeque<AccessibilityNodeInfo>()
        pendingNodes.add(root)
        while (pendingNodes.isNotEmpty())
        {
            val node = pendingNodes.removeFirst()
            if (isSupportedEditor(node) && identity.representsSameEditor(createIdentity(node)))
            {
                return node
            }
            for (childIndex in 0 until node.childCount)
            {
                node.getChild(childIndex)?.let(pendingNodes::addLast)
            }
        }
        return null
    }

    private fun isSupportedEditor(node: AccessibilityNodeInfo): Boolean
    {
        return isSupportedAccessibilityEditor(
            isEditable = node.isEditable,
            isEnabled = node.isEnabled,
            isVisibleToUser = node.isVisibleToUser,
            setTextSupported = supportsAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT),
            pasteSupported = supportsAction(node, AccessibilityNodeInfo.ACTION_PASTE)
        )
    }

    private fun inspectSafety(node: AccessibilityNodeInfo) = securityInspector.inspect(
        AccessibilityEditorSecurityMetadata(
            inputType = node.inputType,
            isPassword = node.isPassword,
            hintText = node.hintText?.toString(),
            viewIdentifier = node.viewIdResourceName
        )
    )

    private fun createIdentity(node: AccessibilityNodeInfo): AccessibilityFieldIdentity
    {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return AccessibilityFieldIdentity(
            windowIdentifier = node.windowId,
            packageName = node.packageName?.toString().orEmpty(),
            className = node.className?.toString().orEmpty(),
            viewIdentifier = node.viewIdResourceName.orEmpty(),
            left = bounds.left,
            top = bounds.top,
            right = bounds.right,
            bottom = bounds.bottom
        )
    }

    /**
     * Shows the control beside non-sensitive editors only; connection state still determines the icon drawn inside the visible control.
     */
    private fun refreshControlPresentation(focusedEditor: AccessibilityNodeInfo? = findFocusedEditor())
    {
        val recordingActive = latestClientState.recordingState.isActive()
        val clientVisualState = latestClientState.visualState()
        val focusedEditorAllowsDictation = focusedEditor?.let(::inspectSafety)?.dictationAllowed == true
        floatingControl.update(clientVisualState, latestClientState.normalizedAudioLevel)
        floatingControl.visibility = if (shouldShowFloatingDictationControl(recordingActive, focusedEditor != null, focusedEditorAllowsDictation)) View.VISIBLE else View.GONE
        val undoRecord = lastInsertionUndo
        floatingUndoControl.visibility = if (!recordingActive && focusedEditorAllowsDictation && undoRecord != null &&
            undoRecord.fieldIdentity.representsSameEditor(createIdentity(focusedEditor))) View.VISIBLE else View.GONE
    }

    private fun cancelRecording()
    {
        recordingTouchActive = false
        recordingField = null
        pendingPaste = null
        if (::inferenceServiceClient.isInitialized)
        {
            inferenceServiceClient.cancelDictation()
            inferenceServiceClient.clearCompletedTranscript()
        }
    }

    private fun clearUndoAvailability()
    {
        lastInsertionUndo = null
        if (::floatingUndoControl.isInitialized)
        {
            floatingUndoControl.visibility = View.GONE
        }
    }

    private fun closeService()
    {
        if (closed)
        {
            return
        }
        closed = true
        cancelRecording()
        if (::inferenceServiceClient.isInitialized)
        {
            inferenceServiceClient.close()
        }
        if (::floatingControl.isInitialized)
        {
            floatingControl.removeCallbacks(lockFloatingControlForDictation)
            windowManager.removeView(floatingControl)
        }
        if (::floatingUndoControl.isInitialized)
        {
            windowManager.removeView(floatingUndoControl)
        }
        serviceScope.cancel()
    }

    private fun densityIndependentPixels(value: Int): Int
    {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun showMessage(message: String)
    {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun editableText(node: AccessibilityNodeInfo): String
    {
        val reportedText = node.text?.toString().orEmpty()
        val hintText = node.hintText?.toString()
        return resolveAccessibilityEditableText(
            reportedText = reportedText,
            hintText = hintText,
            isShowingHintText = node.isShowingHintText,
            selectionStart = node.textSelectionStart,
            selectionEnd = node.textSelectionEnd
        )
    }

    companion object
    {
        private const val SERVICE_LOG_TAG = "ClearDictateAccess"

        /**
         * Lets the setup screen reflect the system-owned service toggle without reading secure settings directly.
         */
        fun isEnabled(context: Context): Boolean
        {
            val expectedComponent = ComponentName(context, ClearDictateAccessibilityService::class.java)
            return context.getSystemService(AccessibilityManager::class.java)
                .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { serviceInfo ->
                    val actualComponent = ComponentName(serviceInfo.resolveInfo.serviceInfo.packageName, serviceInfo.resolveInfo.serviceInfo.name)
                    actualComponent == expectedComponent
                }
        }
    }

    private fun supportsAction(node: AccessibilityNodeInfo, actionIdentifier: Int): Boolean
    {
        return node.actionList.any { action -> action.id == actionIdentifier }
    }
}

/**
 * Excludes a platform-reported placeholder while preserving actual text whenever the editor exposes a valid cursor.
 */
internal fun resolveAccessibilityEditableText(reportedText: String, hintText: String?, isShowingHintText: Boolean, selectionStart: Int, selectionEnd: Int): String
{
    val cursorUnavailable = selectionStart < 0 || selectionEnd < 0
    val unmarkedHint = cursorUnavailable && !hintText.isNullOrEmpty() && reportedText == hintText
    return if (isShowingHintText || unmarkedHint) "" else reportedText
}

/**
 * Selects native paste only when an editor with paste support hides its cursor.
 */
internal fun shouldUseNativePaste(selectionStart: Int, selectionEnd: Int, pasteSupported: Boolean, setTextSupported: Boolean, isEditable: Boolean): Boolean
{
    val pasteCanBeAttempted = pasteSupported || (isEditable && !setTextSupported)
    if (!pasteCanBeAttempted)
    {
        return false
    }
    val selectionUnavailable = selectionStart < 0 || selectionEnd < 0
    return !setTextSupported || selectionUnavailable
}

/**
 * Uses Android's advertised insertion actions instead of the unreliable editable flag used by some custom document editors.
 */
internal fun isSupportedAccessibilityEditor(isEditable: Boolean, isEnabled: Boolean, isVisibleToUser: Boolean, setTextSupported: Boolean, pasteSupported: Boolean): Boolean
{
    return isEnabled && isVisibleToUser && (isEditable || setTextSupported || pasteSupported)
}

/**
 * Keeps the overlay visible through an active recording but otherwise excludes non-editors and sensitive editors.
 */
internal fun shouldShowFloatingDictationControl(recordingActive: Boolean, editorSupported: Boolean, dictationAllowed: Boolean): Boolean
{
    return recordingActive || (editorSupported && dictationAllowed)
}

/**
 * Recognizes WhatsApp's genuinely empty composer without trusting the placeholder text exposed through accessibility.
 */
internal fun isWhatsAppEmptyComposer(packageName: String, viewIdentifier: String, voiceNoteControlVisible: Boolean): Boolean
{
    return packageName == WHATSAPP_PACKAGE_NAME && viewIdentifier == WHATSAPP_COMPOSER_VIEW_IDENTIFIER && voiceNoteControlVisible
}

private fun InferenceClientState.isReadyForDictation(): Boolean
{
    return connectionState == InferenceConnectionState.CONNECTED && pcConnectionState == PcConnectionState.CONNECTED &&
        speechModelState == SpeechModelState.READY && recordingState == ClientRecordingState.IDLE
}

/**
 * Gives connection loss priority so the overlay never resembles an available microphone after the PC link drops.
 */
internal fun InferenceClientState.visualState(): FloatingDictationVisualState
{
    if (connectionState != InferenceConnectionState.CONNECTED || pcConnectionState == PcConnectionState.DISCONNECTED)
    {
        return FloatingDictationVisualState.DISCONNECTED
    }
    if (pcConnectionState != PcConnectionState.CONNECTED)
    {
        return FloatingDictationVisualState.UNAVAILABLE
    }
    return when (recordingState)
    {
        ClientRecordingState.PREPARING, ClientRecordingState.LISTENING, ClientRecordingState.SPEECH_DETECTED -> FloatingDictationVisualState.RECORDING
        ClientRecordingState.FINALIZING -> FloatingDictationVisualState.PROCESSING
        ClientRecordingState.IDLE -> if (isReadyForDictation()) FloatingDictationVisualState.READY else FloatingDictationVisualState.UNAVAILABLE
        ClientRecordingState.ERROR -> FloatingDictationVisualState.UNAVAILABLE
    }
}

private fun ClientRecordingState.isActive(): Boolean
{
    return this == ClientRecordingState.PREPARING || this == ClientRecordingState.LISTENING || this == ClientRecordingState.SPEECH_DETECTED || this == ClientRecordingState.FINALIZING
}

private const val WHATSAPP_PACKAGE_NAME = "com.whatsapp"
private const val WHATSAPP_COMPOSER_VIEW_IDENTIFIER = "com.whatsapp:id/entry"
private const val WHATSAPP_VOICE_NOTE_VIEW_IDENTIFIER = "com.whatsapp:id/voice_note_btn"
