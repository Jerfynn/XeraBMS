@file:OptIn(ExperimentalMaterial3Api::class)

package com.xera.bms.ui.params

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xera.bms.core.BmsAction
import com.xera.bms.core.BmsParameter
import com.xera.bms.core.Capability
import com.xera.bms.core.ParamType
import com.xera.bms.ui.MainViewModel
import com.xera.bms.ui.components.EmptyState
import com.xera.bms.ui.components.SectionCard
import com.xera.bms.ui.more.BatteryOutputsCard
import com.xera.bms.ui.theme.fmt

private val TAB_ORDER = listOf("Protection", "Battery", "Temperature", "Boards", "Device")
private const val DEVICE_TAB = "Device"

@Composable
fun ParametersScreen(vm: MainViewModel) {
    val params by vm.session.parameters.collectAsStateWithLifecycle()
    val values by vm.session.paramValues.collectAsStateWithLifecycle()
    val texts by vm.session.paramTexts.collectAsStateWithLifecycle()
    val caps by vm.session.capabilities.collectAsStateWithLifecycle()
    val actions by vm.session.actions.collectAsStateWithLifecycle()
    val busy by vm.session.busy.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

    var unlocked by rememberSaveable { mutableStateOf(false) }
    var askPin by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<BmsParameter?>(null) }
    var pendingAction by remember { mutableStateOf<BmsAction?>(null) }
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }

    val canWrite = caps.contains(Capability.PARAM_WRITE)
    val hasOutputs = caps.contains(Capability.MOSFET_CONTROL) || caps.contains(Capability.BALANCER_CONTROL)

    LaunchedEffect(params) {
        if (params.isNotEmpty() && values.isEmpty() && caps.contains(Capability.PARAM_READ)) vm.session.readParameters()
    }

    val tabs = remember(params, actions, hasOutputs) {
        val names = params.map { it.group } + if (hasOutputs || actions.isNotEmpty()) listOf(DEVICE_TAB) else emptyList()
        names.distinct().sortedBy { name -> TAB_ORDER.indexOf(name).let { if (it < 0) TAB_ORDER.size else it } }
    }
    if (tabs.isEmpty()) {
        EmptyState(
            icon = Icons.Filled.Tune,
            title = "No settings available",
            body = "This BMS type doesn't expose its settings to XeraBMS.",
            modifier = Modifier.fillMaxSize(),
        )
        return
    }
    val selected = tabIndex.coerceIn(0, tabs.lastIndex)
    val tab = tabs[selected]

    Column(Modifier.fillMaxSize()) {
        ScrollableTabRow(
            selectedTabIndex = selected,
            edgePadding = 12.dp,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            tabs.forEachIndexed { i, name ->
                Tab(selected = i == selected, onClick = { tabIndex = i }, text = { Text(name) })
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalButton(onClick = vm.session::readParameters, enabled = busy == null && caps.contains(Capability.PARAM_READ)) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Read from BMS")
            }
            if (canWrite || actions.isNotEmpty()) {
                OutlinedButton(onClick = { if (unlocked) unlocked = false else askPin = true }) {
                    Icon(if (unlocked) Icons.Filled.LockOpen else Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (unlocked) "Lock" else "Unlock to edit")
                }
            }
        }
        busy?.let {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp))
            }
        }

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (tab == DEVICE_TAB && hasOutputs) item(key = "outputs") { BatteryOutputsCard(vm) }

            params.filter { it.group == tab }
                .groupBy { it.section.ifEmpty { tab } }
                .forEach { (section, list) ->
                    item(key = "$tab/$section") {
                        SectionCard(title = section) {
                            list.forEachIndexed { i, p ->
                                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                val known = if (p.type == ParamType.NUMBER) values[p.key] != null else texts[p.key] != null
                                ParamRow(
                                    param = p,
                                    value = valueText(p, values, texts),
                                    editable = unlocked && canWrite && p.writable && p.type != ParamType.INFO && busy == null &&
                                        (known || p.type == ParamType.TEXT),
                                    onEdit = { editing = p },
                                )
                            }
                        }
                    }
                }

            if (tab == DEVICE_TAB && actions.isNotEmpty()) {
                item(key = "actions") {
                    SectionCard(title = "Maintenance") {
                        actions.forEach { a ->
                            Text(a.label, style = MaterialTheme.typography.bodyLarge)
                            Text(a.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedButton(
                                onClick = { pendingAction = a },
                                enabled = unlocked && busy == null,
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            ) { Text(a.label) }
                        }
                        if (!unlocked) {
                            Text(
                                "Unlock editing to use these.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    if (askPin) {
        PinDialog(
            expected = settings.paramPin,
            onDismiss = { askPin = false },
            onUnlocked = {
                askPin = false
                unlocked = true
            },
        )
    }
    editing?.let { p ->
        EditDialog(
            param = p,
            currentNumber = values[p.key],
            currentText = texts[p.key],
            onDismiss = { editing = null },
            onSaveNumber = { v ->
                editing = null
                vm.session.writeParameter(p, v)
            },
            onSaveText = { t ->
                editing = null
                vm.session.writeText(p, t)
            },
        )
    }
    pendingAction?.let { a ->
        ActionDialog(
            action = a,
            onDismiss = { pendingAction = null },
            onConfirm = {
                pendingAction = null
                vm.session.runAction(a)
            },
        )
    }
}

private fun valueText(p: BmsParameter, values: Map<String, Double>, texts: Map<String, String>): String =
    when (p.type) {
        ParamType.NUMBER -> values[p.key]?.let { "${it.fmt(p.decimals)} ${p.unit}".trim() } ?: "--"
        else -> texts[p.key]?.ifEmpty { "Not set" } ?: "--"
    }

@Composable
private fun ParamRow(param: BmsParameter, value: String, editable: Boolean, onEdit: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (param.type == ParamType.NUMBER) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(param.label, style = MaterialTheme.typography.bodyMedium)
                param.note?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(value, style = MaterialTheme.typography.titleMedium)
        } else {
            Column(Modifier.weight(1f)) {
                Text(param.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.bodyLarge)
            }
        }
        if (editable) {
            IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = "Edit ${param.label}") }
        } else if (param.writable && param.type != ParamType.INFO) {
            Spacer(Modifier.width(48.dp))
        }
    }
}

