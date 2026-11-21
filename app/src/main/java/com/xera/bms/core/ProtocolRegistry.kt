package com.xera.bms.core

import com.xera.bms.core.protocols.DalyProtocol
import com.xera.bms.core.protocols.JbdProtocol
import com.xera.bms.core.protocols.JkProtocol
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Every supported BMS family. Auto-detection tries the protocols whose GATT service is present,
 * name-hint matches first, and keeps the first one that answers with a valid frame.
 *
 * Adding a brand = one new BmsProtocol class + one Entry here.
 */
object ProtocolRegistry {

    class Entry(
        val id: String,
        val label: String,
        val nameHints: List<String>,
        val factory: () -> BmsProtocol,
    )

    const val EXPLORER_ID = "explorer"

    val entries: List<Entry> = listOf(
        Entry(
            "jbd", "JBD / Xiaoxiang / Overkill",
            listOf("xiaoxiang", "jbd", "sp0", "sp1", "sp2", "dp0", "overkill", "litime", "redodo", "power queen"),
        ) { JbdProtocol() },
        Entry("daly", "Daly", listOf("dl-", "daly")) { DalyProtocol() },
        Entry("jk", "JK / Jikong", listOf("jk-", "jk_", "jk ", "jikong", "jkbms")) { JkProtocol() },
        Entry("jbd_ffe0", "JBD on FFE0 module", emptyList()) {
            JbdProtocol(
                id = "jbd_ffe0", name = "JBD (FFE0 module)",
                serviceUuid = uuid16(0xFFE0), notifyUuid = uuid16(0xFFE1), writeUuid = uuid16(0xFFE1),
            )
        },
        Entry("daly40", "Daly (UART address 0x40)", emptyList()) {
            DalyProtocol(address = 0x40, id = "daly40", name = "Daly Smart BMS (addr 0x40)")
        },
    )

    fun create(id: String): BmsProtocol? = entries.firstOrNull { it.id == id }?.factory?.invoke()

    /** Ordered candidate list for auto-detection (or just the forced protocol). */
    fun candidates(serviceUuids: Collection<String>, deviceName: String?, forcedId: String?): List<BmsProtocol> {
        if (forcedId != null) return listOfNotNull(create(forcedId))
        val name = deviceName?.lowercase().orEmpty()
        val built = entries.map { it to it.factory() }
        val withService = built.filter { (_, p) -> serviceUuids.any { it.equals(p.serviceUuid, ignoreCase = true) } }
        val pool = withService.ifEmpty { built }
        return pool.sortedByDescending { (e, _) -> e.nameHints.any { name.contains(it) } }.map { it.second }
    }

    /** Best guess for the scan list, from the advertised name and service UUIDs. */
    fun guessLabel(deviceName: String?, advertisedServices: Collection<String>): String? {
        val name = deviceName?.lowercase().orEmpty()
        entries.firstOrNull { e -> e.nameHints.any { name.contains(it) } }?.let { return it.label }
        val adv = advertisedServices.map { it.lowercase() }
        return when {
            uuid16(0xFF00) in adv -> "JBD-compatible"
            uuid16(0xFFF0) in adv -> "Daly-compatible"
            name.contains("bms") || name.contains("battery") || name.contains("lifepo") -> "Battery"
            else -> null
        }
    }
}

/** A simulated 16S LiFePO4 pack so every screen can be tried without hardware. */
class DemoProtocol : BaseBmsProtocol() {
    override val id = "demo"
    override val name = "Demo battery (simulated 16S LiFePO4)"
    override val serviceUuid = ""
    override val notifyUuid = ""
    override val writeUuid = ""
    override val capabilities = Capability.entries.toSet()

