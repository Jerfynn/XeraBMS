package com.xera.bms.data

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.xera.bms.ble.BleConnection
import com.xera.bms.ble.BleNotification
import com.xera.bms.ble.GattCharInfo
import com.xera.bms.core.BmsAction
import com.xera.bms.core.BmsParameter
import com.xera.bms.core.BmsProtocol
import com.xera.bms.core.BmsSnapshot
import com.xera.bms.core.Capability
import com.xera.bms.core.Command
import com.xera.bms.core.DemoProtocol
import com.xera.bms.core.ProtocolRegistry
import com.xera.bms.core.hexToBytes
import com.xera.bms.core.toHex
import com.xera.bms.service.BmsService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow

enum class LinkState { IDLE, CONNECTING, DETECTING, CONNECTED, RECONNECTING }

data class SessionStatus(
    val state: LinkState = LinkState.IDLE,
    val deviceName: String? = null,
    val address: String? = null,
    val protocolId: String? = null,
    val protocolName: String? = null,
    val message: String? = null,
    val explorer: Boolean = false,
    val demo: Boolean = false,
    val everConnected: Boolean = false,
)

data class ConsoleLine(val time: Long, val outgoing: Boolean, val text: String, val channel: String)

data class AlarmEvent(val time: Long, val text: String, val active: Boolean)

/**
 * The one place that talks to the battery. All protocol work runs on a single background
 * thread, so protocol objects never see concurrent calls.
 */
class BmsSession(private val app: Context, private val settings: SettingsRepository) {

