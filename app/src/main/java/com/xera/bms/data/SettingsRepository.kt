package com.xera.bms.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AppSettings(
    val pollIntervalMs: Long = 2000,
    val autoReconnect: Boolean = true,
    val csvLogging: Boolean = false,
    val logIntervalSec: Int = 5,
    val backgroundMonitoring: Boolean = true,
    val fahrenheit: Boolean = false,
    /** 0 = follow system, 1 = dark, 2 = light */
    val theme: Int = 0,
    val keepScreenOn: Boolean = false,
    val paramPin: String = "1234",
    val bmsOnlyFilter: Boolean = false,
    val lastAddress: String? = null,
    val lastName: String? = null,
    val lastProtocol: String? = null,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "xera_settings")

class SettingsRepository(private val context: Context, private val scope: CoroutineScope) {

    private object K {
        val poll = longPreferencesKey("poll_ms")
        val reconnect = booleanPreferencesKey("auto_reconnect")
        val csv = booleanPreferencesKey("csv_logging")
        val logInterval = intPreferencesKey("log_interval_s")
        val background = booleanPreferencesKey("background")
        val fahrenheit = booleanPreferencesKey("fahrenheit")
        val theme = intPreferencesKey("theme")
        val screenOn = booleanPreferencesKey("keep_screen_on")
        val pin = stringPreferencesKey("param_pin")
        val bmsOnly = booleanPreferencesKey("bms_only")
        val lastAddress = stringPreferencesKey("last_address")
        val lastName = stringPreferencesKey("last_name")
        val lastProtocol = stringPreferencesKey("last_protocol")
    }

    val settings: StateFlow<AppSettings> = context.dataStore.data
        .map { read(it) }
        .stateIn(scope, SharingStarted.Eagerly, AppSettings())

    val current: AppSettings get() = settings.value

    fun update(transform: (AppSettings) -> AppSettings) {
        scope.launch {
            context.dataStore.edit { prefs -> write(transform(read(prefs)), prefs) }
        }
    }

    private fun read(p: Preferences): AppSettings {
        val d = AppSettings()
        return AppSettings(
            pollIntervalMs = p[K.poll] ?: d.pollIntervalMs,
            autoReconnect = p[K.reconnect] ?: d.autoReconnect,
            csvLogging = p[K.csv] ?: d.csvLogging,
            logIntervalSec = p[K.logInterval] ?: d.logIntervalSec,
            backgroundMonitoring = p[K.background] ?: d.backgroundMonitoring,
            fahrenheit = p[K.fahrenheit] ?: d.fahrenheit,
            theme = p[K.theme] ?: d.theme,
            keepScreenOn = p[K.screenOn] ?: d.keepScreenOn,
            paramPin = p[K.pin] ?: d.paramPin,
            bmsOnlyFilter = p[K.bmsOnly] ?: d.bmsOnlyFilter,
            lastAddress = p[K.lastAddress],
            lastName = p[K.lastName],
            lastProtocol = p[K.lastProtocol],
        )
    }

    private fun write(s: AppSettings, p: MutablePreferences) {
        p[K.poll] = s.pollIntervalMs
        p[K.reconnect] = s.autoReconnect
        p[K.csv] = s.csvLogging
        p[K.logInterval] = s.logIntervalSec
        p[K.background] = s.backgroundMonitoring
        p[K.fahrenheit] = s.fahrenheit
        p[K.theme] = s.theme
        p[K.screenOn] = s.keepScreenOn
        p[K.pin] = s.paramPin
        p[K.bmsOnly] = s.bmsOnlyFilter
        putOrRemove(p, K.lastAddress, s.lastAddress)
        putOrRemove(p, K.lastName, s.lastName)
        putOrRemove(p, K.lastProtocol, s.lastProtocol)
    }

    private fun putOrRemove(p: MutablePreferences, key: Preferences.Key<String>, value: String?) {
        if (value == null) p.remove(key) else p[key] = value
    }
}
