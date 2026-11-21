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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xera.bms.core.BmsParameter
import com.xera.bms.core.Capability
import com.xera.bms.ui.MainViewModel
import com.xera.bms.ui.components.EmptyState
import com.xera.bms.ui.components.SectionCard
import com.xera.bms.ui.theme.fmt

@Composable
fun ParametersScreen(vm: MainViewModel) {
    val params by vm.session.parameters.collectAsStateWithLifecycle()
    val values by vm.session.paramValues.collectAsStateWithLifecycle()
    val caps by vm.session.capabilities.collectAsStateWithLifecycle()
    val busy by vm.session.busy.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

    var unlocked by rememberSaveable { mutableStateOf(false) }
    var askPin by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<BmsParameter?>(null) }
    val canWrite = caps.contains(Capability.PARAM_WRITE)

    LaunchedEffect(params) {
        if (params.isNotEmpty() && values.isEmpty() && caps.contains(Capability.PARAM_READ)) vm.session.readParameters()
    }

    if (!caps.contains(Capability.PARAM_READ) || params.isEmpty()) {
        EmptyState(
            icon = Icons.Filled.Tune,
            title = "No adjustable limits",
            body = "This BMS type doesn't expose its protection settings to XeraBMS.",
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = vm.session::readParameters, enabled = busy == null) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Read from BMS")
                    }
                    if (canWrite) {
                        OutlinedButton(onClick = { if (unlocked) unlocked = false else askPin = true }) {
                            Icon(
                                if (unlocked) Icons.Filled.LockOpen else Icons.Filled.Lock,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(if (unlocked) "Lock editing" else "Unlock editing")
                        }
                    }
                }
                busy?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                }
                if (!canWrite) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "These values are read-only for this BMS type.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        params.groupBy { it.group }.forEach { (group, list) ->
            item(key = group) {
                SectionCard(title = group) {
                    list.forEachIndexed { i, p ->
                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        ParamRow(
                            param = p,
                            value = values[p.key],
                            editable = unlocked && canWrite && p.writable && busy == null,
                            onEdit = { editing = p },
                        )
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
            current = values[p.key],
            onDismiss = { editing = null },
            onWrite = { v ->
                editing = null
                vm.session.writeParameter(p, v)
            },
        )
    }
}

@Composable
private fun ParamRow(param: BmsParameter, value: Double?, editable: Boolean, onEdit: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(param.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            if (value == null) "--" else "${value.fmt(param.decimals)} ${param.unit}".trim(),
            style = MaterialTheme.typography.titleMedium,
        )
        if (editable) {
            IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = "Edit ${param.label}") }
        }
    }
}

@Composable
private fun PinDialog(expected: String, onDismiss: () -> Unit, onUnlocked: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enter PIN to edit limits") },
        text = {
            Column {
                Text(
                    "The default PIN is 1234. Change it in App settings.",
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
        confirmButton = {
            Button(onClick = { if (pin == expected) onUnlocked() else wrong = true }) { Text("Unlock") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun EditDialog(param: BmsParameter, current: Double?, onDismiss: () -> Unit, onWrite: (Double) -> Unit) {
    var text by remember { mutableStateOf(current?.fmt(param.decimals) ?: "") }
    val parsed = text.replace(',', '.').toDoubleOrNull()
    val error = when {
        parsed == null -> "Enter a number"
        parsed < param.min || parsed > param.max ->
            "Allowed range is ${param.min.fmt(param.decimals)} to ${param.max.fmt(param.decimals)} ${param.unit}".trim()
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(param.label) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(if (param.unit.isEmpty()) "Value" else "Value in ${param.unit}") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = { error?.let { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "A wrong limit can stop the BMS protecting your cells. Check your cell datasheet before saving.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = {
            Button(onClick = { parsed?.let(onWrite) }, enabled = error == null) { Text("Save to BMS") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
