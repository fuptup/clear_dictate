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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Lists retained dictations, filters them by the user's local date, and plays the WAV attached to any clicked row.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun ClearDictateHistoryScreen(history: SqliteDesktopDictationHistory)
{
    MaterialTheme {
        val scope = rememberCoroutineScope()
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
            Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
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
                    HistoryDateFilter(dates, selectedDate, Modifier.width(190.dp)) { date ->
                        selectedDate = date
                        selectedIdentifier = null
                        correctionDraft = ""
                    }
                    OutlinedButton(onClick = { refreshSequence += 1 }, modifier = Modifier.padding(start = 10.dp).height(42.dp)) {
                        Text("Refresh")
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxWidth().weight(1.0F).padding(top = 14.dp),
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
                                    HistoryEntryRow(entry, entry.identifier == selectedIdentifier, zoneId) {
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
                    "Click a row to play its recording and review its correction target. Times are shown in your PC's local time.",
                    modifier = Modifier.padding(top = 10.dp),
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
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = correctionDraft,
                onValueChange = onCorrectionChanged,
                modifier = Modifier.weight(1.0F).heightIn(min = 86.dp),
                label = { Text("Reviewed correction") },
                supportingText = { Text("Stored separately from the ASR and polished outputs.") },
                maxLines = 3
            )
            Button(onClick = onSave, enabled = saveEnabled && !saving, modifier = Modifier.padding(start = 12.dp).height(44.dp)) {
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
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
        HistoryCell("Date / time", 1.05F, fontWeight = FontWeight.SemiBold)
        HistoryCell("Qwen3-ASR", 2.2F, fontWeight = FontWeight.SemiBold)
        HistoryCell("Qwen3.5 polished", 2.2F, fontWeight = FontWeight.SemiBold)
        HistoryCell("Reviewed correction", 2.2F, fontWeight = FontWeight.SemiBold)
        HistoryCell("ASR", 0.75F, fontWeight = FontWeight.SemiBold)
        HistoryCell("Total", 0.75F, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun HistoryEntryRow(entry: StoredDictationSummary, selected: Boolean, zoneId: ZoneId, onClick: () -> Unit)
{
    Surface(color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier.fillMaxWidth().height(72.dp).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val localCaptureTime = entry.recordedAt.atZone(zoneId)
            HistoryCell("${localCaptureTime.format(DATE_FORMATTER)}\n${localCaptureTime.format(TIME_FORMATTER)}", 1.05F)
            HistoryCell(entry.rawTranscript, 2.2F)
            HistoryCell(entry.polishedTranscript, 2.2F)
            HistoryCell(entry.correctedTranscript ?: "—", 2.2F)
            HistoryCell("${entry.timing.recognitionMilliseconds} ms", 0.75F)
            HistoryCell("${entry.timing.totalMilliseconds} ms", 0.75F)
        }
    }
}

@Composable
private fun RowScope.HistoryCell(text: String, weight: Float, fontWeight: FontWeight? = null)
{
    Text(
        text = text,
        modifier = Modifier.weight(weight).padding(end = 12.dp),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = fontWeight
    )
}

@Composable
@ExperimentalMaterial3Api
private fun HistoryDateFilter(dates: List<LocalDate>, selectedDate: LocalDate?, modifier: Modifier, onSelected: (LocalDate?) -> Unit)
{
    var expanded by remember { mutableStateOf(false) }
    val label = selectedDate?.format(DATE_FORMATTER) ?: "All dates"
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        Surface(
            modifier = modifier.height(42.dp).menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true),
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
private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
