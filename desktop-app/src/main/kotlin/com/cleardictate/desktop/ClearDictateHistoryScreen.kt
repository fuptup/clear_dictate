package com.cleardictate.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.awt.datatransfer.StringSelection
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Lists retained dictations, filters them by the user's local date, and plays the WAV attached to any clicked row.
 */
@Composable
@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
fun ClearDictateHistoryScreen(history: SqliteDesktopDictationHistory)
{
    MaterialTheme {
        val scope = rememberCoroutineScope()
        val clipboard = LocalClipboard.current
        val audioPlayer = remember(history) { DesktopDictationAudioPlayer(history) }
        var entries by remember { mutableStateOf<List<StoredDictationSummary>>(emptyList()) }
        var selectedDate by remember { mutableStateOf<LocalDate?>(null) }
        var selectedIdentifier by remember { mutableStateOf<Long?>(null) }
        var correctionDraft by remember { mutableStateOf("") }
        var refreshSequence by remember { mutableIntStateOf(0) }
        var loading by remember { mutableStateOf(true) }
        var savingCorrection by remember { mutableStateOf(false) }
        var status by remember { mutableStateOf("") }
        var statusIsError by remember { mutableStateOf(false) }
        val zoneId = remember { ZoneId.systemDefault() }
        val dates = remember(entries, zoneId) { entries.map { entry -> entry.localDate(zoneId) }.distinct().sortedDescending() }
        val visibleEntries = remember(entries, selectedDate, zoneId) { filterHistoryEntries(entries, selectedDate, zoneId) }
        val selectedEntry = remember(entries, selectedIdentifier) { entries.firstOrNull { entry -> entry.identifier == selectedIdentifier } }

        DisposableEffect(audioPlayer)
        {
            onDispose(audioPlayer::close)
        }

        LaunchedEffect(history, refreshSequence)
        {
            loading = true
            status = ""
            statusIsError = false
            runCatching { history.readSummaries() }
                .onSuccess { loadedEntries -> entries = loadedEntries }
                .onFailure {
                    status = "Could not load dictation history."
                    statusIsError = true
                }
            loading = false
        }

        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1.0F)) {
                        Text("Dictation history", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            historySubtitle(loading, visibleEntries.size, status),
                            style = MaterialTheme.typography.bodyMedium,
                            color = when
                            {
                                status.isEmpty() -> MaterialTheme.colorScheme.onSurfaceVariant
                                statusIsError -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.primary
                            }
                        )
                    }
                    HistoryDateFilter(dates, selectedDate, Modifier.width(170.dp)) { date ->
                        selectedDate = date
                        selectedIdentifier = null
                        correctionDraft = ""
                    }
                    OutlinedButton(onClick = { refreshSequence += 1 }, modifier = Modifier.padding(start = 6.dp).height(36.dp)) {
                        Text("Refresh")
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxWidth().weight(1.0F).padding(top = 8.dp),
                    shape = MaterialTheme.shapes.medium,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        HistoryHeaderRow()
                        HorizontalDivider()
                        when
                        {
                            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                            visibleEntries.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("No saved dictations for this date.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            else -> LazyColumn(modifier = Modifier.fillMaxHeight()) {
                                items(visibleEntries, key = StoredDictationSummary::identifier) { entry ->
                                    HistoryEntryRow(
                                        entry = entry,
                                        selected = entry.identifier == selectedIdentifier,
                                        zoneId = zoneId,
                                        onCopy = { label, text ->
                                            scope.launch {
                                                clipboard.setClipEntry(ClipEntry(StringSelection(text)))
                                                status = "$label copied."
                                                statusIsError = false
                                            }
                                        },
                                        onClick = {
                                            selectedIdentifier = entry.identifier
                                            correctionDraft = entry.correctedTranscript ?: entry.polishedTranscript
                                            status = ""
                                            statusIsError = false
                                            scope.launch {
                                                runCatching { audioPlayer.play(entry.identifier) }
                                                    .onFailure {
                                                        status = "Could not play the selected recording."
                                                        statusIsError = true
                                                    }
                                            }
                                        }
                                    )
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                }

                if (selectedEntry != null)
                {
                    HistoryCorrectionEditor(
                        correctionDraft = correctionDraft,
                        saving = savingCorrection,
                        saveEnabled = correctionDraft.isNotBlank() && correctionDraft.trim() != selectedEntry.correctedTranscript,
                        onCorrectionChanged = { correctionDraft = it },
                        onSave = {
                            savingCorrection = true
                            status = ""
                            statusIsError = false
                            scope.launch {
                                runCatching {
                                    history.saveCorrection(selectedEntry.identifier, correctionDraft)
                                    history.readSummaries()
                                }.onSuccess { loadedEntries ->
                                    entries = loadedEntries
                                    correctionDraft = loadedEntries.first { entry -> entry.identifier == selectedEntry.identifier }.correctedTranscript.orEmpty()
                                    status = "Correction saved."
                                    statusIsError = false
                                }.onFailure {
                                    status = "Could not save the correction."
                                    statusIsError = true
                                }
                                savingCorrection = false
                            }
                        }
                    )
                }

                Text(
                    "Click transcript text to copy it; click elsewhere on a row to play its recording.",
                    modifier = Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Keeps transient status inside the fixed-height header so selecting or saving a record never shifts the editor off-screen.
 */
internal fun historySubtitle(loading: Boolean, visibleEntryCount: Int, status: String): String
{
    if (status.isNotEmpty())
    {
        return status
    }
    if (loading)
    {
        return "Loading records..."
    }
    return "$visibleEntryCount ${if (visibleEntryCount == 1) "record" else "records"}"
}

/**
 * Edits the human-reviewed target separately from immutable model output so training examples retain their provenance.
 */
@Composable
private fun HistoryCorrectionEditor(correctionDraft: String, saving: Boolean, saveEnabled: Boolean, onCorrectionChanged: (String) -> Unit, onSave: () -> Unit)
{
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = correctionDraft,
                onValueChange = onCorrectionChanged,
                modifier = Modifier.weight(1.0F).heightIn(min = 60.dp),
                label = { Text("Reviewed correction") },
                maxLines = 2
            )
            Button(onClick = onSave, enabled = saveEnabled && !saving, modifier = Modifier.padding(start = 8.dp).height(36.dp)) {
                Text(if (saving) "Saving…" else "Save correction")
            }
        }
    }
}

/**
 * Keeps date filtering deterministic and independently testable from Compose rendering.
 */
internal fun filterHistoryEntries(entries: List<StoredDictationSummary>, selectedDate: LocalDate?, zoneId: ZoneId): List<StoredDictationSummary>
{
    return if (selectedDate == null) entries else entries.filter { entry -> entry.localDate(zoneId) == selectedDate }
}

private fun StoredDictationSummary.localDate(zoneId: ZoneId): LocalDate
{
    return recordedAt.atZone(zoneId).toLocalDate()
}

@Composable
private fun HistoryHeaderRow()
{
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
        HistoryCell("Date / time", 1.35F, maxLines = 1, fontWeight = FontWeight.SemiBold)
        HistoryCell("Qwen3-ASR", 2.2F, fontWeight = FontWeight.SemiBold)
        HistoryCell("Qwen3.5 polished", 2.2F, fontWeight = FontWeight.SemiBold)
        HistoryCell("Reviewed correction", 2.0F, fontWeight = FontWeight.SemiBold)
        HistoryCell("Audio", 0.75F, fontWeight = FontWeight.SemiBold)
        HistoryCell("ASR", 0.75F, fontWeight = FontWeight.SemiBold)
        HistoryCell("Total", 0.75F, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun HistoryEntryRow(entry: StoredDictationSummary, selected: Boolean, zoneId: ZoneId, onCopy: (String, String) -> Unit, onClick: () -> Unit)
{
    Surface(color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier.fillMaxWidth().height(54.dp).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val localCaptureTime = entry.recordedAt.atZone(zoneId)
            HistoryCell(localCaptureTime.format(HISTORY_TIMESTAMP_FORMATTER), 1.35F, maxLines = 1)
            HistoryCopyCell(entry.rawTranscript, 2.2F) { onCopy("ASR text", entry.rawTranscript) }
            HistoryCopyCell(entry.polishedTranscript, 2.2F) { onCopy("Polished text", entry.polishedTranscript) }
            HistoryCopyCell(entry.correctedTranscript ?: "—", 2.0F, enabled = entry.correctedTranscript != null) {
                onCopy("Reviewed correction", entry.correctedTranscript.orEmpty())
            }
            HistoryCell(formatAudioDuration(entry.audioDurationMilliseconds), 0.75F, maxLines = 1)
            HistoryCell("${entry.timing.recognitionMilliseconds} ms", 0.75F, maxLines = 1)
            HistoryCell("${entry.timing.totalMilliseconds} ms", 0.75F, maxLines = 1)
        }
    }
}

/**
 * Shows sampled-audio length in seconds while keeping the compact History column easy to scan.
 */
internal fun formatAudioDuration(durationMilliseconds: Long): String
{
    return String.format(Locale.ROOT, "%.2f s", durationMilliseconds / 1_000.0)
}

@Composable
private fun RowScope.HistoryCell(text: String, weight: Float, maxLines: Int = 2, fontWeight: FontWeight? = null)
{
    Text(
        text = text,
        modifier = Modifier.weight(weight).padding(end = 8.dp),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = fontWeight
    )
}

/**
 * Copies exactly one transcript column while hover styling makes the click target unambiguous from the row's audio action.
 */
@Composable
@OptIn(ExperimentalComposeUiApi::class)
private fun RowScope.HistoryCopyCell(text: String, weight: Float, enabled: Boolean = true, onCopy: () -> Unit)
{
    var hovered by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.weight(weight).fillMaxHeight().padding(end = 6.dp)
            .onPointerEvent(PointerEventType.Enter) { hovered = enabled }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .clickable(enabled = enabled, onClick = onCopy),
        shape = MaterialTheme.shapes.small,
        color = if (hovered) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface.copy(alpha = 0.0F)
    ) {
        Row(modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, modifier = Modifier.weight(1.0F), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            if (hovered)
            {
                Text("Copy", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
@ExperimentalMaterial3Api
private fun HistoryDateFilter(dates: List<LocalDate>, selectedDate: LocalDate?, modifier: Modifier, onSelected: (LocalDate?) -> Unit)
{
    var expanded by remember { mutableStateOf(false) }
    val label = selectedDate?.format(DATE_FORMATTER) ?: "All dates"
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        Surface(
            modifier = modifier.height(36.dp).menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true),
            shape = MaterialTheme.shapes.small,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Row(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, modifier = Modifier.weight(1.0F), maxLines = 1, overflow = TextOverflow.Ellipsis)
                ExposedDropdownMenuDefaults.TrailingIcon(expanded)
            }
        }
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("All dates") }, onClick = {
                onSelected(null)
                expanded = false
            })
            dates.forEach { date ->
                DropdownMenuItem(text = { Text(date.format(DATE_FORMATTER)) }, onClick = {
                    onSelected(date)
                    expanded = false
                })
            }
        }
    }
}

private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
private val HISTORY_TIMESTAMP_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("dd MMM yy HH:mm:ss")
