package com.xera.bms.core.protocols

import com.xera.bms.core.BaseBmsProtocol
import com.xera.bms.core.BmsAction
import com.xera.bms.core.BmsParameter
import com.xera.bms.core.Capability
import com.xera.bms.core.Command
import com.xera.bms.core.ParamType
import com.xera.bms.core.u16be
import com.xera.bms.core.u32be
import com.xera.bms.core.u8
import com.xera.bms.core.uuid16
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Daly Smart BMS ("A5" protocol over the built-in Bluetooth or a Daly dongle).
 *
 * Frame    : A5 <addr> <cmd> 08 <8 data bytes> <checksum = low byte of the sum of bytes 0..11>
 * Response : A5 01 <cmd> 08 <8 data bytes> <checksum>. Values are big-endian.
 *
 * Live data : 0x90-0x98 (official Daly protocol V1.2).
 * Settings  : read 0x50-0x66; write = read ID - 0x40 (0x10, 0x11, 0x13, 0x1F, 0x20 and 0x21 are
 *             confirmed by captures of Daly's PC tool; the rest follow the same rule). Layouts from
 *             matthewgream/DalyBMSInterface and Polytech-VM-Team/daly_uart_protocol_extended.
 * Every write sends the last frame read from the BMS with only the edited bytes changed, and the
 * app reads the setting back afterwards to confirm the BMS accepted it.
 */
class DalyProtocol(
    private val address: Int = 0x80,
    override val id: String = "daly",
    override val name: String = "Daly Smart BMS",
    override val serviceUuid: String = uuid16(0xFFF0),
    override val notifyUuid: String = uuid16(0xFFF1),
    override val writeUuid: String = uuid16(0xFFF2),
) : BaseBmsProtocol() {

    private enum class Enc { U8, U16, U32_MILLI, U16_MILLI, U16_DECI, CURRENT_CHG, CURRENT_DSG, TEMP }

    private class Field(val read: Int, val offset: Int, val enc: Enc, val param: BmsParameter)

    private fun field(
        read: Int, offset: Int, enc: Enc, key: String, label: String, unit: String,
        group: String, section: String, min: Double, max: Double, decimals: Int,
        writable: Boolean = true, note: String? = null,
    ) = Field(read, offset, enc, BmsParameter(key, label, unit, group, min, max, decimals, writable, section, note = note))

    private val fields: List<Field> = listOf(
        // Protection
        field(0x59, 2, Enc.U16_MILLI, "cell_ov_p", "Cell overvoltage protection", "V", PROT, "Cell voltage", 2.0, 4.5, 3),
        field(0x59, 0, Enc.U16_MILLI, "cell_ov_a", "Cell overvoltage alarm", "V", PROT, "Cell voltage", 2.0, 4.5, 3),
        field(0x59, 6, Enc.U16_MILLI, "cell_uv_p", "Cell undervoltage protection", "V", PROT, "Cell voltage", 1.5, 4.0, 3),
        field(0x59, 4, Enc.U16_MILLI, "cell_uv_a", "Cell undervoltage alarm", "V", PROT, "Cell voltage", 1.5, 4.0, 3),
        field(0x5A, 2, Enc.U16_DECI, "pack_ov_p", "Pack overvoltage protection", "V", PROT, "Pack voltage", 1.0, 250.0, 1),
        field(0x5A, 0, Enc.U16_DECI, "pack_ov_a", "Pack overvoltage alarm", "V", PROT, "Pack voltage", 1.0, 250.0, 1),
        field(0x5A, 6, Enc.U16_DECI, "pack_uv_p", "Pack undervoltage protection", "V", PROT, "Pack voltage", 1.0, 250.0, 1),
        field(0x5A, 4, Enc.U16_DECI, "pack_uv_a", "Pack undervoltage alarm", "V", PROT, "Pack voltage", 1.0, 250.0, 1),
        field(0x5B, 2, Enc.CURRENT_CHG, "chg_oc_p", "Charge overcurrent protection", "A", PROT, "Current", 1.0, 1000.0, 1),
        field(0x5B, 0, Enc.CURRENT_CHG, "chg_oc_a", "Charge overcurrent alarm", "A", PROT, "Current", 1.0, 1000.0, 1),
        field(0x5B, 6, Enc.CURRENT_DSG, "dsg_oc_p", "Discharge overcurrent protection", "A", PROT, "Current", 1.0, 1000.0, 1),
        field(0x5B, 4, Enc.CURRENT_DSG, "dsg_oc_a", "Discharge overcurrent alarm", "A", PROT, "Current", 1.0, 1000.0, 1),
        field(0x60, 0, Enc.U16, "short_circuit", "Short-circuit trip current", "A", PROT, "Current", 0.0, 5000.0, 0, writable = false),
        field(0x5E, 2, Enc.U16_MILLI, "diff_v_p", "Cell difference protection", "V", PROT, "Cell difference", 0.005, 2.0, 3),
        field(0x5E, 0, Enc.U16_MILLI, "diff_v_a", "Cell difference alarm", "V", PROT, "Cell difference", 0.005, 2.0, 3),

        // Battery (Daly "cell characteristics")
        field(0x50, 0, Enc.U32_MILLI, "rated_capacity", "Rated capacity", "Ah", BATT, "Capacity", 1.0, 5000.0, 1),
        field(0x50, 4, Enc.U32_MILLI, "cell_ref_v", "Nominal cell voltage", "V", BATT, "Capacity", 1.0, 4.5, 3),
        field(0x61, 6, Enc.U16_DECI, "soc_set", "State of charge", "%", BATT, "State of charge", 0.0, 100.0, 1,
            note = "Sets the BMS's charge counter. Use after a full charge or when the reading has drifted."),
        field(0x5D, 2, Enc.U16_DECI, "soc_high_p", "High SOC protection", "%", BATT, "State of charge", 0.0, 100.0, 1),
        field(0x5D, 0, Enc.U16_DECI, "soc_high_a", "High SOC alarm", "%", BATT, "State of charge", 0.0, 100.0, 1),
        field(0x5D, 6, Enc.U16_DECI, "soc_low_p", "Low SOC protection", "%", BATT, "State of charge", 0.0, 100.0, 1),
        field(0x5D, 4, Enc.U16_DECI, "soc_low_a", "Low SOC alarm", "%", BATT, "State of charge", 0.0, 100.0, 1),
        field(0x5F, 0, Enc.U16_MILLI, "bal_start", "Balance start voltage", "V", BATT, "Balancing", 2.0, 4.5, 3),
        field(0x5F, 2, Enc.U16_MILLI, "bal_diff", "Balance start difference", "V", BATT, "Balancing", 0.001, 0.5, 3),
        field(0x53, 5, Enc.U16, "sleep_time", "Sleep after idle", "s", BATT, "Operation", 0.0, 65535.0, 0),

        // Temperature
        field(0x5C, 1, Enc.TEMP, "chg_ot_p", "Charge high-temperature protection", "°C", TEMP, "Charging", -40.0, 100.0, 0),
        field(0x5C, 0, Enc.TEMP, "chg_ot_a", "Charge high-temperature alarm", "°C", TEMP, "Charging", -40.0, 100.0, 0),
        field(0x5C, 3, Enc.TEMP, "chg_ut_p", "Charge low-temperature protection", "°C", TEMP, "Charging", -40.0, 60.0, 0),
        field(0x5C, 2, Enc.TEMP, "chg_ut_a", "Charge low-temperature alarm", "°C", TEMP, "Charging", -40.0, 60.0, 0),
        field(0x5C, 5, Enc.TEMP, "dsg_ot_p", "Discharge high-temperature protection", "°C", TEMP, "Discharging", -40.0, 100.0, 0),
        field(0x5C, 4, Enc.TEMP, "dsg_ot_a", "Discharge high-temperature alarm", "°C", TEMP, "Discharging", -40.0, 100.0, 0),
        field(0x5C, 7, Enc.TEMP, "dsg_ut_p", "Discharge low-temperature protection", "°C", TEMP, "Discharging", -40.0, 60.0, 0),
        field(0x5C, 6, Enc.TEMP, "dsg_ut_a", "Discharge low-temperature alarm", "°C", TEMP, "Discharging", -40.0, 60.0, 0),
        field(0x5E, 5, Enc.TEMP, "diff_t_p", "Temperature difference protection", "°C", TEMP, "Difference", 1.0, 60.0, 0),
        field(0x5E, 4, Enc.TEMP, "diff_t_a", "Temperature difference alarm", "°C", TEMP, "Difference", 1.0, 60.0, 0),
        field(0x66, 1, Enc.TEMP, "fan_on", "Fan on temperature", "°C", TEMP, "Fan and heater", -40.0, 100.0, 0, writable = false,
            note = "Read-only until its layout is confirmed against the Daly app."),
        field(0x66, 0, Enc.TEMP, "heat_on", "Heater on temperature", "°C", TEMP, "Fan and heater", -40.0, 60.0, 0, writable = false),

        // Collector boards
        field(0x51, 0, Enc.U8, "boards", "Collector boards", "", BOARDS, "Collector boards", 1.0, 3.0, 0),
        field(0x51, 1, Enc.U8, "board1_cells", "Board 1 cells", "", BOARDS, "Cells per board", 0.0, 32.0, 0),
        field(0x51, 2, Enc.U8, "board2_cells", "Board 2 cells", "", BOARDS, "Cells per board", 0.0, 32.0, 0),
        field(0x51, 3, Enc.U8, "board3_cells", "Board 3 cells", "", BOARDS, "Cells per board", 0.0, 32.0, 0),
        field(0x51, 4, Enc.U8, "board1_ntc", "Board 1 temperature sensors", "", BOARDS, "Temperature sensors per board", 0.0, 16.0, 0),
        field(0x51, 5, Enc.U8, "board2_ntc", "Board 2 temperature sensors", "", BOARDS, "Temperature sensors per board", 0.0, 16.0, 0),
        field(0x51, 6, Enc.U8, "board3_ntc", "Board 3 temperature sensors", "", BOARDS, "Temperature sensors per board", 0.0, 16.0, 0),
    )
    private val fieldByKey = fields.associateBy { it.param.key }

    private val textParams = listOf(
        BmsParameter(
            KEY_CODE, "Battery serial number", "", DEVICE, section = "Identity", type = ParamType.TEXT,
            maxLength = CODE_LENGTH, note = "Daly \"battery code\", up to 35 characters.",
        ),
        BmsParameter("battery_type", "Battery type", "", DEVICE, section = "Identity", type = ParamType.INFO, writable = false),
        BmsParameter("production_date", "Production date", "", DEVICE, section = "Identity", type = ParamType.INFO, writable = false),
        BmsParameter("hardware_version", "Hardware version", "", DEVICE, section = "Firmware", type = ParamType.INFO, writable = false),
        BmsParameter("software_version", "Software version", "", DEVICE, section = "Firmware", type = ParamType.INFO, writable = false),
    )

    override val capabilities = setOf(Capability.MOSFET_CONTROL, Capability.PARAM_READ, Capability.PARAM_WRITE)
    override val parameters: List<BmsParameter> = fields.map { it.param } + textParams
    override val actions = listOf(
        BmsAction(
            ACTION_RESET, "Reset BMS",
            "Sends Daly command 0x00. Depending on the firmware this restarts the BMS or restores factory " +
                "defaults, including cell count and protection limits. Write down your settings first.",
            dangerous = true,
        ),
    )

    /** Last 8-byte payload read for each settings command, used as the base for writes. */
    private val raw = HashMap<Int, ByteArray>()
    /** Multi-frame strings: command -> chunks indexed by frame number. */
    private val chunks = HashMap<Int, Array<String?>>()

    private var cellCount = 0
    private var tempCount = 0
    private var cells = DoubleArray(0)
    private var temps = DoubleArray(0)

    private fun cmd(id: Int, delay: Long = 140, label: String = "") = Command(frame(address, id), delay, label)

    override fun initCommands() = listOf(
        cmd(0x94, 200, "Status (cell count)"),
        cmd(0x90, 200, "Voltage / current / SOC"),
        cmd(0x50, 200, "Rated capacity"),
        cmd(0x53, 200, "Battery info"),
        cmd(0x57, 400, "Battery code"),
        cmd(0x63, 300, "Hardware version"),
        cmd(0x62, 300, "Software version"),
    )

    override fun pollCommands(nowMs: Long) = listOf(
        cmd(0x90, label = "Voltage / current / SOC"),
        cmd(0x93, label = "MOSFET state / remaining capacity"),
        cmd(0x94, label = "Status"),
        cmd(0x95, 300, "Cell voltages"),
        cmd(0x96, 180, "Temperatures"),
        cmd(0x97, label = "Balancing"),
        cmd(0x98, label = "Faults"),
    )

    override fun quickCommands() = listOf(
        cmd(0x90, label = "0x90 SOC"), cmd(0x93, label = "0x93 MOSFET"), cmd(0x94, label = "0x94 Status"),
        cmd(0x95, label = "0x95 Cells"), cmd(0x96, label = "0x96 Temps"), cmd(0x98, label = "0x98 Faults"),
        cmd(0x57, label = "0x57 Battery code"), cmd(0x61, label = "0x61 RTC + SOC"), cmd(0x66, label = "0x66 Fan / heater"),
    )

    override fun readParameterCommands(): List<Command> =
        SETTINGS_READS.map { id -> cmd(id, if (id == 0x57) 450L else 260L, "Read 0x%02X".format(id)) }

    override fun readBackCommands(param: BmsParameter): List<Command> {
        val id = if (param.key == KEY_CODE) 0x57 else fieldByKey[param.key]?.read ?: return readParameterCommands()
        return listOf(cmd(id, 450, "Confirm ${param.label}"))
    }

    override fun mosfetCommands(charge: Boolean, discharge: Boolean) = listOf(
        Command(frame(address, 0xDA, byteArrayOf(if (charge) 1 else 0, 0, 0, 0, 0, 0, 0, 0)), 300, "Charge MOSFET"),
        Command(frame(address, 0xD9, byteArrayOf(if (discharge) 1 else 0, 0, 0, 0, 0, 0, 0, 0)), 300, "Discharge MOSFET"),
    )

    override fun writeParameterCommands(param: BmsParameter, value: Double): List<Command> {
        val f = fieldByKey[param.key] ?: return emptyList()
        if (!f.param.writable) return emptyList()
        val data = if (f.read == 0x61) {
            // Keep the BMS clock bytes exactly as read; only replace the SOC (0.1 %).
            raw[0x61]?.copyOf() ?: clockBytes()
        } else {
            raw[f.read]?.copyOf() ?: return emptyList()
        }
        encode(f.enc, data, f.offset, value)
        return listOf(Command(frame(address, f.read - 0x40, data), 400, "Write ${param.label}"))
    }

    override fun writeTextCommands(param: BmsParameter, text: String): List<Command> {
        if (param.key != KEY_CODE) return emptyList()
        val bytes = text.filter { it.code in 0x20..0x7E }.take(CODE_LENGTH).toByteArray(Charsets.US_ASCII)
        return (0 until CODE_FRAMES).map { n ->
            val data = ByteArray(8)
            data[0] = (n + 1).toByte()
            for (i in 0 until 7) {
                val idx = n * 7 + i
                data[1 + i] = if (idx < bytes.size) bytes[idx] else 0
            }
            Command(frame(address, 0x57 - 0x40, data), 160, "Write battery code ${n + 1}/$CODE_FRAMES")
        }
    }

    override fun actionCommands(action: BmsAction): List<Command> = when (action.key) {
        ACTION_RESET -> listOf(cmd(0x00, 600, "Reset BMS"))
        else -> emptyList()
    }

    override fun reset() {
        super.reset()
        raw.clear()
        chunks.clear()
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
                soc = d.u16be(6) / 10.0,
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
                        alarms += FAULTS.getOrNull(byte)?.getOrNull(bit) ?: "Fault ${byte}.${bit}"
                    }
                }
                if (d.u8(7) != 0) alarms += "Fault code ${d.u8(7)}"
                state = state.copy(alarms = alarms)
            }
            0x57 -> collectString(0x57, CODE_FRAMES, d) { code ->
                texts[KEY_CODE] = code
                state = state.copy(batteryCode = code.ifEmpty { null })
            }
            0x62 -> collectString(0x62, 2, d) { v ->
                texts["software_version"] = v
                state = state.copy(softwareVersion = v.ifEmpty { null })
            }
            0x63 -> collectString(0x63, 2, d) { v ->
                texts["hardware_version"] = v
                state = state.copy(hardwareVersion = v.ifEmpty { null })
            }
            in SETTINGS_READS -> {
                raw[cmd] = d.copyOf()
                fields.filter { it.read == cmd }.forEach { params[it.param.key] = decode(it.enc, d, it.offset) }
                when (cmd) {
                    0x50 -> state = state.copy(nominalAh = d.u32be(0) / 1000.0)
                    0x53 -> {
                        val y = d.u8(2)
                        val m = d.u8(3)
                        val day = d.u8(4)
                        if (m in 1..12 && day in 1..31) {
                            val date = "${2000 + y}-${m.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"
                            texts["production_date"] = date
                            state = state.copy(manufactureDate = date)
                        }
                        texts["battery_type"] = batteryTypeName(d.u8(1))
                    }
                }
            }
        }
    }

    /** Daly strings arrive as numbered frames: byte 0 = frame number (from 1), bytes 1-7 = text. */
    private fun collectString(cmd: Int, frames: Int, d: ByteArray, done: (String) -> Unit) {
        val n = d.u8(0)
        if (n !in 1..frames) return
        val parts = chunks.getOrPut(cmd) { arrayOfNulls(frames) }
        parts[n - 1] = String(ByteArray(7) { d[1 + it] }, Charsets.ISO_8859_1)
        if (parts.all { it != null }) {
            val text = parts.joinToString("") { it.orEmpty() }
                .map { if (it.code in 0x20..0x7E) it else ' ' }
                .joinToString("")
                .trim()
                .replace(Regex(" +"), " ")
            chunks.remove(cmd)
            done(text)
        }
    }

    private fun decode(enc: Enc, d: ByteArray, o: Int): Double = when (enc) {
        Enc.U8 -> d.u8(o).toDouble()
        Enc.U16 -> d.u16be(o).toDouble()
        Enc.U32_MILLI -> d.u32be(o) / 1000.0
        Enc.U16_MILLI -> d.u16be(o) / 1000.0
        Enc.U16_DECI -> d.u16be(o) / 10.0
        Enc.CURRENT_CHG, Enc.CURRENT_DSG -> abs(d.u16be(o) - 30000) / 10.0
        Enc.TEMP -> (d.u8(o) - 40).toDouble()
    }

    private fun encode(enc: Enc, d: ByteArray, o: Int, value: Double) {
        when (enc) {
            Enc.U8 -> d[o] = value.roundToInt().coerceIn(0, 255).toByte()
            Enc.U16 -> put16(d, o, value.roundToInt())
            Enc.U32_MILLI -> put32(d, o, (value * 1000).roundToLong())
            Enc.U16_MILLI -> put16(d, o, (value * 1000).roundToInt())
            Enc.U16_DECI -> put16(d, o, (value * 10).roundToInt())
            Enc.CURRENT_CHG, Enc.CURRENT_DSG -> {
                // 30000 = 0 A. Keep the sign convention the BMS already uses for this field.
                val existing = d.u16be(o)
                val magnitude = (abs(value) * 10).roundToInt()
                val negative = if (existing == 30000) enc == Enc.CURRENT_DSG else existing < 30000
                put16(d, o, if (negative) 30000 - magnitude else 30000 + magnitude)
            }
            Enc.TEMP -> d[o] = (value.roundToInt() + 40).coerceIn(0, 255).toByte()
        }
    }

    private fun put16(d: ByteArray, o: Int, v: Int) {
        val c = v.coerceIn(0, 0xFFFF)
        d[o] = (c shr 8).toByte()
        d[o + 1] = c.toByte()
    }

    private fun put32(d: ByteArray, o: Int, v: Long) {
        for (i in 0 until 4) d[o + i] = (v shr (8 * (3 - i))).toByte()
    }

    private fun clockBytes(): ByteArray {
        val now = LocalDateTime.now()
        return byteArrayOf(
            (now.year - 2000).toByte(), now.monthValue.toByte(), now.dayOfMonth.toByte(),
            now.hour.toByte(), now.minute.toByte(), now.second.toByte(), 0, 0,
        )
    }

    private fun batteryTypeName(code: Int) = when (code) {
        1 -> "LiFePO4"
        2 -> "Li-ion (ternary)"
        3 -> "LTO"
        else -> "Type code $code"
    }

    companion object {
        private const val PROT = "Protection"
        private const val BATT = "Battery"
        private const val TEMP = "Temperature"
        private const val BOARDS = "Boards"
        private const val DEVICE = "Device"
        const val KEY_CODE = "battery_code"
        const val ACTION_RESET = "reset"
        private const val CODE_FRAMES = 5
        private const val CODE_LENGTH = CODE_FRAMES * 7

        private val SETTINGS_READS = listOf(
            0x50, 0x51, 0x53, 0x57, 0x59, 0x5A, 0x5B, 0x5C, 0x5D, 0x5E, 0x5F, 0x60, 0x61, 0x62, 0x63, 0x66,
        )

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
