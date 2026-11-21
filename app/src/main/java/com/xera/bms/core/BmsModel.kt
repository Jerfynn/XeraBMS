package com.xera.bms.core

import kotlin.math.abs

/**
 * One brand-neutral picture of the battery. Every protocol decodes its frames into this,
 * so the UI never needs to know which BMS it is talking to.
 * Units: volts, amps (positive = charging), amp-hours, degrees Celsius.
 */
data class BmsSnapshot(
    val voltage: Double? = null,
    val current: Double? = null,
    val soc: Int? = null,
    val soh: Int? = null,
    val remainingAh: Double? = null,
    val nominalAh: Double? = null,
    val cycles: Int? = null,
    val cellVoltages: List<Double> = emptyList(),
    val cellResistances: List<Double> = emptyList(),
    /** Zero-based indexes of cells currently being balanced. */
    val balancingCells: Set<Int> = emptySet(),
    val balanceCurrent: Double? = null,
    val temperatures: List<Double> = emptyList(),
    val mosTemperature: Double? = null,
    val chargeFet: Boolean? = null,
    val dischargeFet: Boolean? = null,
    val balancerEnabled: Boolean? = null,
    val alarms: List<String> = emptyList(),
    val model: String? = null,
    val hardwareVersion: String? = null,
    val softwareVersion: String? = null,
    val manufactureDate: String? = null,
    val updatedAt: Long = 0L,
) {
    val power: Double? get() = if (voltage != null && current != null) voltage * current else null

    private val validCells: List<Double> get() = cellVoltages.filter { it > 0.1 }

    val cellMax: Double? get() = validCells.maxOrNull()
    val cellMin: Double? get() = validCells.minOrNull()
    val cellAvg: Double? get() = validCells.takeIf { it.isNotEmpty() }?.average()

    val cellDeltaMv: Double?
        get() {
            val hi = cellMax ?: return null
            val lo = cellMin ?: return null
            return (hi - lo) * 1000.0
        }

    val maxCellIndex: Int get() = cellMax?.let { cellVoltages.indexOf(it) } ?: -1
    val minCellIndex: Int get() = cellMin?.let { cellVoltages.indexOf(it) } ?: -1

    val maxTemperature: Double? get() = (temperatures + listOfNotNull(mosTemperature)).maxOrNull()

    val hasData: Boolean get() = voltage != null || cellVoltages.isNotEmpty()

    /** Hours until full (charging) or empty (discharging), or null if idle / unknown. */
    fun timeEstimate(): TimeEstimate? {
        val i = current ?: return null
        val remaining = remainingAh ?: return null
        val nominal = nominalAh ?: return null
        if (i > 0.05 && nominal > remaining) return TimeEstimate(true, (nominal - remaining) / i)
        if (i < -0.05 && remaining > 0) return TimeEstimate(false, remaining / abs(i))
        return null
    }
}

data class TimeEstimate(val toFull: Boolean, val hours: Double)

/** A configurable BMS setting, always expressed in display units (V, A, Ah, °C ...). */
data class BmsParameter(
    val key: String,
    val label: String,
    val unit: String,
    val group: String,
    val min: Double,
    val max: Double,
    val decimals: Int = 0,
    val writable: Boolean = true,
)

class Command(
    val bytes: ByteArray,
    /** How long to wait after sending, so slow BMS boards are not flooded. */
    val delayAfterMs: Long = 150L,
    val label: String = "",
)

enum class Capability { MOSFET_CONTROL, BALANCER_CONTROL, PARAM_READ, PARAM_WRITE }

/**
 * Contract every BMS brand implements. Implementations are stateful (they reassemble frames
 * and keep the latest snapshot) and are only ever called from one thread.
 *
 * To add a new BMS brand: implement this interface (extend [BaseBmsProtocol]) and register it
 * in [ProtocolRegistry]. Nothing else in the app needs to change.
 */
interface BmsProtocol {
    val id: String
    val name: String
    /** Full 128-bit lowercase UUIDs. */
    val serviceUuid: String
    val notifyUuid: String
    val writeUuid: String
    val capabilities: Set<Capability>
    val parameters: List<BmsParameter>
    val snapshot: BmsSnapshot
    val parameterValues: Map<String, Double>

    fun reset()

    /** Sent once right after connecting (also used as the detection probe). */
    fun initCommands(): List<Command>

    /** Sent every poll cycle. Streaming protocols may return an empty list. */
    fun pollCommands(nowMs: Long): List<Command>

    /** Feed raw notification bytes. Returns how many valid frames were decoded. */
    fun feed(data: ByteArray, nowMs: Long): Int

    fun mosfetCommands(charge: Boolean, discharge: Boolean): List<Command> = emptyList()
    fun balancerCommands(enabled: Boolean): List<Command> = emptyList()
    fun readParameterCommands(): List<Command> = emptyList()
    fun writeParameterCommands(param: BmsParameter, value: Double): List<Command> = emptyList()

    /** Handy raw requests offered in the console screen. */
    fun quickCommands(): List<Command> = initCommands()
}

abstract class BaseBmsProtocol : BmsProtocol {
    protected var state = BmsSnapshot()
    protected val params = LinkedHashMap<String, Double>()
    protected val buffer = FrameBuffer()

    override val snapshot: BmsSnapshot get() = state
    override val parameterValues: Map<String, Double> get() = LinkedHashMap(params)

    override fun reset() {
        state = BmsSnapshot()
        params.clear()
        buffer.clear()
    }
}
