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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
        var refreshSequence by remember { mutableIntStateOf(0) }
        var loading by remember { mutableStateOf(true) }
        var status by remember { mutableStateOf("") }
        val zoneId = remember { ZoneId.systemDefault() }
        val dates = remember(entries, zoneId) { entries.map { entry -> entry.localDate(zoneId) }.distinct().sortedDescending() }
        val visibleEntries = remember(entries, selectedDate, zoneId) { filterHistoryEntries(entries, selectedDate, zoneId) }

        DisposableEffect(audioPlayer)
        {
            onDispose(audioPlayer::close)
        }

        LaunchedEffect(history, refreshSequence)
        {
            loading = true
            status = ""
            runCatching { history.readSummaries() }
                .onSuccess { loadedEntries -> entries = loadedEntries }
                .onFailure { status = "Could not load dictation history." }
            loading = false
        }

        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1.0F)) {
                        Text("Dictation history", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (loading) "Loading records..." else "${visibleEntries.size} ${if (visibleEntries.size == 1) "record" else "records"}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    HistoryDateFilter(dates, selectedDate, Modifier.width(190.dp)) { date ->
                        selectedDate = date
                        selectedIdentifier = null
                    }
                    OutlinedButton(onClick = { refreshSequence += 1 }, modifier = Modifier.padding(start = 10.dp).height(42.dp)) {
                        Text("Refresh")
                    }
                }

                if (status.isNotEmpty())
                {
                    Text(status, modifier = Modifier.padding(top = 10.dp), color = MaterialTheme.colorScheme.error)
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
                                        status = ""
                                        scope.launch {
                                            runCatching { audioPlayer.play(entry.identifier) }
                                                .onFailure { status = "Could not play the selected recording." }
                                        }
                                    }
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                }

                Text(
                    "Click a row to play its recording. Times are shown in your PC's local time.",
                    modifier = Modifier.padding(top = 10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
