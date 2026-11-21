@file:OptIn(ExperimentalMaterial3Api::class)

package com.xera.bms.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xera.bms.data.LinkState
import com.xera.bms.data.SessionStatus
import com.xera.bms.ui.charts.ChartsScreen
import com.xera.bms.ui.dashboard.DashboardScreen
import com.xera.bms.ui.more.AlarmsScreen
import com.xera.bms.ui.more.AppSettingsScreen
import com.xera.bms.ui.more.ConsoleScreen
import com.xera.bms.ui.more.LogsScreen
import com.xera.bms.ui.more.MoreScreen
import com.xera.bms.ui.params.ParametersScreen
import com.xera.bms.ui.scan.ScanScreen

enum class Tab(val label: String, val icon: ImageVector) {
    BATTERY("Status", Icons.Filled.BatteryChargingFull),
    CHARTS("Charts", Icons.AutoMirrored.Filled.ShowChart),
    LIMITS("Settings", Icons.Filled.Tune),
    MORE("More", Icons.Filled.MoreHoriz),
}

enum class MoreDest(val title: String) {
    ALARMS("Alarms"),
    CONSOLE("Data console"),
    LOGS("Log files"),
    SETTINGS("App settings"),
}

@Composable
fun AppRoot(vm: MainViewModel) {
    val status by vm.session.status.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        vm.session.events.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }
    val view = LocalView.current
    DisposableEffect(settings.keepScreenOn) {
        view.keepScreenOn = settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    val connecting = status.state == LinkState.CONNECTING || status.state == LinkState.DETECTING ||
        (status.state == LinkState.RECONNECTING && !status.everConnected)
    when {
        status.state == LinkState.IDLE -> ScanScreen(vm)
        connecting -> ConnectingScreen(status, onCancel = vm.session::disconnect)
        else -> ConnectedShell(vm, status)
    }
}

@Composable
private fun ConnectingScreen(status: SessionStatus, onCancel: () -> Unit) {
    BackHandler(onBack = onCancel)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator(Modifier.size(48.dp))
            Spacer(Modifier.height(24.dp))
            Text(
                status.deviceName ?: status.address ?: "Battery",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                status.message ?: "Connecting…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@Composable
private fun ConnectedShell(vm: MainViewModel, status: SessionStatus) {
    var tab by rememberSaveable { mutableStateOf(if (status.explorer) Tab.MORE else Tab.BATTERY) }
    var sub by rememberSaveable { mutableStateOf<MoreDest?>(if (status.explorer) MoreDest.CONSOLE else null) }

    BackHandler(enabled = sub != null || tab != Tab.BATTERY) {
        if (sub != null) sub = null else tab = Tab.BATTERY
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            sub?.title ?: status.deviceName ?: "Battery",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            status.protocolName.orEmpty(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    if (sub != null) {
                        IconButton(onClick = { sub = null }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = vm.session::disconnect) {
                        Icon(Icons.Filled.LinkOff, contentDescription = "Disconnect")
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = {
                            tab = t
                            sub = null
                        },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            val banner = when {
                status.state == LinkState.RECONNECTING -> status.message ?: "Reconnecting…"
                status.explorer && status.message != null -> status.message
                else -> null
            }
            if (banner != null) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        banner,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val current = sub
                if (current != null) {
                    when (current) {
                        MoreDest.ALARMS -> AlarmsScreen(vm)
                        MoreDest.CONSOLE -> ConsoleScreen(vm)
                        MoreDest.LOGS -> LogsScreen(vm)
                        MoreDest.SETTINGS -> AppSettingsScreen(vm)
                    }
                } else {
                    when (tab) {
                        Tab.BATTERY -> DashboardScreen(
                            vm,
                            onOpenAlarms = { tab = Tab.MORE; sub = MoreDest.ALARMS },
                            onOpenConsole = { tab = Tab.MORE; sub = MoreDest.CONSOLE },
                        )
                        Tab.CHARTS -> ChartsScreen(vm)
                        Tab.LIMITS -> ParametersScreen(vm)
                        Tab.MORE -> MoreScreen(vm, status, onOpen = { sub = it })
                    }
                }
            }
        }
    }
}