    override val parameters = listOf(
        BmsParameter("design_cap", "Design capacity", "Ah", "Capacity", 1.0, 650.0, 1),
        BmsParameter("cell_ov", "Cell overvoltage", "V", "Cell voltage", 2.0, 4.5, 3),
        BmsParameter("cell_uv", "Cell undervoltage", "V", "Cell voltage", 1.5, 4.0, 3),
        BmsParameter("chg_oc", "Charge overcurrent", "A", "Current", 1.0, 300.0, 1),
        BmsParameter("dsg_oc", "Discharge overcurrent", "A", "Current", 1.0, 300.0, 1),
        BmsParameter("chg_ot", "Charge over-temperature", "°C", "Temperature", 0.0, 100.0, 1),
        BmsParameter("bal_start", "Balance start voltage", "V", "Balancing", 2.0, 4.5, 3),
    )

    private var startMs = 0L
    private var lastMs = 0L
    private var socExact = 72.0
    private var charge = true
    private var discharge = true
    private var balancer = true

    init {
        resetParams()
    }

    private fun resetParams() {
        params["design_cap"] = 100.0
        params["cell_ov"] = 3.650
        params["cell_uv"] = 2.500
        params["chg_oc"] = 100.0
        params["dsg_oc"] = 150.0
        params["chg_ot"] = 55.0
        params["bal_start"] = 3.400
    }

    override fun reset() {
        super.reset()
        resetParams()
    }

    override fun initCommands() = emptyList<Command>()
    override fun pollCommands(nowMs: Long) = emptyList<Command>()
    override fun feed(data: ByteArray, nowMs: Long) = 0

    fun setFets(c: Boolean, d: Boolean) {
        charge = c
        discharge = d
    }

    fun setBalancer(enabled: Boolean) {
        balancer = enabled
    }

    fun applyParam(key: String, value: Double) {
        params[key] = value
    }

    fun tick(nowMs: Long) {
        if (startMs == 0L) {
            startMs = nowMs
            lastMs = nowMs
        }
        val t = (nowMs - startMs) / 1000.0
        val dtHours = (nowMs - lastMs) / 3_600_000.0
        lastMs = nowMs
        val capacity = params["design_cap"] ?: 100.0

        var current = 18.0 * sin(2 * PI * t / 90.0) + 4.0 * sin(2 * PI * t / 7.0)
        if (!charge && current > 0) current = 0.0
        if (!discharge && current < 0) current = 0.0
        socExact = (socExact + current * dtHours * 60.0 / capacity * 100.0).coerceIn(3.0, 100.0)

        val base = 3.18 + 0.17 * (socExact / 100.0) + current * 0.0015
        val cells = (0 until 16).map { i ->
            val drift = when (i) {
                5 -> 0.021
                11 -> -0.014
                else -> 0.004 * sin(i * 1.7 + t / 30.0)
            }
            ((base + drift) * 1000).roundToInt() / 1000.0
        }
        val hi = cells.maxOrNull() ?: base
        val lo = cells.minOrNull() ?: base
        val balancing = if (balancer && hi - lo > 0.015 && current > 0) setOf(cells.indexOf(hi)) else emptySet()
        val cellTemp = 27.0 + 3.0 * sin(t / 120.0) + abs(current) * 0.08

        val alarms = buildList {
            if (hi >= (params["cell_ov"] ?: 3.65)) add("Cell overvoltage")
            if (lo <= (params["cell_uv"] ?: 2.5)) add("Cell undervoltage")
            if (!discharge) add("Discharge switched off")
        }

        state = state.copy(
            voltage = cells.sum(),
            current = current,
            soc = socExact.roundToInt(),
            soh = 98,
            remainingAh = capacity * socExact / 100.0,
            nominalAh = capacity,
            cycles = 137,
            cellVoltages = cells,
            cellResistances = List(16) { 0.18 + (it % 5) * 0.01 },
            balancingCells = balancing,
            temperatures = listOf(cellTemp, cellTemp - 1.3),
            mosTemperature = cellTemp + 4.0,
            chargeFet = charge,
            dischargeFet = discharge,
            balancerEnabled = balancer,
            alarms = alarms,
            model = "XERA-DEMO-16S100A",
            hardwareVersion = "1.0",
            softwareVersion = "1.0.0",
            manufactureDate = "2026-01-15",
        )
    }

    private fun abs(v: Double) = if (v < 0) -v else v
}
