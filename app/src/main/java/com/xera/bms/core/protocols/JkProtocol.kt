package com.xera.bms.core.protocols

import com.xera.bms.core.BaseBmsProtocol
import com.xera.bms.core.BmsParameter
import com.xera.bms.core.Capability
import com.xera.bms.core.Command
import com.xera.bms.core.ascii
import com.xera.bms.core.bytesOf
import com.xera.bms.core.s16le
import com.xera.bms.core.s32le
import com.xera.bms.core.u16be
import com.xera.bms.core.u16le
import com.xera.bms.core.u32le
import com.xera.bms.core.u8
import com.xera.bms.core.uuid16
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * JK / Jikong BMS, "JK02" BLE protocol (JK-B1A24S, JK-B2A24S, JK-BD6A..., PB series).
 *
 * Request (20 bytes): AA 55 90 EB <reg> <len> <value LE x4> 00 x9 <crc = sum(0..18) low byte>
 * Response (300 B)  : 55 AA EB 90 <type> <counter> ... <crc = sum(0..298) low byte>
 *   type 0x01 = settings, 0x02 = live cell info (streamed continuously), 0x03 = device info.
 * Values are little-endian. Firmware 11.x and newer uses the 32-cell layout (fields shifted),
 * which is detected automatically from the frame contents.
 */