@Composable
private fun PinDialog(expected: String, onDismiss: () -> Unit, onUnlocked: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enter PIN to edit settings") },
        text = {
            Column {
                Text(
                    "The default PIN is 1234. Change it in More > App settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = {
                        pin = it.filter(Char::isDigit).take(8)
                        wrong = false
                    },
                    label = { Text("PIN") },
                    singleLine = true,
                    isError = wrong,
                    supportingText = { if (wrong) Text("That PIN doesn't match.") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
            }
        },
        confirmButton = { Button(onClick = { if (pin == expected) onUnlocked() else wrong = true }) { Text("Unlock") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun EditDialog(
    param: BmsParameter,
    currentNumber: Double?,
    currentText: String?,
    onDismiss: () -> Unit,
    onSaveNumber: (Double) -> Unit,
    onSaveText: (String) -> Unit,
) {
    val isText = param.type == ParamType.TEXT
    val maxLength = if (param.maxLength > 0) param.maxLength else 64
    var text by remember { mutableStateOf(if (isText) currentText.orEmpty() else currentNumber?.fmt(param.decimals).orEmpty()) }
    val parsed = if (isText) null else text.replace(',', '.').toDoubleOrNull()
    val error = if (isText) {
        when {
            text.isBlank() -> "Enter a value"
            text.any { it.code !in 0x20..0x7E } -> "Use letters, numbers and basic symbols only"
            else -> null
        }
    } else {
        when {
            parsed == null -> "Enter a number"
            parsed < param.min || parsed > param.max ->
                "Allowed range is ${param.min.fmt(param.decimals)} to ${param.max.fmt(param.decimals)} ${param.unit}".trim()
            else -> null
        }
    }
    val warning = when {
        isText -> "The app reads the value back after saving to confirm the BMS stored it."
        param.group == "Boards" -> "Wrong cell or sensor counts make the BMS misread the pack. Match your wiring exactly."
        param.key == "soc_set" -> "This resets the BMS charge counter to the value you enter."
        else -> "A wrong limit can stop the BMS protecting your cells. Check your cell datasheet before saving."
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(param.label) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = if (isText) it.take(maxLength) else it },
                    label = { Text(if (isText || param.unit.isEmpty()) "Value" else "Value in ${param.unit}") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = {
                        Text(error ?: if (isText) "${text.length} / $maxLength characters" else "Range ${param.min.fmt(param.decimals)} to ${param.max.fmt(param.decimals)}")
                    },
                    keyboardOptions = if (isText) {
                        KeyboardOptions(capitalization = KeyboardCapitalization.Characters)
                    } else {
                        KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    warning,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isText) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (isText) onSaveText(text.trim()) else parsed?.let(onSaveNumber) },
                enabled = error == null,
            ) { Text("Save to BMS") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ActionDialog(action: BmsAction, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var acknowledged by remember { mutableStateOf(!action.dangerous) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${action.label}?") },
        text = {
            Column {
                Text(action.description, style = MaterialTheme.typography.bodyMedium)
                if (action.dangerous) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = acknowledged, onCheckedChange = { acknowledged = it })
                        Text("I have a record of my current settings", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = acknowledged,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) { Text(action.label) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
