package com.xera.bms.core.protocols

import com.xera.bms.core.BaseBmsProtocol
import com.xera.bms.core.BmsParameter
import com.xera.bms.core.Capability
import com.xera.bms.core.Command
import com.xera.bms.core.u16be
import com.xera.bms.core.u32be
import com.xera.bms.core.u8
import com.xera.bms.core.uuid16
import kotlin.math.abs

/**
 * Daly Smart BMS (classic "A5" protocol, Bluetooth dongle or built-in BLE).
 *
 * Request : A5 <addr> <cmd> 08 <8 data bytes> <checksum>   (13 bytes)
 * Response: A5 01 <cmd> 08 <8 data bytes> <checksum>
 * Checksum: sum of the first 12 bytes, low byte. Big-endian values.
 * Address : 0x80 for Bluetooth apps, 0x40 for the PC/UART tool. Both variants are registered.
 */
class DalyProtocol(
    private val address: Int = 0x80,
    override val id: String = "daly",
    override val name: String = "Daly Smart BMS",
    override val serviceUuid: String = uuid16(0xFFF0),
    override val notifyUuid: String = uuid16(0xFFF1),
    override val writeUuid: String = uuid16(0xFFF2),
) : BaseBmsProtocol() {

    private var cellCount = 0
    private var tempCount = 0
    private var cells = DoubleArray(0)
    private var temps = DoubleArray(0)

    override val capabilities = setOf(Capability.MOSFET_CONTROL, Capability.PARAM_READ)

    override val parameters = listOf(
        BmsParameter("rated_capacity", "Rated capacity", "Ah", "Capacity", 0.0, 2000.0, 1, writable = false),
        BmsParameter("cell_high_1", "Cell high voltage alarm (level 1)", "V", "Cell voltage", 0.0, 5.0, 3, writable = false),
        BmsParameter("cell_high_2", "Cell high voltage protection (level 2)", "V", "Cell voltage", 0.0, 5.0, 3, writable = false),
        BmsParameter("cell_low_1", "Cell low voltage alarm (level 1)", "V", "Cell voltage", 0.0, 5.0, 3, writable = false),
        BmsParameter("cell_low_2", "Cell low voltage protection (level 2)", "V", "Cell voltage", 0.0, 5.0, 3, writable = false),
        BmsParameter("pack_high_1", "Pack high voltage alarm (level 1)", "V", "Pack voltage", 0.0, 200.0, 1, writable = false),
        BmsParameter("pack_high_2", "Pack high voltage protection (level 2)", "V", "Pack voltage", 0.0, 200.0, 1, writable = false),
        BmsParameter("pack_low_1", "Pack low voltage alarm (level 1)", "V", "Pack voltage", 0.0, 200.0, 1, writable = false),
        BmsParameter("pack_low_2", "Pack low voltage protection (level 2)", "V", "Pack voltage", 0.0, 200.0, 1, writable = false),
        BmsParameter("chg_oc_1", "Charge overcurrent alarm (level 1)", "A", "Current", 0.0, 1000.0, 1, writable = false),
        BmsParameter("chg_oc_2", "Charge overcurrent protection (level 2)", "A", "Current", 0.0, 1000.0, 1, writable = false),
        BmsParameter("dsg_oc_1", "Discharge overcurrent alarm (level 1)", "A", "Current", 0.0, 1000.0, 1, writable = false),
        BmsParameter("dsg_oc_2", "Discharge overcurrent protection (level 2)", "A", "Current", 0.0, 1000.0, 1, writable = false),
    )

    private fun cmd(id: Int, delay: Long = 140, label: String = "") = Command(frame(address, id), delay, label)

    override fun initCommands() = listOf(
        cmd(0x94, 200, "Status (cell count)"),
        cmd(0x90, 200, "Voltage / current / SOC"),
        cmd(0x50, 200, "Rated capacity"),
    )

    override fun pollCommands(nowMs: Long) = listOf(
        cmd(0x90, label = "Voltage / current / SOC"),
        cmd(0x93, label = "MOSFET state / remaining capacity"),
        cmd(0x94, label = "Status"),
        cmd(0x95, 260, "Cell voltages"),
        cmd(0x96, 180, "Temperatures"),
        cmd(0x97, label = "Balancing"),
        cmd(0x98, label = "Faults"),
    )

    override fun quickCommands() = listOf(
        cmd(0x90, label = "0x90 SOC"), cmd(0x91, label = "0x91 Cell min/max"), cmd(0x92, label = "0x92 Temp min/max"),
        cmd(0x93, label = "0x93 MOSFET"), cmd(0x94, label = "0x94 Status"), cmd(0x95, label = "0x95 Cells"),
        cmd(0x96, label = "0x96 Temps"), cmd(0x98, label = "0x98 Faults"),
    )

    override fun mosfetCommands(charge: Boolean, discharge: Boolean) = listOf(
        Command(frame(address, 0xDA, byteArrayOf(if (charge) 1 else 0, 0, 0, 0, 0, 0, 0, 0)), 300, "Charge MOSFET"),
        Command(frame(address, 0xD9, byteArrayOf(if (discharge) 1 else 0, 0, 0, 0, 0, 0, 0, 0)), 300, "Discharge MOSFET"),
    )

    override fun readParameterCommands() = listOf(
        cmd(0x50, 250, "Rated capacity"),
        cmd(0x59, 250, "Cell voltage thresholds"),
        cmd(0x5A, 250, "Pack voltage thresholds"),
        cmd(0x5B, 250, "Current thresholds"),
    )

    override fun reset() {
        super.reset()
        cellCount = 0
        tempCount = 0
        cells = DoubleArray(0)
        temps = DoubleArray(0)
    }

    override fun feed(data: ByteArray, nowMs: Long): Int {
        buffer.append(data)
        var frames = 0
        while (true) {
            val start = buffer.peek().indexOfFirst { (it.toInt() and 0xFF) == 0xA5 }
            if (start < 0) {
                buffer.clear()
                break
            }
            if (start > 0) buffer.drop(start)
            val f = buffer.peek()
            if (f.size < 13) break
            var sum = 0
            for (i in 0 until 12) sum += f.u8(i)
            if (f.u8(3) != 0x08 || (sum and 0xFF) != f.u8(12)) {
                buffer.drop(1)
                continue
            }
            val addr = f.u8(1)
            val cmdId = f.u8(2)
            val d = f.copyOfRange(4, 12)
            buffer.drop(13)
            // Skip an echo of our own request (same address, empty payload).
            if (addr == address && d.all { it.toInt() == 0 }) continue
            handle(cmdId, d)
            frames++
        }
        return frames
    }

    private fun handle(cmd: Int, d: ByteArray) {
        when (cmd) {
            0x90 -> state = state.copy(
                voltage = d.u16be(0) / 10.0,
                current = (d.u16be(4) - 30000) / 10.0,
                // SOC arrives in 0.1 % steps. Truncate (92.6 % -> 92 %) like the Daly app does; rounding showed +1.
                soc = d.u16be(6) / 10,
            )
            0x93 -> state = state.copy(
                chargeFet = d.u8(1) != 0,
                dischargeFet = d.u8(2) != 0,
                remainingAh = d.u32be(4) / 1000.0,
            )
            0x94 -> {
                val n = d.u8(0)
                val t = d.u8(1)
                if (n in 1..48 && n != cellCount) {
                    cellCount = n
                    cells = cells.copyOf(n)
                }
                if (t in 0..16 && t != tempCount) {
                    tempCount = t
                    temps = temps.copyOf(t)
                }
                state = state.copy(cycles = d.u16be(5))
            }
            0x95 -> {
                val frameNo = d.u8(0)
                if (frameNo < 1) return
                for (i in 0 until 3) {
                    val idx = (frameNo - 1) * 3 + i
                    val mv = d.u16be(1 + i * 2)
                    if (cellCount == 0 && mv > 0 && idx >= cells.size) cells = cells.copyOf(idx + 1)
                    if (idx < cells.size) cells[idx] = mv / 1000.0
                }
                state = state.copy(cellVoltages = cells.toList())
            }
            0x96 -> {
                val frameNo = d.u8(0)
                if (frameNo < 1) return
                for (i in 0 until 7) {
                    val idx = (frameNo - 1) * 7 + i
                    if (idx < temps.size) temps[idx] = (d.u8(1 + i) - 40).toDouble()
                }
                state = state.copy(temperatures = temps.toList())
            }
            0x97 -> {
                val set = buildSet {
                    for (byte in 0 until 6) for (bit in 0 until 8) {
                        val idx = byte * 8 + bit
                        if (idx < cellCount && (d.u8(byte) shr bit) and 1 == 1) add(idx)
                    }
                }
                state = state.copy(balancingCells = set)
            }
            0x98 -> {
                val alarms = mutableListOf<String>()
                for (byte in 0 until 7) for (bit in 0 until 8) {
                    if ((d.u8(byte) shr bit) and 1 == 1) {
                        alarms += FAULTS.getOrNull(byte)?.getOrNull(bit) ?: "Fault code ${byte}.${bit}"
                    }
                }
                state = state.copy(alarms = alarms)
            }
            0x50 -> {
                val ah = d.u32be(0) / 1000.0
                params["rated_capacity"] = ah
                state = state.copy(nominalAh = ah)
            }
            0x59 -> {
                params["cell_high_1"] = d.u16be(0) / 1000.0
                params["cell_high_2"] = d.u16be(2) / 1000.0
                params["cell_low_1"] = d.u16be(4) / 1000.0
                params["cell_low_2"] = d.u16be(6) / 1000.0
            }
            0x5A -> {
                params["pack_high_1"] = d.u16be(0) / 10.0
                params["pack_high_2"] = d.u16be(2) / 10.0
                params["pack_low_1"] = d.u16be(4) / 10.0
                params["pack_low_2"] = d.u16be(6) / 10.0
            }
            0x5B -> {
                params["chg_oc_1"] = abs(d.u16be(0) - 30000) / 10.0
                params["chg_oc_2"] = abs(d.u16be(2) - 30000) / 10.0
                params["dsg_oc_1"] = abs(d.u16be(4) - 30000) / 10.0
                params["dsg_oc_2"] = abs(d.u16be(6) - 30000) / 10.0
            }
        }
    }

    companion object {
        fun frame(address: Int, cmd: Int, data: ByteArray = ByteArray(8)): ByteArray {
            val f = ByteArray(13)
            f[0] = 0xA5.toByte()
            f[1] = address.toByte()
            f[2] = cmd.toByte()
            f[3] = 0x08
            data.copyInto(f, 4, 0, minOf(8, data.size))
            var sum = 0
            for (i in 0 until 12) sum += f.u8(i)
            f[12] = sum.toByte()
            return f
        }

        private val FAULTS: List<List<String?>> = listOf(
            listOf(
                "Cell voltage high (level 1)", "Cell voltage high (level 2)",
                "Cell voltage low (level 1)", "Cell voltage low (level 2)",
                "Pack voltage high (level 1)", "Pack voltage high (level 2)",
                "Pack voltage low (level 1)", "Pack voltage low (level 2)",
            ),
            listOf(
                "Charge temperature high (level 1)", "Charge temperature high (level 2)",
                "Charge temperature low (level 1)", "Charge temperature low (level 2)",
                "Discharge temperature high (level 1)", "Discharge temperature high (level 2)",
                "Discharge temperature low (level 1)", "Discharge temperature low (level 2)",
            ),
            listOf(
                "Charge overcurrent (level 1)", "Charge overcurrent (level 2)",
                "Discharge overcurrent (level 1)", "Discharge overcurrent (level 2)",
                "SOC high (level 1)", "SOC high (level 2)",
                "SOC low (level 1)", "SOC low (level 2)",
            ),
            listOf(
                "Cell voltage difference (level 1)", "Cell voltage difference (level 2)",
                "Temperature difference (level 1)", "Temperature difference (level 2)",
                null, null, null, null,
            ),
            listOf(
                "Charge MOSFET over-temperature", "Discharge MOSFET over-temperature",
                "Charge MOSFET temperature sensor fault", "Discharge MOSFET temperature sensor fault",
                "Charge MOSFET stuck closed", "Discharge MOSFET stuck closed",
                "Charge MOSFET open circuit", "Discharge MOSFET open circuit",
            ),
            listOf(
                "Front-end chip fault", "Cell voltage sensing lost", "Cell temperature sensor fault",
                "EEPROM fault", "RTC fault", "Precharge failed",
                "External communication fault", "Internal communication fault",
            ),
            listOf(
                "Current sensor fault", "Pack voltage sensing fault",
                "Short-circuit protection fault", "Charging blocked (voltage too low)",
                null, null, null, null,
            ),
        )
    }
}