    private val dispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "xera-bms-engine").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val ble = BleConnection(app)
    private val bus = Mutex()

    private val _status = MutableStateFlow(SessionStatus())
    val status: StateFlow<SessionStatus> = _status.asStateFlow()

    private val _snapshot = MutableStateFlow(BmsSnapshot())
    val snapshot: StateFlow<BmsSnapshot> = _snapshot.asStateFlow()

    private val _parameters = MutableStateFlow<List<BmsParameter>>(emptyList())
    val parameters: StateFlow<List<BmsParameter>> = _parameters.asStateFlow()

    private val _paramValues = MutableStateFlow<Map<String, Double>>(emptyMap())
    val paramValues: StateFlow<Map<String, Double>> = _paramValues.asStateFlow()

    private val _paramTexts = MutableStateFlow<Map<String, String>>(emptyMap())
    val paramTexts: StateFlow<Map<String, String>> = _paramTexts.asStateFlow()

    private val _actions = MutableStateFlow<List<BmsAction>>(emptyList())
    val actions: StateFlow<List<BmsAction>> = _actions.asStateFlow()

    private val _capabilities = MutableStateFlow<Set<Capability>>(emptySet())
    val capabilities: StateFlow<Set<Capability>> = _capabilities.asStateFlow()

    private val _history = MutableStateFlow<List<HistoryPoint>>(emptyList())
    val history: StateFlow<List<HistoryPoint>> = _history.asStateFlow()

    private val _console = MutableStateFlow<List<ConsoleLine>>(emptyList())
    val console: StateFlow<List<ConsoleLine>> = _console.asStateFlow()

    private val _characteristics = MutableStateFlow<List<GattCharInfo>>(emptyList())
    val characteristics: StateFlow<List<GattCharInfo>> = _characteristics.asStateFlow()

    private val _quickCommands = MutableStateFlow<List<Command>>(emptyList())
    val quickCommands: StateFlow<List<Command>> = _quickCommands.asStateFlow()

    private val _alarmLog = MutableStateFlow<List<AlarmEvent>>(emptyList())
    val alarmLog: StateFlow<List<AlarmEvent>> = _alarmLog.asStateFlow()

    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val events: SharedFlow<String> = _events.asSharedFlow()

    @Volatile var consolePaused: Boolean = false

    private var protocol: BmsProtocol? = null
    private var sessionJob: Job? = null
    private var validFrames = 0
    private var lastFrameAt = 0L
    private var lastHistoryAt = 0L
    private var lastLogAt = 0L
    private var logger: CsvLogger? = null
    private var activeAlarms: Set<String> = emptySet()
    private val historyBuffer = ArrayDeque<HistoryPoint>()

    val logDirectory: File get() = File(app.getExternalFilesDir(null) ?: app.filesDir, "logs")

    // ------------------------------------------------------------------ public actions

    fun connect(address: String, name: String?, protocolId: String?) {
        scope.launch {
            stopInternal()
            _status.value = SessionStatus(LinkState.CONNECTING, name, address, message = "Connecting…")
            sessionJob = scope.launch { runSession(address, name, protocolId) }
        }
    }

    fun startDemo() {
        scope.launch {
            stopInternal()
            val demo = DemoProtocol()
            protocol = demo
            _capabilities.value = demo.capabilities
            _parameters.value = demo.parameters
            _paramValues.value = demo.parameterValues
            _paramTexts.value = demo.parameterTexts
            _actions.value = demo.actions
            _status.value = SessionStatus(
                LinkState.CONNECTED, "Demo battery", "DEMO", demo.id, demo.name, demo = true, everConnected = true,
            )
            sessionJob = scope.launch {
                while (currentCoroutineContext().isActive) {
                    val now = System.currentTimeMillis()
                    demo.tick(now)
                    publish(demo, now)
                    delay(1000)
                }
            }
        }
    }

    fun disconnect() {
        scope.launch { stopInternal() }
    }

    fun setFets(charge: Boolean, discharge: Boolean) {
        scope.launch {
            val p = protocol ?: return@launch
            if (p is DemoProtocol) {
                p.setFets(charge, discharge)
                p.tick(System.currentTimeMillis())
                publish(p, System.currentTimeMillis())
                return@launch
            }
            val cmds = p.mosfetCommands(charge, discharge)
            if (cmds.isEmpty()) {
                _events.tryEmit("This BMS does not support switching over Bluetooth")
                return@launch
            }
            bus.withLock { cmds.forEach { send(p, it) } }
            _events.tryEmit("Switch command sent")
        }
    }

    fun setBalancer(enabled: Boolean) {
        scope.launch {
            val p = protocol ?: return@launch
            if (p is DemoProtocol) {
                p.setBalancer(enabled)
                p.tick(System.currentTimeMillis())
                publish(p, System.currentTimeMillis())
                return@launch
            }
            val cmds = p.balancerCommands(enabled)
            if (cmds.isEmpty()) return@launch
            bus.withLock { cmds.forEach { send(p, it) } }
            _events.tryEmit(if (enabled) "Balancer turned on" else "Balancer turned off")
        }
    }

    fun readParameters() {
        scope.launch { readParametersInternal() }
    }

    /** Writes a number, reads it back, and reports whether the BMS confirmed it. */
    fun writeParameter(param: BmsParameter, value: Double) {
        scope.launch {
            val p = protocol ?: return@launch
            if (p is DemoProtocol) {
                p.applyParam(param.key, value)
                _paramValues.value = p.parameterValues
                _events.tryEmit("${param.label} saved")
                return@launch
            }
            val cmds = p.writeParameterCommands(param, value)
            if (cmds.isEmpty()) {
                _events.tryEmit("Can't change ${param.label} yet. Tap Read from BMS, then try again.")
                return@launch
            }
            _busy.value = "Saving ${param.label}…"
            try {
                bus.withLock {
                    cmds.forEach { send(p, it) }
                    delay(300)
                    p.readBackCommands(param).forEach { send(p, it) }
                }
                delay(500)
                publish(p, System.currentTimeMillis())
                val got = p.parameterValues[param.key]
                val tolerance = 0.5 * 10.0.pow(-param.decimals) + 1e-6
                _events.tryEmit(
                    if (got != null && abs(got - value) <= tolerance) {
                        "${param.label} saved and confirmed by the BMS"
                    } else {
                        "The BMS did not accept the new ${param.label}"
                    },
                )
            } finally {
                _busy.value = null
            }
        }
    }

    /** Writes text (e.g. the battery serial number), reads it back and confirms. */
    fun writeText(param: BmsParameter, text: String) {
        scope.launch {
            val p = protocol ?: return@launch
            val clean = text.trim().replace(Regex(" +"), " ")
            if (p is DemoProtocol) {
                p.applyText(param.key, clean)
                publish(p, System.currentTimeMillis())
                _events.tryEmit("${param.label} saved")
                return@launch
            }
            val cmds = p.writeTextCommands(param, clean)
            if (cmds.isEmpty()) {
                _events.tryEmit("${param.label} can't be changed on this BMS")
                return@launch
            }
            _busy.value = "Saving ${param.label}…"
            try {
                bus.withLock {
                    cmds.forEach { send(p, it) }
                    delay(300)
                    p.readBackCommands(param).forEach { send(p, it) }
                }
                delay(800)
                publish(p, System.currentTimeMillis())
                val got = p.parameterTexts[param.key]
                _events.tryEmit(
                    if (got == clean) {
                        "${param.label} saved and confirmed by the BMS"
                    } else {
                        "The BMS did not accept the new ${param.label}" + (got?.let { ". It still reports \"$it\"" } ?: "")
                    },
                )
            } finally {
                _busy.value = null
            }
        }
    }

    fun runAction(action: BmsAction) {
        scope.launch {
            val p = protocol ?: return@launch
            if (p is DemoProtocol) {
                _events.tryEmit("${action.label} isn't available in demo mode")
                return@launch
            }
            val cmds = p.actionCommands(action)
            if (cmds.isEmpty()) return@launch
            bus.withLock { cmds.forEach { send(p, it) } }
            _events.tryEmit("${action.label} sent")
        }
    }

    /** Sends raw hex to [target], or to the active protocol's write characteristic. */
    fun sendRaw(hex: String, target: GattCharInfo?) {
        scope.launch {
            val bytes = hexToBytes(hex)
            if (bytes == null) {
                _events.tryEmit("Enter an even number of hex digits, e.g. DD A5 03 00 FF FD 77")
                return@launch
            }
            val service = target?.service?.toString() ?: protocol?.serviceUuid
            val characteristic = target?.uuid?.toString() ?: protocol?.writeUuid
            if (characteristic.isNullOrEmpty()) {
                _events.tryEmit("Choose a characteristic to write to")
                return@launch
            }
            bus.withLock {
                addConsole(true, bytes.toHex(), characteristic.substring(4, 8).uppercase())
                if (!ble.write(service, characteristic, bytes)) _events.tryEmit("Write failed")
            }
        }
    }

    fun sendQuick(command: Command) {
        scope.launch {
            val p = protocol ?: return@launch
            if (p is DemoProtocol) return@launch
            bus.withLock { send(p, command) }
        }
    }

    fun clearConsole() {
        _console.value = emptyList()
    }

    fun clearAlarmLog() {
        _alarmLog.value = emptyList()
    }

    // ------------------------------------------------------------------ connection lifecycle

    private suspend fun runSession(address: String, name: String?, forcedId: String?) {
        var attempt = 0
        var everConnected = false
        try {
            while (currentCoroutineContext().isActive) {
                if (attempt > 0) {
                    _status.update {
                        it.copy(state = LinkState.RECONNECTING, message = "Connection lost. Reconnecting (attempt $attempt)…")
                    }
                }
                val reachedData = connectOnce(address, name, forcedId)
                everConnected = everConnected || reachedData
                ble.close()
                stopLogger()
                val keepTrying = settings.current.autoReconnect && (everConnected || attempt < 2)
                if (!keepTrying) {
                    if (!everConnected) {
                        _events.tryEmit("Couldn't connect to ${name ?: address}. Move closer and make sure no other app is connected.")
                    }
                    break
                }
                attempt++
                delay(min(1500L * attempt, 10_000L))
            }
        } finally {
            ble.close()
            stopLogger()
            protocol = null
            if (_status.value.address == address) resetState()
        }
    }

    /** Returns true if the BMS was identified and the session reached CONNECTED. */
    private suspend fun connectOnce(address: String, name: String?, forcedId: String?): Boolean {
        if (!ble.connect(address)) return false
        delay(500)
        val services = ble.discoverServices() ?: return false
        ble.requestMtu(247)
        _characteristics.value = ble.characteristics()
        val serviceUuids = services.map { it.uuid.toString().lowercase() }

        val collector = scope.launch { ble.notifications.collect { onNotification(it) } }
        try {
            _status.update { it.copy(state = LinkState.DETECTING, message = "Identifying the BMS…") }
            val explorerRequested = forcedId == ProtocolRegistry.EXPLORER_ID
            val proto = if (explorerRequested) null else detect(serviceUuids, name, forcedId)

            if (proto == null) {
                // Unknown BMS: subscribe to everything and let the user inspect raw traffic.
                protocol = null
                _characteristics.value.filter { it.canNotify || it.canIndicate }.forEach {
                    ble.enableNotifications(it.service.toString(), it.uuid.toString())
                }
                _capabilities.value = emptySet()
                _actions.value = emptyList()
                _parameters.value = emptyList()
                _quickCommands.value = emptyList()
                _status.value = SessionStatus(
                    LinkState.CONNECTED, name, address, ProtocolRegistry.EXPLORER_ID, "Unknown BMS – explorer mode",
                    message = if (explorerRequested) null else "No supported protocol answered. Use the console to look at raw data.",
                    explorer = true, everConnected = true,
                )
                ble.connected.first { !it }
                return true
            }

            protocol = proto
            _capabilities.value = proto.capabilities
            _actions.value = proto.actions
            _parameters.value = proto.parameters
            _quickCommands.value = proto.quickCommands()
            _status.value = SessionStatus(
                LinkState.CONNECTED, name, address, proto.id, proto.name, everConnected = true,
            )
            publish(proto, System.currentTimeMillis())
            settings.update { it.copy(lastAddress = address, lastName = name, lastProtocol = proto.id) }
            startBackgroundService()

            lastFrameAt = System.currentTimeMillis()
            val poller = scope.launch { pollLoop(proto) }
            ble.connected.first { !it }
            poller.cancel()
            return true
        } finally {
            collector.cancel()
        }
    }

    private suspend fun detect(serviceUuids: List<String>, name: String?, forcedId: String?): BmsProtocol? {
        for (candidate in ProtocolRegistry.candidates(serviceUuids, name, forcedId)) {
            if (!ble.hasCharacteristic(candidate.serviceUuid, candidate.notifyUuid) ||
                !ble.hasCharacteristic(candidate.serviceUuid, candidate.writeUuid)
            ) continue
            _status.update { it.copy(message = "Trying ${candidate.name}…") }
            candidate.reset()
            validFrames = 0
            protocol = candidate
            ble.enableNotifications(candidate.serviceUuid, candidate.notifyUuid)
            repeat(2) {
                bus.withLock {
                    (candidate.initCommands() + candidate.pollCommands(System.currentTimeMillis()))
                        .forEach { send(candidate, it) }
                }
                val deadline = System.currentTimeMillis() + 2500
                while (System.currentTimeMillis() < deadline && validFrames == 0) delay(100)
                if (validFrames > 0) return candidate
            }
        }
        protocol = null
        return null
    }

    private suspend fun pollLoop(p: BmsProtocol) {
        while (currentCoroutineContext().isActive && ble.connected.value) {
            val cmds = p.pollCommands(System.currentTimeMillis())
            if (cmds.isNotEmpty()) bus.withLock { cmds.forEach { send(p, it) } }
            if (System.currentTimeMillis() - lastFrameAt > 20_000) {
                addConsole(false, "No data for 20 s – reconnecting", "--")
                ble.close()
                break
            }
            delay(settings.current.pollIntervalMs)
        }
    }

    private suspend fun stopInternal() {
        sessionJob?.cancelAndJoin()
        sessionJob = null
        ble.close()
        stopLogger()
        protocol = null
        resetState()
    }

    private fun resetState() {
        _status.value = SessionStatus()
        _snapshot.value = BmsSnapshot()
        _parameters.value = emptyList()
        _paramValues.value = emptyMap()
        _capabilities.value = emptySet()
        _paramTexts.value = emptyMap()
        _actions.value = emptyList()
        _characteristics.value = emptyList()
        _quickCommands.value = emptyList()
        _busy.value = null
        historyBuffer.clear()
        _history.value = emptyList()
        activeAlarms = emptySet()
    }

    // ------------------------------------------------------------------ data path

    private suspend fun send(p: BmsProtocol, cmd: Command): Boolean {
        addConsole(true, cmd.bytes.toHex(), cmd.label.ifEmpty { "TX" })
        val ok = ble.write(p.serviceUuid, p.writeUuid, cmd.bytes)
        delay(cmd.delayAfterMs)
        return ok
    }

    private fun onNotification(n: BleNotification) {
        val now = System.currentTimeMillis()
        addConsole(false, n.value.toHex(), n.characteristic.toString().substring(4, 8).uppercase())
        val p = protocol ?: return
        if (p is DemoProtocol) return
        if (!n.characteristic.toString().equals(p.notifyUuid, ignoreCase = true)) return
        val frames = try {
            p.feed(n.value, now)
        } catch (_: Exception) {
            0
        }
        if (frames > 0) {
            validFrames += frames
            lastFrameAt = now
            publish(p, now)
        }
    }

    private fun publish(p: BmsProtocol, now: Long) {
        val snap = p.snapshot.copy(updatedAt = now)
        _snapshot.value = snap
        _paramValues.value = p.parameterValues
        _paramTexts.value = p.parameterTexts
        trackAlarms(snap.alarms, now)
        if (snap.hasData && now - lastHistoryAt >= 1000) {
            lastHistoryAt = now
            historyBuffer.addLast(snap.toHistoryPoint(now))
            while (historyBuffer.size > MAX_HISTORY) historyBuffer.removeFirst()
            _history.value = historyBuffer.toList()
        }
        val s = settings.current
        if (s.csvLogging && snap.hasData && p !is DemoProtocol && now - lastLogAt >= s.logIntervalSec * 1000L) {
            lastLogAt = now
            try {
                val log = logger ?: CsvLogger(logDirectory, _status.value.deviceName ?: "battery").also { logger = it }
                log.write(snap)
            } catch (_: Exception) {
                stopLogger()
            }
        } else if (!s.csvLogging && logger != null) {
            stopLogger()
        }
    }

    private fun trackAlarms(alarms: List<String>, now: Long) {
        val current = alarms.toSet()
        if (current == activeAlarms) return
        val events = (current - activeAlarms).map { AlarmEvent(now, it, true) } +
            (activeAlarms - current).map { AlarmEvent(now, it, false) }
        activeAlarms = current
        _alarmLog.value = (events + _alarmLog.value).take(200)
    }

    private fun addConsole(outgoing: Boolean, text: String, channel: String) {
        if (consolePaused) return
        val line = ConsoleLine(System.currentTimeMillis(), outgoing, text, channel)
        val list = _console.value
        _console.value = if (list.size >= MAX_CONSOLE) list.drop(list.size - MAX_CONSOLE + 1) + line else list + line
    }

    private suspend fun readParametersInternal() {
        val p = protocol ?: return
        if (p is DemoProtocol) {
            _paramValues.value = p.parameterValues
            return
        }
        val cmds = p.readParameterCommands()
        if (cmds.isEmpty()) return
        _busy.value = "Reading settings from the BMS…"
        try {
            bus.withLock { cmds.forEach { send(p, it) } }
            delay(600)
            publish(p, System.currentTimeMillis())
        } finally {
            _busy.value = null
        }
    }

    private fun stopLogger() {
        logger?.close()
        logger = null
    }

    private fun startBackgroundService() {
        if (!settings.current.backgroundMonitoring) return
        try {
            ContextCompat.startForegroundService(app, Intent(app, BmsService::class.java))
        } catch (_: Exception) {
            // Starting from the background is not allowed on some Android versions; ignore.
        }
    }

    companion object {
        private const val MAX_HISTORY = 4 * 3600
        private const val MAX_CONSOLE = 400
    }
}
