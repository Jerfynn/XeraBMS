package com.xera.bms.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.xera.bms.core.ProtocolRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ScannedDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
    val serviceUuids: List<String>,
    val protocolHint: String?,
    val lastSeen: Long,
)

@SuppressLint("MissingPermission")
class BleScanner(private val context: Context) {

    private val adapter: BluetoothAdapter?
        get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    private val found = LinkedHashMap<String, ScannedDevice>()
    private val handler = Handler(Looper.getMainLooper())

    private val _devices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val devices: StateFlow<List<ScannedDevice>> = _devices.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val isBluetoothOn: Boolean get() = adapter?.isEnabled == true

    private val stopRunnable = Runnable { stop() }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)

        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { handle(it) }

        override fun onScanFailed(errorCode: Int) {
            _scanning.value = false
            _error.value = "Scanning failed (code $errorCode). Turn Bluetooth off and on, then try again."
        }
    }

    fun start(durationMs: Long = 25_000) {
        if (_scanning.value) return
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            _error.value = "Bluetooth is off."
            return
        }
        synchronized(found) { found.clear() }
        _devices.value = emptyList()
        _error.value = null
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(null, settings, callback)
            _scanning.value = true
            handler.removeCallbacks(stopRunnable)
            handler.postDelayed(stopRunnable, durationMs)
        } catch (e: SecurityException) {
            _error.value = "Bluetooth permission is missing."
        } catch (e: IllegalStateException) {
            _error.value = "Bluetooth is off."
        }
    }

    fun stop() {
        handler.removeCallbacks(stopRunnable)
        if (!_scanning.value) return
        try {
            adapter?.bluetoothLeScanner?.stopScan(callback)
        } catch (_: Exception) {
        }
        _scanning.value = false
    }

    private fun handle(result: ScanResult) {
        val address = result.device?.address ?: return
        val advertisedName = result.scanRecord?.deviceName
        val name = advertisedName ?: try {
            result.device.name
        } catch (_: SecurityException) {
            null
        }
        val uuids = result.scanRecord?.serviceUuids?.map { it.uuid.toString().lowercase() } ?: emptyList()
        synchronized(found) {
            val previous = found[address]
            val finalName = name ?: previous?.name
            found[address] = ScannedDevice(
                address = address,
                name = finalName,
                rssi = result.rssi,
                serviceUuids = uuids.ifEmpty { previous?.serviceUuids ?: emptyList() },
                protocolHint = ProtocolRegistry.guessLabel(finalName, uuids),
                lastSeen = System.currentTimeMillis(),
            )
            _devices.value = found.values.sortedByDescending { it.rssi }
        }
    }
}
