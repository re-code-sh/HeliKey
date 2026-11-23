// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.ForcedAutocorrection
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.SearchScreen
import helium314.keyboard.settings.dialogs.ThreeButtonAlertDialog

@Composable
fun ForcedAutocorrectionsScreen(
    onClickBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    var entries by remember { mutableStateOf(ForcedAutocorrection.load(prefs)) }
    var disabled by remember { mutableStateOf(ForcedAutocorrection.disabledTriggers(prefs)) }
    var selectedEntry: Pair<String, String>? by remember { mutableStateOf(null) }

    fun refresh() {
        entries = ForcedAutocorrection.load(prefs)
        disabled = ForcedAutocorrection.disabledTriggers(prefs)
    }

    SearchScreen(
        onClickBack = onClickBack,
        title = {
            Column {
                Text(stringResource(R.string.forced_autocorrection_screen_title))
                Text(
                    stringResource(R.string.forced_autocorrection_screen_summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        filteredItems = { term ->
            entries.toList().sortedBy { it.first }.filter {
                term.isBlank() || it.first.contains(term, true) || it.second.contains(term, true)
            }
        },
        itemContent = { entry ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { selectedEntry = entry }
                    .padding(vertical = 6.dp, horizontal = 16.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text(entry.first, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "→ ${entry.second}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = entry.first !in disabled,
                    onCheckedChange = { checked ->
                        ForcedAutocorrection.setTriggerDisabled(prefs, entry.first, !checked)
                        refresh()
                    }
                )
            }
        }
    )
    if (selectedEntry != null) {
        EditForcedCorrectionDialog(selectedEntry!!, entries) {
            selectedEntry = null
            refresh()
        }
    }
    ExtendedFloatingActionButton(
        onClick = { selectedEntry = "" to "" },
        text = { Text(stringResource(R.string.forced_autocorrection_add_button)) },
        icon = { Icon(painter = painterResource(R.drawable.ic_plus), stringResource(R.string.forced_autocorrection_add_button)) },
        modifier = Modifier.wrapContentSize(Alignment.BottomEnd).padding(all = 12.dp)
            .then(Modifier.safeDrawingPadding())
    )
}

@Composable
private fun EditForcedCorrectionDialog(
    entry: Pair<String, String>,
    entries: Map<String, String>,
    onDismissRequest: () -> Unit
) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    val focusRequester = remember { FocusRequester() }
    var trigger by remember { mutableStateOf(entry.first) }
    var replacement by remember { mutableStateOf(entry.second) }
    val isNew = entry.first.isEmpty()
    val normalizedTrigger = ForcedAutocorrection.normalizeTrigger(trigger)
    val normalizedReplacement = ForcedAutocorrection.normalizeTrigger(replacement)
    // a -> b together with b -> a would correct forever, so don't allow it
    val createsLoop = normalizedTrigger.isNotEmpty() && normalizedReplacement.isNotEmpty()
        && normalizedReplacement != normalizedTrigger
        && entries.any { (existingTrigger, existingReplacement) ->
            existingTrigger != normalizedTrigger
                && existingTrigger == normalizedReplacement
                && ForcedAutocorrection.normalizeTrigger(existingReplacement) == normalizedTrigger
        }

    fun save() {
        val newEntries = entries.toMutableMap()
        if (!isNew && entry.first != normalizedTrigger) {
            // trigger changed: drop the old entry (and its disabled state, if any)
            newEntries.remove(entry.first)
            if (entry.first in ForcedAutocorrection.disabledTriggers(prefs)) {
                ForcedAutocorrection.setTriggerDisabled(prefs, entry.first, false)
                ForcedAutocorrection.setTriggerDisabled(prefs, normalizedTrigger, true)
            }
        }
        newEntries[normalizedTrigger] = replacement.trim()
        ForcedAutocorrection.save(prefs, newEntries)
    }

    fun delete() {
        val newEntries = entries.toMutableMap()
        newEntries.remove(entry.first)
        ForcedAutocorrection.save(prefs, newEntries)
        if (entry.first in ForcedAutocorrection.disabledTriggers(prefs)) {
            ForcedAutocorrection.setTriggerDisabled(prefs, entry.first, false)
        }
    }

    ThreeButtonAlertDialog(
        onDismissRequest = onDismissRequest,
        onConfirmed = { save() },
        checkOk = { trigger.isNotBlank() && replacement.isNotBlank() && !createsLoop },
        confirmButtonText = stringResource(R.string.save),
        neutralButtonText = if (isNew) null else stringResource(R.string.delete),
        onNeutral = {
            delete()
            onDismissRequest()
        },
        title = { Text(stringResource(R.string.forced_autocorrection_screen_title)) },
        content = {
            LaunchedEffect(entry) {
                if (isNew) focusRequester.requestFocus()
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(
                    value = trigger,
                    onValueChange = { trigger = it },
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                    singleLine = true,
                    label = { Text(stringResource(R.string.forced_autocorrection_trigger_hint)) },
                    keyboardActions = KeyboardActions {
                        if (trigger.isNotBlank() && replacement.isNotBlank() && !createsLoop) {
                            save()
                            onDismissRequest()
                        }
                    }
                )
                TextField(
                    value = replacement,
                    onValueChange = { replacement = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.forced_autocorrection_replacement_hint)) },
                    keyboardActions = KeyboardActions {
                        if (trigger.isNotBlank() && replacement.isNotBlank() && !createsLoop) {
                            save()
                            onDismissRequest()
                        }
                    }
                )
                if (createsLoop) {
                    Text(
                        stringResource(R.string.forced_autocorrection_loop_warning),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    )
}
