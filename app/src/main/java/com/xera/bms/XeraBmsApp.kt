package com.xera.bms

import android.app.Application
import com.xera.bms.ble.BleScanner
import com.xera.bms.data.BmsSession
import com.xera.bms.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** App-wide singletons: the Bluetooth session outlives screens and keeps running in the background. */
class XeraBmsApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var settings: SettingsRepository
        private set
    lateinit var scanner: BleScanner
        private set
    lateinit var session: BmsSession
        private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this, appScope)
        scanner = BleScanner(this)
        session = BmsSession(this, settings)
    }
}
