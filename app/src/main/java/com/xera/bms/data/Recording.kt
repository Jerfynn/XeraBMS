package com.xera.bms.data

import com.xera.bms.core.BmsSnapshot
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One point on the charts. NaN means "not reported by this BMS". */
data class HistoryPoint(
    val t: Long,
    val voltage: Float,
    val current: Float,
    val soc: Float,
    val deltaMv: Float,
    val maxTemp: Float,
)

fun BmsSnapshot.toHistoryPoint(t: Long) = HistoryPoint(
    t = t,
    voltage = voltage?.toFloat() ?: Float.NaN,
    current = current?.toFloat() ?: Float.NaN,
    soc = soc?.toFloat() ?: Float.NaN,
    deltaMv = cellDeltaMv?.toFloat() ?: Float.NaN,
    maxTemp = maxTemperature?.toFloat() ?: Float.NaN,
)

/** Writes one CSV row per sample. Files land in Android/data/com.xera.bms/files/logs. */
class CsvLogger(dir: File, deviceName: String) {
    val file: File
    private val writer: BufferedWriter
    private var headerWritten = false
    private var cellColumns = 0
    private var tempColumns = 0
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    init {
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safe = deviceName.replace(Regex("[^A-Za-z0-9_-]"), "_").take(24).ifEmpty { "battery" }
        file = File(dir, "XeraBMS_${safe}_$stamp.csv")
        writer = file.bufferedWriter()
    }

    fun write(s: BmsSnapshot) {
        if (!headerWritten) {
            cellColumns = s.cellVoltages.size
            tempColumns = s.temperatures.size
            val cols = mutableListOf(
                "time", "voltage_v", "current_a", "power_w", "soc_pct", "remaining_ah", "cycles",
                "cell_min_v", "cell_max_v", "cell_delta_mv", "charge_fet", "discharge_fet", "mos_temp_c", "alarms",
            )
            repeat(tempColumns) { cols += "temp${it + 1}_c" }
            repeat(cellColumns) { cols += "cell${it + 1}_v" }
            writer.write(cols.joinToString(","))
            writer.newLine()
            headerWritten = true
        }
        val row = mutableListOf(
            timeFormat.format(Date(if (s.updatedAt > 0) s.updatedAt else System.currentTimeMillis())),
            n(s.voltage, 3), n(s.current, 3), n(s.power, 1), n(s.soc, 1),
            n(s.remainingAh, 2), s.cycles?.toString().orEmpty(),
            n(s.cellMin, 3), n(s.cellMax, 3), n(s.cellDeltaMv, 0),
            b(s.chargeFet), b(s.dischargeFet), n(s.mosTemperature, 1),
            "\"" + s.alarms.joinToString("; ").replace("\"", "'") + "\"",
        )
        repeat(tempColumns) { row += n(s.temperatures.getOrNull(it), 1) }
        repeat(cellColumns) { row += n(s.cellVoltages.getOrNull(it), 3) }
        writer.write(row.joinToString(","))
        writer.newLine()
        writer.flush()
    }

    fun close() {
        try {
            writer.close()
        } catch (_: Exception) {
        }
    }

    private fun n(v: Double?, decimals: Int) = if (v == null) "" else String.format(Locale.US, "%.${decimals}f", v)
    private fun b(v: Boolean?) = when (v) {
        true -> "1"
        false -> "0"
        null -> ""
    }
}
