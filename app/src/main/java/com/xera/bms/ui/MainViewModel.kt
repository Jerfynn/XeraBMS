package com.xera.bms.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.xera.bms.XeraBmsApp
import com.xera.bms.ble.ScannedDevice
import com.xera.bms.data.AppSettings
import java.io.File

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val xera = application as XeraBmsApp

    val session = xera.session
    val scanner = xera.scanner
    val settings = xera.settings.settings

    fun updateSettings(transform: (AppSettings) -> AppSettings) = xera.settings.update(transform)

    fun connect(device: ScannedDevice, protocolId: String?) {
        scanner.stop()
        session.connect(device.address, device.name, protocolId)
    }

    fun reconnectLast() {
        val s = settings.value
        val address = s.lastAddress ?: return
        scanner.stop()
        session.connect(address, s.lastName, s.lastProtocol)
    }

    fun startDemo() {
        scanner.stop()
        session.startDemo()
    }

    fun logFiles(): List<File> =
        session.logDirectory.listFiles { f -> f.isFile && f.name.endsWith(".csv") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
}
