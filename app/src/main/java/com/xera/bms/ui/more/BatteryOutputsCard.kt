package com.xera.bms.ui.more

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xera.bms.core.Capability
import com.xera.bms.ui.MainViewModel
import com.xera.bms.ui.components.SectionCard

private enum class SwitchKind { CHARGE, DISCHARGE, BALANCER }

private data class SwitchRequest(val kind: SwitchKind, val on: Boolean)

/**
 * Charge, discharge and balancer switches, each confirmed before the command is sent.
 * Shows nothing until the BMS has reported its switch states.
 */
@Composable
fun BatteryOutputsCard(vm: MainViewModel) {
    val snap by vm.session.snapshot.collectAsStateWithLifecycle()
    val caps by vm.session.capabilities.collectAsStateWithLifecycle()
    var pending by remember { mutableStateOf<SwitchRequest?>(null) }

    val canSwitch = caps.contains(Capability.MOSFET_CONTROL)
    val canBalance = caps.contains(Capability.BALANCER_CONTROL)
    if (snap.chargeFet == null && snap.dischargeFet == null && !canSwitch) return

    SectionCard(title = "Battery outputs") {
        SwitchRow("Charging", "Lets chargers fill the battery", snap.chargeFet ?: false, canSwitch) {
            pending = SwitchRequest(SwitchKind.CHARGE, it)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SwitchRow("Discharging", "Powers everything connected to the battery", snap.dischargeFet ?: false, canSwitch) {
            pending = SwitchRequest(SwitchKind.DISCHARGE, it)
        }
        if (canBalance || snap.balancerEnabled != null) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SwitchRow("Balancer", "Evens out cell voltages", snap.balancerEnabled ?: false, canBalance) {
                pending = SwitchRequest(SwitchKind.BALANCER, it)
            }
        }
        if (!canSwitch) {
            Text(
                "This BMS reports these states but doesn't accept switching from XeraBMS.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    pending?.let { req ->
        ConfirmSwitch(
            request = req,
            onDismiss = { pending = null },
            onConfirm = {
                pending = null
                when (req.kind) {
                    SwitchKind.CHARGE -> vm.session.setFets(req.on, snap.dischargeFet ?: true)
                    SwitchKind.DISCHARGE -> vm.session.setFets(snap.chargeFet ?: true, req.on)
                    SwitchKind.BALANCER -> vm.session.setBalancer(req.on)
                }
            },
        )
    }
}

@Composable
private fun SwitchRow(title: String, hint: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun ConfirmSwitch(request: SwitchRequest, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val name = when (request.kind) {
        SwitchKind.CHARGE -> "charging"
        SwitchKind.DISCHARGE -> "discharging"
        SwitchKind.BALANCER -> "the balancer"
    }
    val warning = when {
        request.kind == SwitchKind.DISCHARGE && !request.on ->
            "Everything powered by this battery will lose power immediately."
        request.kind == SwitchKind.CHARGE && !request.on ->
            "Chargers and solar won't be able to charge the battery until you turn this back on."
        else -> "The BMS may still block this if a protection is active."
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (request.on) "Turn on $name?" else "Turn off $name?") },
        text = { Text(warning) },
        confirmButton = { Button(onClick = onConfirm) { Text(if (request.on) "Turn on" else "Turn off") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