class JkProtocol(
    override val id: String = "jk",
    override val name: String = "JK BMS (JK02)",
    override val serviceUuid: String = uuid16(0xFFE0),
    override val notifyUuid: String = uuid16(0xFFE1),
    override val writeUuid: String = uuid16(0xFFE1),
) : BaseBmsProtocol() {

    private class Setting(val offset: Int, val scale: Double, val param: BmsParameter) {
        /** JK register address = (offset - 6) / 4 + 1 for the settings block. */
        val register: Int get() = (offset - 6) / 4 + 1
    }

    private val settings = listOf(
        Setting(10, 0.001, BmsParameter("cell_uvp", "Cell undervoltage protection", "V", "Cell voltage", 1.5, 4.5, 3)),
        Setting(14, 0.001, BmsParameter("cell_uvpr", "Cell undervoltage recovery", "V", "Cell voltage", 1.5, 4.5, 3)),
        Setting(18, 0.001, BmsParameter("cell_ovp", "Cell overvoltage protection", "V", "Cell voltage", 1.5, 4.5, 3)),
        Setting(22, 0.001, BmsParameter("cell_ovpr", "Cell overvoltage recovery", "V", "Cell voltage", 1.5, 4.5, 3)),
        Setting(30, 0.001, BmsParameter("soc100_v", "Voltage at 100% SOC", "V", "Cell voltage", 1.5, 4.5, 3)),
        Setting(34, 0.001, BmsParameter("soc0_v", "Voltage at 0% SOC", "V", "Cell voltage", 1.5, 4.5, 3)),
        Setting(46, 0.001, BmsParameter("power_off_v", "Power-off voltage", "V", "Cell voltage", 1.5, 4.5, 3)),
        Setting(26, 0.001, BmsParameter("bal_trigger", "Balance trigger difference", "V", "Balancing", 0.001, 1.0, 3)),
        Setting(78, 0.001, BmsParameter("max_bal_i", "Max balance current", "A", "Balancing", 0.0, 10.0, 1)),
        Setting(50, 0.001, BmsParameter("max_chg_i", "Max charge current", "A", "Current", 1.0, 600.0, 1)),
        Setting(54, 1.0, BmsParameter("chg_ocp_delay", "Charge overcurrent delay", "s", "Current", 1.0, 600.0, 0)),
        Setting(62, 0.001, BmsParameter("max_dsg_i", "Max discharge current", "A", "Current", 1.0, 600.0, 1)),
        Setting(66, 1.0, BmsParameter("dsg_ocp_delay", "Discharge overcurrent delay", "s", "Current", 1.0, 600.0, 0)),
        Setting(82, 0.1, BmsParameter("chg_otp", "Charge over-temperature", "°C", "Temperature", 0.0, 100.0, 1)),
        Setting(86, 0.1, BmsParameter("chg_otpr", "Charge over-temperature recovery", "°C", "Temperature", 0.0, 100.0, 1)),
        Setting(90, 0.1, BmsParameter("dsg_otp", "Discharge over-temperature", "°C", "Temperature", 0.0, 100.0, 1)),
        Setting(94, 0.1, BmsParameter("dsg_otpr", "Discharge over-temperature recovery", "°C", "Temperature", 0.0, 100.0, 1)),
        Setting(98, 0.1, BmsParameter("chg_utp", "Charge under-temperature", "°C", "Temperature", -40.0, 40.0, 1)),
        Setting(102, 0.1, BmsParameter("chg_utpr", "Charge under-temperature recovery", "°C", "Temperature", -40.0, 40.0, 1)),
        Setting(106, 0.1, BmsParameter("mos_otp", "MOSFET over-temperature", "°C", "Temperature", 0.0, 120.0, 1)),
        Setting(110, 0.1, BmsParameter("mos_otpr", "MOSFET over-temperature recovery", "°C", "Temperature", 0.0, 120.0, 1)),
        Setting(130, 0.001, BmsParameter("capacity", "Battery capacity", "Ah", "Capacity", 1.0, 2000.0, 1)),
        Setting(114, 1.0, BmsParameter("cell_count", "Cell count", "", "Capacity", 1.0, 32.0, 0, writable = false)),
    )
    private val settingByKey = settings.associateBy { it.param.key }

    private var lastFrameMs = 0L
    /** From device info: true = firmware uses the 32-cell layout. */
    private var firmwareHint32: Boolean? = null

    override val capabilities = Capability.entries.toSet()
    override val parameters: List<BmsParameter> = settings.map { it.param }

    override fun initCommands() = listOf(
        Command(command(CMD_DEVICE_INFO), 400, "Device info"),
        Command(command(CMD_CELL_INFO), 400, "Start live data"),
    )

    /** JK streams cell info by itself; we only re-trigger it if the stream stalls. */
    override fun pollCommands(nowMs: Long): List<Command> =
        if (nowMs - lastFrameMs > 6000) listOf(Command(command(CMD_CELL_INFO), 400, "Start live data")) else emptyList()

    override fun mosfetCommands(charge: Boolean, discharge: Boolean) = listOf(
        Command(command(0x1D, if (charge) 1 else 0, 4), 400, "Charge switch"),
        Command(command(0x1E, if (discharge) 1 else 0, 4), 400, "Discharge switch"),
    )

    override fun balancerCommands(enabled: Boolean) =
        listOf(Command(command(0x1F, if (enabled) 1 else 0, 4), 400, "Balancer switch"))

    override fun readParameterCommands() = initCommands()

    override fun writeParameterCommands(param: BmsParameter, value: Double): List<Command> {
        val s = settingByKey[param.key] ?: return emptyList()
        if (!s.param.writable) return emptyList()
        val raw = (value / s.scale).roundToLong()
        return listOf(Command(command(s.register, raw, 4), 500, "Write ${param.label}")) + initCommands()
    }

    override fun reset() {
        super.reset()
        lastFrameMs = 0L
        firmwareHint32 = null
    }

    override fun feed(data: ByteArray, nowMs: Long): Int {
        buffer.append(data)
        var frames = 0
        while (true) {
            val h = buffer.indexOf(HEADER)
            if (h < 0) {
                // Keep a possible partial header at the very end.
                if (buffer.size > 3) buffer.drop(buffer.size - 3)
                break
            }
            if (h > 0) buffer.drop(h)
            val f = buffer.peek()
            if (f.size < FRAME_SIZE) break
            var sum = 0
            for (i in 0 until FRAME_SIZE - 1) sum += f.u8(i)
            if ((sum and 0xFF) != f.u8(FRAME_SIZE - 1)) {
                buffer.drop(HEADER.size)
                continue
            }
            val frame = f.copyOf(FRAME_SIZE)
            buffer.drop(FRAME_SIZE)
            when (frame.u8(4)) {
                0x01 -> decodeSettings(frame)
                0x02 -> decodeCellInfo(frame)
                0x03 -> decodeDeviceInfo(frame)
            }
            lastFrameMs = nowMs
            frames++
        }
        return frames
    }

    private fun decodeDeviceInfo(f: ByteArray) {
        val model = f.ascii(6, 16)
        val hw = f.ascii(22, 8)
        val sw = f.ascii(30, 8)
        val major = (sw.substringBefore('.').toIntOrNull() ?: hw.substringBefore('.').toIntOrNull())
        if (major != null) firmwareHint32 = major >= 11
        state = state.copy(
            model = model.ifEmpty { null },
            hardwareVersion = hw.ifEmpty { null },
            softwareVersion = sw.ifEmpty { null },
        )
    }

    private fun decodeSettings(f: ByteArray) {
        settings.forEach { s -> params[s.param.key] = f.s32le(s.offset) * s.scale }
        state = state.copy(
            balancerEnabled = f.u32le(126) != 0L,
            nominalAh = state.nominalAh ?: (f.u32le(130) / 1000.0),
        )
    }

    /** Decide 24S vs 32S layout by checking which "total voltage" field matches the cell sum. */
    private fun is32Layout(f: ByteArray): Boolean {
        var sum24 = 0.0
        for (i in 0 until 24) sum24 += f.u16le(6 + i * 2) / 1000.0
        var sum32 = 0.0
        for (i in 0 until 32) sum32 += f.u16le(6 + i * 2) / 1000.0
        val total24 = f.u32le(118) / 1000.0
        val total32 = f.u32le(150) / 1000.0
        val ok24 = sum24 > 1.0 && abs(total24 - sum24) < sum24 * 0.1 + 0.5
        val ok32 = sum32 > 1.0 && abs(total32 - sum32) < sum32 * 0.1 + 0.5
        return when {
            ok32 && !ok24 -> true
            ok24 && !ok32 -> false
            else -> firmwareHint32 ?: true
        }
    }

    private fun decodeCellInfo(f: ByteArray) {
        val wide = is32Layout(f)
        val o = if (wide) 16 else 0
        val maxCells = if (wide) 32 else 24
        val raw = (0 until maxCells).map { f.u16le(6 + it * 2) / 1000.0 }
        val mask = f.u32le(54 + o)
        val maskCount = java.lang.Long.bitCount(mask)
        val lastNonZero = raw.indexOfLast { it > 0.1 } + 1
        val count = if (maskCount in 1..maxCells && maskCount >= lastNonZero) maskCount else lastNonZero
        val cellVoltages = raw.take(count)
        val resistances = (0 until count).map { f.u16le(64 + o + it * 2) / 1000.0 }

        val o2 = o * 2
        val temps = listOf(f.s16le(130 + o2) / 10.0, f.s16le(132 + o2) / 10.0).filter { it > -60 && it < 150 }
        val mosTemp = if (wide) f.s16le(112 + o2) / 10.0 else f.s16le(134) / 10.0
        val errors = if (wide) f.u16be(134 + o2) else f.u16be(136)
        val balanceAction = f.u8(140 + o2)
        val maxCell = f.u8(62 + o)
        val minCell = f.u8(63 + o)
        val soh = f.u8(158 + o2)

        state = state.copy(
            cellVoltages = cellVoltages,
            cellResistances = resistances,
            voltage = f.u32le(118 + o2) / 1000.0,
            current = f.s32le(126 + o2) / 1000.0,
            temperatures = temps,
            mosTemperature = mosTemp.takeIf { it > -60 && it < 150 },
            alarms = ERRORS.filterIndexed { bit, _ -> errors and (1 shl bit) != 0 },
            balanceCurrent = f.s16le(138 + o2) / 1000.0,
            balancingCells = if (balanceAction != 0) setOf(maxCell, minCell).filter { it < count }.toSet() else emptySet(),
            soc = f.u8(141 + o2),
            remainingAh = f.u32le(142 + o2) / 1000.0,
            nominalAh = f.u32le(146 + o2) / 1000.0,
            cycles = f.u32le(150 + o2).toInt(),
            soh = soh.takeIf { it in 1..100 },
            chargeFet = f.u8(166 + o2) != 0,
            dischargeFet = f.u8(167 + o2) != 0,
        )
    }

    companion object {
        const val FRAME_SIZE = 300
        const val CMD_CELL_INFO = 0x96
        const val CMD_DEVICE_INFO = 0x97
        val HEADER: ByteArray = bytesOf(0x55, 0xAA, 0xEB, 0x90)

        fun command(register: Int, value: Long = 0, length: Int = 0): ByteArray {
            val f = ByteArray(20)
            f[0] = 0xAA.toByte()
            f[1] = 0x55
            f[2] = 0x90.toByte()
            f[3] = 0xEB.toByte()
            f[4] = register.toByte()
            f[5] = length.toByte()
            f[6] = value.toByte()
            f[7] = (value shr 8).toByte()
            f[8] = (value shr 16).toByte()
            f[9] = (value shr 24).toByte()
            var sum = 0
            for (i in 0 until 19) sum += f.u8(i)
            f[19] = sum.toByte()
            return f
        }

        fun command(register: Int, value: Int, length: Int): ByteArray = command(register, value.toLong(), length)

        private val ERRORS = listOf(
            "Charge over-temperature", "Charge under-temperature", "Error bit 2", "Cell undervoltage",
            "Error bit 4", "Error bit 5", "Error bit 6", "Error bit 7",
            "Error bit 8", "Error bit 9", "Cell count differs from settings", "Current sensor anomaly",
            "Cell overvoltage", "Error bit 13", "Charge overcurrent", "Error bit 15",
        )
    }
}
