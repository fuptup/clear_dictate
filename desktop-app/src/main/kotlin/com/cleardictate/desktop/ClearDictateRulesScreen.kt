package com.cleardictate.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cleardictate.domain.SpokenFormattingSpacing
import kotlinx.coroutines.launch

/**
 * Creates, edits, and deletes literal custom spoken-formatting rules that apply immediately to every desktop and phone dictation.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun ClearDictateRulesScreen(store: SqliteDesktopSpokenFormattingRuleStore)
{
    MaterialTheme {
        val scope = rememberCoroutineScope()
        var rules by remember { mutableStateOf<List<StoredSpokenFormattingRule>>(emptyList()) }
        var selectedIdentifier by remember { mutableStateOf<Long?>(null) }
        var spokenPhrase by remember { mutableStateOf("") }
        var replacement by remember { mutableStateOf("") }
        var spacing by remember { mutableStateOf(SpokenFormattingSpacing.PRESERVE) }
        var consumesRecognizerPunctuation by remember { mutableStateOf(false) }
        var loading by remember { mutableStateOf(true) }
        var busy by remember { mutableStateOf(false) }
        var status by remember { mutableStateOf("") }
        var statusIsError by remember { mutableStateOf(false) }

        fun resetEditor()
        {
            selectedIdentifier = null
            spokenPhrase = ""
            replacement = ""
            spacing = SpokenFormattingSpacing.PRESERVE
            consumesRecognizerPunctuation = false
        }

        LaunchedEffect(store)
        {
            runCatching { store.readAll() }
                .onSuccess { loadedRules -> rules = loadedRules }
                .onFailure {
                    status = "Could not load custom rules."
                    statusIsError = true
                }
            loading = false
        }

        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
                Text("Custom formatting rules", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "Literal, case-insensitive phrases. Changes apply to the next PC or phone dictation.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Surface(
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                    shape = MaterialTheme.shapes.medium,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(
                                value = spokenPhrase,
                                onValueChange = { spokenPhrase = it },
                                modifier = Modifier.weight(1.0F),
                                label = { Text("When I say") },
                                placeholder = { Text("per cent") },
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = replacement,
                                onValueChange = { replacement = it },
                                modifier = Modifier.width(190.dp),
                                label = { Text("Write") },
                                placeholder = { Text("%") },
                                singleLine = true
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            RuleSpacingDropdown(spacing, Modifier.width(230.dp)) { selectedSpacing -> spacing = selectedSpacing }
                            Row(modifier = Modifier.weight(1.0F), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = consumesRecognizerPunctuation, onCheckedChange = { consumesRecognizerPunctuation = it })
                                Text("Remove automatic punctuation after phrase", style = MaterialTheme.typography.bodySmall)
                            }
                            OutlinedButton(onClick = {
                                resetEditor()
                                status = ""
                                statusIsError = false
                            }, enabled = !busy) {
                                Text("New")
                            }
                            Button(
                                onClick = {
                                    busy = true
                                    status = ""
                                    statusIsError = false
                                    scope.launch {
                                        runCatching {
                                            store.save(selectedIdentifier, spokenPhrase, replacement, spacing, consumesRecognizerPunctuation)
                                            store.readAll()
                                        }.onSuccess { loadedRules ->
                                            rules = loadedRules
                                            resetEditor()
                                            status = "Rule saved."
                                        }.onFailure {
                                            status = "Could not save the rule. Check that the phrase is unique and both fields are filled in."
                                            statusIsError = true
                                        }
                                        busy = false
                                    }
                                },
                                enabled = !busy && spokenPhrase.isNotBlank() && replacement.isNotEmpty()
                            ) {
                                Text(if (selectedIdentifier == null) "Add rule" else "Save")
                            }
                        }
                    }
                }

                Text(
                    if (status.isNotEmpty()) status else "${rules.size} custom ${if (rules.size == 1) "rule" else "rules"}",
                    modifier = Modifier.padding(top = 10.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (statusIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )

                Surface(
                    modifier = Modifier.fillMaxWidth().weight(1.0F),
                    shape = MaterialTheme.shapes.medium,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    when
                    {
                        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        rules.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No custom rules yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(rules, key = StoredSpokenFormattingRule::identifier) { rule ->
                                CustomRuleRow(
                                    rule,
                                    busy,
                                    onEdit = {
                                        selectedIdentifier = rule.identifier
                                        spokenPhrase = rule.spokenPhrase
                                        replacement = rule.replacement
                                        spacing = rule.spacing
                                        consumesRecognizerPunctuation = rule.consumesRecognizerPunctuation
                                        status = ""
                                        statusIsError = false
                                    },
                                    onDelete = {
                                        busy = true
                                        scope.launch {
                                            runCatching {
                                                store.delete(rule.identifier)
                                                store.readAll()
                                            }.onSuccess { loadedRules ->
                                                rules = loadedRules
                                                if (selectedIdentifier == rule.identifier)
                                                {
                                                    resetEditor()
                                                }
                                                status = "Rule deleted."
                                                statusIsError = false
                                            }.onFailure {
                                                status = "Could not delete the rule."
                                                statusIsError = true
                                            }
                                            busy = false
                                        }
                                    }
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Presents one saved rule with compact edit and delete actions.
 */
@Composable
private fun CustomRuleRow(rule: StoredSpokenFormattingRule, busy: Boolean, onEdit: () -> Unit, onDelete: () -> Unit)
{
    Row(modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(rule.spokenPhrase, modifier = Modifier.weight(1.4F), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("→", modifier = Modifier.padding(horizontal = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(rule.replacement, modifier = Modifier.weight(0.8F), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
        Text(rule.spacing.displayName(), modifier = Modifier.weight(1.2F), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onEdit, enabled = !busy) { Text("Edit") }
        TextButton(onClick = onDelete, enabled = !busy) { Text("Delete") }
    }
}

/**
 * Chooses how the replacement joins adjacent dictated text.
 */
@Composable
@ExperimentalMaterial3Api
private fun RuleSpacingDropdown(selected: SpokenFormattingSpacing, modifier: Modifier, onSelected: (SpokenFormattingSpacing) -> Unit)
{
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected.displayName(),
            onValueChange = {},
            readOnly = true,
            modifier = modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true),
            label = { Text("Spacing") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            singleLine = true
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SpokenFormattingSpacing.entries.forEach { spacing ->
                DropdownMenuItem(text = { Text(spacing.displayName()) }, onClick = {
                    onSelected(spacing)
                    expanded = false
                })
            }
        }
    }
}

internal fun SpokenFormattingSpacing.displayName(): String
{
    return when (this)
    {
        SpokenFormattingSpacing.PRESERVE -> "Keep spaces"
        SpokenFormattingSpacing.ATTACH_LEFT -> "Attach left"
        SpokenFormattingSpacing.ATTACH_RIGHT -> "Attach right"
        SpokenFormattingSpacing.ATTACH_BOTH -> "Attach both"
    }
}
