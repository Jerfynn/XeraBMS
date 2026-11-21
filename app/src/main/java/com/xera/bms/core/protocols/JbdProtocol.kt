package com.xera.bms.core.protocols

import com.xera.bms.core.BaseBmsProtocol
import com.xera.bms.core.BmsParameter
import com.xera.bms.core.Capability
import com.xera.bms.core.Command
import com.xera.bms.core.ascii
import com.xera.bms.core.s16be
import com.xera.bms.core.u16be
import com.xera.bms.core.u8
import com.xera.bms.core.uuid16
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * JBD (Jiabaida) "Xiaoxiang Smart BMS" protocol. Also used by Overkill Solar and many
 * rebranded LiFePO4 batteries.
 *
 * Read request : DD A5 <reg> 00 <chkH> <chkL> 77
 * Write request: DD 5A <reg> <len> <data..> <chkH> <chkL> 77
 * Response     : DD <reg> <status> <len> <data..> <chkH> <chkL> 77
 * Checksum     : 0x10000 - sum(bytes between the command byte and the checksum), big-endian.
 * All multi-byte values are big-endian.
 */
class JbdProtocol(
    override val id: String = "jbd",
    override val name: String = "JBD / Xiaoxiang Smart BMS",
    override val serviceUuid: String = uuid16(0xFF00),
    override val notifyUuid: String = uuid16(0xFF01),
    override val writeUuid: String = uuid16(0xFF02),
) : BaseBmsProtocol() {

    private enum class Enc { CAP_10MAH, MV, V_10MV, A_10MA, A_10MA_NEG, TEMP_01K, RAW }

    private class Reg(val address: Int, val param: BmsParameter, val enc: Enc)

    private val registers = listOf(
        Reg(0x10, BmsParameter("design_cap", "Design capacity", "Ah", "Capacity", 1.0, 650.0, 2), Enc.CAP_10MAH),
        Reg(0x11, BmsParameter("cycle_cap", "Cycle capacity", "Ah", "Capacity", 1.0, 650.0, 2), Enc.CAP_10MAH),
        Reg(0x17, BmsParameter("cycle_count", "Cycle count", "", "Capacity", 0.0, 65535.0, 0, writable = false), Enc.RAW),

        Reg(0x24, BmsParameter("cell_ov", "Cell overvoltage", "V", "Cell voltage", 2.0, 4.5, 3), Enc.MV),
        Reg(0x25, BmsParameter("cell_ov_rel", "Cell overvoltage release", "V", "Cell voltage", 2.0, 4.5, 3), Enc.MV),
        Reg(0x26, BmsParameter("cell_uv", "Cell undervoltage", "V", "Cell voltage", 1.5, 4.0, 3), Enc.MV),
        Reg(0x27, BmsParameter("cell_uv_rel", "Cell undervoltage release", "V", "Cell voltage", 1.5, 4.0, 3), Enc.MV),

        Reg(0x20, BmsParameter("pack_ov", "Pack overvoltage", "V", "Pack voltage", 3.0, 150.0, 2), Enc.V_10MV),
        Reg(0x21, BmsParameter("pack_ov_rel", "Pack overvoltage release", "V", "Pack voltage", 3.0, 150.0, 2), Enc.V_10MV),
        Reg(0x22, BmsParameter("pack_uv", "Pack undervoltage", "V", "Pack voltage", 3.0, 150.0, 2), Enc.V_10MV),
        Reg(0x23, BmsParameter("pack_uv_rel", "Pack undervoltage release", "V", "Pack voltage", 3.0, 150.0, 2), Enc.V_10MV),

        Reg(0x28, BmsParameter("chg_oc", "Charge overcurrent", "A", "Current", 1.0, 300.0, 2), Enc.A_10MA),
        Reg(0x29, BmsParameter("dsg_oc", "Discharge overcurrent", "A", "Current", 1.0, 300.0, 2), Enc.A_10MA_NEG),

        Reg(0x18, BmsParameter("chg_ot", "Charge over-temperature", "°C", "Temperature", -20.0, 100.0, 1), Enc.TEMP_01K),
        Reg(0x19, BmsParameter("chg_ot_rel", "Charge over-temperature release", "°C", "Temperature", -20.0, 100.0, 1), Enc.TEMP_01K),
        Reg(0x1A, BmsParameter("chg_ut", "Charge under-temperature", "°C", "Temperature", -40.0, 40.0, 1), Enc.TEMP_01K),
        Reg(0x1B, BmsParameter("chg_ut_rel", "Charge under-temperature release", "°C", "Temperature", -40.0, 40.0, 1), Enc.TEMP_01K),
        Reg(0x1C, BmsParameter("dsg_ot", "Discharge over-temperature", "°C", "Temperature", -20.0, 100.0, 1), Enc.TEMP_01K),
        Reg(0x1D, BmsParameter("dsg_ot_rel", "Discharge over-temperature release", "°C", "Temperature", -20.0, 100.0, 1), Enc.TEMP_01K),
        Reg(0x1E, BmsParameter("dsg_ut", "Discharge under-temperature", "°C", "Temperature", -40.0, 40.0, 1), Enc.TEMP_01K),
        Reg(0x1F, BmsParameter("dsg_ut_rel", "Discharge under-temperature release", "°C", "Temperature", -40.0, 40.0, 1), Enc.TEMP_01K),

        Reg(0x2A, BmsParameter("bal_start", "Balance start voltage", "V", "Balancing", 2.0, 4.5, 3), Enc.MV),
        Reg(0x2B, BmsParameter("bal_window", "Balance window", "mV", "Balancing", 1.0, 500.0, 0), Enc.RAW),
    )

    private val regByAddress = registers.associateBy { it.address }
    private val regByKey = registers.associateBy { it.param.key }

    override val capabilities = setOf(Capability.MOSFET_CONTROL, Capability.PARAM_READ, Capability.PARAM_WRITE)
    override val parameters: List<BmsParameter> = registers.map { it.param }

    override fun initCommands() = listOf(
        Command(readFrame(CMD_VERSION), 300, "Hardware version"),
        Command(readFrame(CMD_BASIC), 300, "Basic info"),
        Command(readFrame(CMD_CELLS), 300, "Cell voltages"),
    )

    override fun pollCommands(nowMs: Long) = listOf(
        Command(readFrame(CMD_BASIC), 300, "Basic info"),
        Command(readFrame(CMD_CELLS), 300, "Cell voltages"),
    )

    override fun quickCommands() = initCommands() + listOf(
        Command(ENTER_FACTORY, 300, "Enter factory mode"),
        Command(EXIT_FACTORY, 300, "Exit factory mode"),
    )

    override fun mosfetCommands(charge: Boolean, discharge: Boolean): List<Command> {
        // Register 0xE1: bit0 set = charge OFF, bit1 set = discharge OFF.
        val mask = (if (charge) 0 else 1) or (if (discharge) 0 else 2)
        return listOf(Command(writeFrame(0xE1, byteArrayOf(0, mask.toByte())), 400, "MOSFET control"))
    }

    override fun readParameterCommands(): List<Command> {
        val list = mutableListOf(Command(ENTER_FACTORY, 300, "Enter factory mode"))
        registers.forEach { list += Command(readFrame(it.address), 180, "Read ${it.param.label}") }
        list += Command(EXIT_FACTORY, 300, "Exit factory mode")
        return list
    }

    override fun writeParameterCommands(param: BmsParameter, value: Double): List<Command> {
        val reg = regByKey[param.key] ?: return emptyList()
        if (!reg.param.writable) return emptyList()
        val raw = encode(reg.enc, value) and 0xFFFF
        return listOf(
            Command(ENTER_FACTORY, 300, "Enter factory mode"),
            Command(writeFrame(reg.address, byteArrayOf((raw shr 8).toByte(), raw.toByte())), 300, "Write ${param.label}"),
            Command(EXIT_FACTORY_SAVE, 500, "Save and exit factory mode"),
        )
    }

    override fun feed(data: ByteArray, nowMs: Long): Int {
        buffer.append(data)
        var frames = 0
        while (true) {
            val start = buffer.peek().indexOfFirst { (it.toInt() and 0xFF) == 0xDD }
            if (start < 0) {
                buffer.clear()
                break
            }
            if (start > 0) buffer.drop(start)
            val f = buffer.peek()
            if (f.size < 4) break
            val len = f.u8(3)
            val total = len + 7
            if (f.size < total) break
            val valid = f.u8(total - 1) == 0x77 && checksum(f, 2, 4 + len) == f.u16be(4 + len)
            if (!valid) {
                buffer.drop(1)
                continue
            }
            val cmd = f.u8(1)
            val status = f.u8(2)
            val payload = f.copyOfRange(4, 4 + len)
            buffer.drop(total)
            frames++
            if (status == 0) handle(cmd, payload)
        }
        return frames
    }

    private fun handle(cmd: Int, p: ByteArray) {
        when (cmd) {
            CMD_BASIC -> parseBasic(p)
            CMD_CELLS -> state = state.copy(cellVoltages = (0 until p.size / 2).map { p.u16be(it * 2) / 1000.0 })
            CMD_VERSION -> state = state.copy(model = p.ascii(0, p.size).ifEmpty { null })
            else -> {
                val reg = regByAddress[cmd] ?: return
                if (p.size >= 2) params[reg.param.key] = decode(reg.enc, p.u16be(0))
            }
        }
    }

    private fun parseBasic(p: ByteArray) {
        if (p.size < 23) return
        val ntc = p.u8(22)
        val temps = (0 until ntc).mapNotNull { i ->
            val o = 23 + i * 2
            if (o + 1 < p.size) (p.u16be(o) - 2731) / 10.0 else null
        }
        val protection = p.u16be(16)
        val alarms = PROTECTION_BITS.filterIndexed { bit, _ -> protection and (1 shl bit) != 0 }
        val balLow = p.u16be(12)
        val balHigh = p.u16be(14)
        val balancing = buildSet {
            for (i in 0 until 16) {
                if (balLow and (1 shl i) != 0) add(i)
                if (balHigh and (1 shl i) != 0) add(i + 16)
            }
        }
        val date = p.u16be(10)
        val dateText = if (date != 0) {
            val month = ((date shr 5) and 0x0F).toString().padStart(2, '0')
            val day = (date and 0x1F).toString().padStart(2, '0')
            "${2000 + (date shr 9)}-$month-$day"
        } else null
        val sw = p.u8(18)
        val fet = p.u8(20)
        state = state.copy(
            voltage = p.u16be(0) / 100.0,
            current = p.s16be(2) / 100.0,
            remainingAh = p.u16be(4) / 100.0,
            nominalAh = p.u16be(6) / 100.0,
            cycles = p.u16be(8),
            manufactureDate = dateText,
            balancingCells = balancing,
            alarms = alarms,
            softwareVersion = "${sw shr 4}.${sw and 0x0F}",
            soc = p.u8(19),
            chargeFet = fet and 0x01 != 0,
            dischargeFet = fet and 0x02 != 0,
            temperatures = temps,
        )
    }

    private fun decode(enc: Enc, raw: Int): Double = when (enc) {
        Enc.CAP_10MAH -> raw / 100.0
        Enc.MV -> raw / 1000.0
        Enc.V_10MV -> raw / 100.0
        Enc.A_10MA -> raw.toShort() / 100.0
        Enc.A_10MA_NEG -> abs(raw.toShort().toInt()) / 100.0
        Enc.TEMP_01K -> (raw - 2731) / 10.0
        Enc.RAW -> raw.toDouble()
    }

    private fun encode(enc: Enc, value: Double): Int = when (enc) {
        Enc.CAP_10MAH -> (value * 100).roundToInt()
        Enc.MV -> (value * 1000).roundToInt()
        Enc.V_10MV -> (value * 100).roundToInt()
        Enc.A_10MA -> (value * 100).roundToInt()
        // Discharge overcurrent is stored as a negative 16-bit value.
        Enc.A_10MA_NEG -> -(abs(value) * 100).roundToInt()
        Enc.TEMP_01K -> (value * 10).roundToInt() + 2731
        Enc.RAW -> value.roundToInt()
    }

    companion object {
        const val CMD_BASIC = 0x03
        const val CMD_CELLS = 0x04
        const val CMD_VERSION = 0x05

        val PROTECTION_BITS = listOf(
            "Cell overvoltage", "Cell undervoltage", "Pack overvoltage", "Pack undervoltage",
            "Charge over-temperature", "Charge under-temperature",
            "Discharge over-temperature", "Discharge under-temperature",
            "Charge overcurrent", "Discharge overcurrent", "Short circuit",
            "Front-end IC error", "MOSFET locked by software",
        )

        fun checksum(bytes: ByteArray, from: Int, toExclusive: Int): Int {
            var sum = 0
            for (i in from until toExclusive) sum += bytes.u8(i)
            return (0x10000 - sum) and 0xFFFF
        }

        fun readFrame(reg: Int): ByteArray {
            val f = byteArrayOf(0xDD.toByte(), 0xA5.toByte(), reg.toByte(), 0, 0, 0, 0x77)
            val chk = checksum(f, 2, 4)
            f[4] = (chk shr 8).toByte()
            f[5] = chk.toByte()
            return f
        }

        fun writeFrame(reg: Int, data: ByteArray): ByteArray {
            val f = ByteArray(7 + data.size)
            f[0] = 0xDD.toByte()
            f[1] = 0x5A
            f[2] = reg.toByte()
            f[3] = data.size.toByte()
            data.copyInto(f, 4)
            val chk = checksum(f, 2, 4 + data.size)
            f[4 + data.size] = (chk shr 8).toByte()
            f[5 + data.size] = chk.toByte()
            f[6 + data.size] = 0x77
            return f
        }

        val ENTER_FACTORY: ByteArray = writeFrame(0x00, byteArrayOf(0x56, 0x78))
        val EXIT_FACTORY: ByteArray = writeFrame(0x01, byteArrayOf(0x00, 0x00))
        val EXIT_FACTORY_SAVE: ByteArray = writeFrame(0x01, byteArrayOf(0x28, 0x28))
    }
}
