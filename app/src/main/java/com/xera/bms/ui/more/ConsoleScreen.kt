@file:OptIn(ExperimentalMaterial3Api::class)

package com.xera.bms.ui.more

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xera.bms.ble.GattCharInfo
import com.xera.bms.ui.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Raw view of every byte sent and received. This is the tool for bringing up a BMS that
 * XeraBMS doesn't support yet: watch what it sends, try requests, then write a protocol class.
 */
@Composable
fun ConsoleScreen(vm: MainViewModel) {
    val lines by vm.session.console.collectAsStateWithLifecycle()
    val chars by vm.session.characteristics.collectAsStateWithLifecycle()
    val quick by vm.session.quickCommands.collectAsStateWithLifecycle()
    val status by vm.session.status.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val listState = rememberLazyListState()
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }

    var input by rememberSaveable { mutableStateOf("") }
    var paused by remember { mutableStateOf(vm.session.consolePaused) }
    var follow by remember { mutableStateOf(true) }
    val writable = chars.filter { it.writable }
    var target by remember { mutableStateOf<GattCharInfo?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    val effectiveTarget = target ?: if (status.explorer) writable.firstOrNull() else null

    LaunchedEffect(lines.size, follow) {
        if (follow && lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = {
                paused = !paused
                vm.session.consolePaused = paused
            }) {
                Icon(if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, contentDescription = if (paused) "Resume" else "Pause")
            }
            IconButton(onClick = vm.session::clearConsole) { Icon(Icons.Filled.Delete, contentDescription = "Clear") }
            IconButton(onClick = {
                val text = lines.joinToString("\n") { l ->
                    "${timeFormat.format(Date(l.time))} ${if (l.outgoing) "TX" else "RX"} [${l.channel}] ${l.text}"
                }
                clipboard.setText(AnnotatedString(text))
            }) { Icon(Icons.Filled.ContentCopy, contentDescription = "Copy all") }
            AssistChip(
                onClick = { follow = !follow },
                label = { Text(if (follow) "Following" else "Follow") },
            )
            Box(Modifier.weight(1f))
            Text("${lines.size} lines", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (quick.isNotEmpty()) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                quick.forEach { cmd ->
                    AssistChip(
                        onClick = { vm.session.sendQuick(cmd) },
                        label = { Text(cmd.label.ifEmpty { "Command" }) },
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            items(lines) { l ->
                Text(
                    "${timeFormat.format(Date(l.time))}  ${if (l.outgoing) "TX" else "RX"} ${l.channel}  ${l.text}",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (l.outgoing) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 1.dp),
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(Modifier.padding(12.dp)) {
            if (writable.isNotEmpty()) {
                Box {
                    OutlinedButton(onClick = { menuOpen = true }) {
                        Text(
                            effectiveTarget?.let { "Write to ${it.shortName} (${it.flags})" }
                                ?: "Write to the BMS protocol's characteristic",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (!status.explorer) {
                            DropdownMenuItem(
                                text = { Text("Protocol default") },
                                onClick = {
                                    target = null
                                    menuOpen = false
                                },
                            )
                        }
                        writable.forEach { c ->
                            DropdownMenuItem(
                                text = { Text("${c.shortName}  service ${c.service.toString().substring(4, 8).uppercase()}  ${c.flags}") },
                                onClick = {
                                    target = c
                                    menuOpen = false
                                },
                            )
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it.uppercase() },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("Hex bytes, e.g. DD A5 03 00 FF FD 77") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { vm.session.sendRaw(input, effectiveTarget) }),
                )
                IconButton(onClick = { vm.session.sendRaw(input, effectiveTarget) }) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}
