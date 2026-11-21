package com.xera.bms.core

import com.xera.bms.core.protocols.DalyProtocol
import com.xera.bms.core.protocols.JbdProtocol
import com.xera.bms.core.protocols.JkProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {

    /** BLE delivers data in small notifications; feed it the same way. */
    private fun feedChunked(p: BmsProtocol, data: ByteArray, chunk: Int = 20): Int {
        var frames = 0
        var i = 0
        while (i < data.size) {
            frames += p.feed(data.copyOfRange(i, minOf(data.size, i + chunk)), 1000L)
            i += chunk
        }
        return frames
    }

    private fun near(expected: Double, actual: Double?, tol: Double = 1e-6) {
        assertNotNull(actual)
        assertTrue("expected $expected but was $actual", kotlin.math.abs(expected - actual!!) <= tol)
    }

    // ---------------------------------------------------------------- JBD

    @Test
    fun jbdRequestFramesMatchReference() {
        assertEquals("DD A5 03 00 FF FD 77", JbdProtocol.readFrame(0x03).toHex())
        assertEquals("DD A5 04 00 FF FC 77", JbdProtocol.readFrame(0x04).toHex())
        assertEquals("DD 5A 00 02 56 78 FF 30 77", JbdProtocol.ENTER_FACTORY.toHex())
        assertEquals("DD 5A 01 02 00 00 FF FD 77", JbdProtocol.EXIT_FACTORY.toHex())
        assertEquals("DD 5A 01 02 28 28 FF AD 77", JbdProtocol.EXIT_FACTORY_SAVE.toHex())
        val allOn = JbdProtocol().mosfetCommands(charge = true, discharge = true).single().bytes
        assertEquals("DD 5A E1 02 00 00 FF 1D 77", allOn.toHex())
    }

    @Test
    fun jbdParsesRealBasicInfoFrame() {
        val p = JbdProtocol()
        val frame = hexToBytes("DD 03 00 1B 17 00 00 00 02 D0 03 E8 00 00 20 78 00 00 00 00 00 00 10 48 03 0F 02 0B 76 0B 82 FB FF 77")!!
        assertEquals(1, feedChunked(p, frame))
        val s = p.snapshot
        near(58.88, s.voltage)
        near(0.0, s.current)
        near(7.20, s.remainingAh)
        near(10.00, s.nominalAh)
        assertEquals(72, s.soc)
        assertEquals(true, s.chargeFet)
        assertEquals(true, s.dischargeFet)
        assertEquals(2, s.temperatures.size)
        near(20.3, s.temperatures[0], 1e-9)
        near(21.5, s.temperatures[1], 1e-9)
        assertTrue(s.alarms.isEmpty())
    }

    @Test
    fun jbdParsesCellsAndRegisterReadsWithNoise() {
        val p = JbdProtocol()
        // 4 cells: 3.300, 3.310, 3.295, 3.305 V
        val payload = bytesOf(0x0C, 0xE4, 0x0C, 0xEE, 0x0C, 0xDF, 0x0C, 0xE9)
        val cells = response(0x04, payload)
        // Register 0x24 (cell OV) = 3650 mV
        val reg = response(0x24, bytesOf(0x0E, 0x42))
        // Register 0x29 (discharge OC) = -5000 * 10 mA => 50 A
        val dsg = response(0x29, bytesOf(0xEC, 0x78))
        val noise = bytesOf(0x00, 0x13, 0x77)
        assertEquals(3, feedChunked(p, noise + cells + reg + dsg, 7))
        assertEquals(listOf(3.300, 3.310, 3.295, 3.305), p.snapshot.cellVoltages)
        near(3.650, p.parameterValues["cell_ov"])
        near(50.0, p.parameterValues["dsg_oc"])
        near(15.0, p.snapshot.cellDeltaMv, 1e-6)
    }

    @Test
    fun jbdWriteEncodesDischargeCurrentAsNegative() {
        val p = JbdProtocol()
        val param = p.parameters.first { it.key == "dsg_oc" }
        val cmds = p.writeParameterCommands(param, 50.0)
        assertEquals(3, cmds.size)
        val write = cmds[1].bytes
        assertEquals(0x29, write.u8(2))
        assertEquals(0xEC78, write.u16be(4))
    }

    private fun response(cmd: Int, payload: ByteArray): ByteArray {
        val f = ByteArray(payload.size + 7)
        f[0] = 0xDD.toByte()
        f[1] = cmd.toByte()
        f[2] = 0
        f[3] = payload.size.toByte()
        payload.copyInto(f, 4)
        val chk = JbdProtocol.checksum(f, 2, 4 + payload.size)
        f[4 + payload.size] = (chk shr 8).toByte()
        f[5 + payload.size] = chk.toByte()
        f[6 + payload.size] = 0x77
        return f
    }

    // ---------------------------------------------------------------- Daly

    @Test
    fun dalyRequestFrame() {
        assertEquals("A5 80 90 08 00 00 00 00 00 00 00 00 BD", DalyProtocol.frame(0x80, 0x90).toHex())
        assertEquals("A5 40 90 08 00 00 00 00 00 00 00 00 7D", DalyProtocol.frame(0x40, 0x90).toHex())
    }

    @Test
    fun dalyParsesSocStatusCellsAndFaults() {
        val p = DalyProtocol()
        fun resp(cmd: Int, vararg d: Int) = DalyProtocol.frame(0x01, cmd, bytesOf(*d))
        val stream =
            resp(0x90, 0x02, 0x14, 0x00, 0x00, 0x75, 0x62, 0x03, 0x9E) + // 53.2 V, +5.0 A, 92.6 %
                resp(0x94, 0x04, 0x02, 0x00, 0x00, 0x00, 0x00, 0x2A, 0x00) + // 4 cells, 2 temps, 42 cycles
                resp(0x95, 0x01, 0x0C, 0xE4, 0x0C, 0xEE, 0x0C, 0xDF, 0x00) + // cells 1-3
                resp(0x95, 0x02, 0x0C, 0xE9, 0x00, 0x00, 0x00, 0x00, 0x00) + // cell 4
                resp(0x96, 0x01, 0x41, 0x43, 0x00, 0x00, 0x00, 0x00, 0x00) + // 25 °C, 27 °C
                resp(0x93, 0x01, 0x01, 0x00, 0x05, 0x00, 0x01, 0x86, 0xA0) + // chg on, dsg off, 100 Ah
                resp(0x98, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00) // cell high L1
        assertEquals(7, feedChunked(p, stream, 9))
        val s = p.snapshot
        near(53.2, s.voltage, 1e-9)
        near(5.0, s.current, 1e-9)
        assertEquals(93, s.soc)
        assertEquals(42, s.cycles)
        assertEquals(listOf(3.300, 3.310, 3.295, 3.305), s.cellVoltages)
        assertEquals(listOf(25.0, 27.0), s.temperatures)
        assertEquals(true, s.chargeFet)
        assertEquals(false, s.dischargeFet)
        near(100.0, s.remainingAh)
        assertEquals(listOf("Cell voltage high (level 1)"), s.alarms)
    }

    @Test
    fun dalyIgnoresEchoOfOwnRequest() {
        val p = DalyProtocol()
        assertEquals(0, p.feed(DalyProtocol.frame(0x80, 0x90), 0))
    }

    // ---------------------------------------------------------------- JK

    @Test
    fun jkRequestChecksumsMatchReference() {
        assertEquals("AA 55 90 EB 97 00 00 00 00 00 00 00 00 00 00 00 00 00 00 11", JkProtocol.command(0x97).toHex())
        assertEquals("AA 55 90 EB 96 00 00 00 00 00 00 00 00 00 00 00 00 00 00 10", JkProtocol.command(0x96).toHex())
    }

    private fun jkCellFrame(wide: Boolean, cellsMv: List<Int>, currentMa: Int, soc: Int): ByteArray {
        val f = ByteArray(300)
        JkProtocol.HEADER.copyInto(f, 0)
        f[4] = 0x02
        val o = if (wide) 16 else 0
        val o2 = o * 2
        fun put16(i: Int, v: Int) { f[i] = v.toByte(); f[i + 1] = (v shr 8).toByte() }
        fun put32(i: Int, v: Long) { for (k in 0 until 4) f[i + k] = (v shr (8 * k)).toByte() }
        cellsMv.forEachIndexed { i, mv -> put16(6 + i * 2, mv) }
        put32(54 + o, (1L shl cellsMv.size) - 1)
        cellsMv.indices.forEach { put16(64 + o + it * 2, 45) }
        put32(118 + o2, cellsMv.sum().toLong())
        put32(126 + o2, currentMa.toLong())
        put16(130 + o2, 251)
        put16(132 + o2, 263)
        f[141 + o2] = soc.toByte()
        put32(142 + o2, 80_000)
        put32(146 + o2, 100_000)
        put32(150 + o2, 12)
        f[166 + o2] = 1
        f[167 + o2] = 1
        var sum = 0
        for (i in 0 until 299) sum += f.u8(i)
        f[299] = sum.toByte()
        return f
    }

    @Test
    fun jkDecodes24And32CellLayouts() {
        for (wide in listOf(false, true)) {
            val p = JkProtocol()
            val cells = List(8) { 3300 + it }
            val frame = jkCellFrame(wide, cells, -12_500, 81)
            assertEquals(1, feedChunked(p, frame))
            val s = p.snapshot
            assertEquals("layout wide=$wide", 8, s.cellVoltages.size)
            near(cells.sum() / 1000.0, s.voltage)
            near(-12.5, s.current)
            assertEquals(81, s.soc)
            near(80.0, s.remainingAh)
            near(100.0, s.nominalAh)
            assertEquals(12, s.cycles)
            assertEquals(listOf(25.1, 26.3), s.temperatures)
            assertEquals(true, s.dischargeFet)
            near(0.045, s.cellResistances.first())
        }
    }

    @Test
    fun jkRejectsCorruptFrameAndRecovers() {
        val p = JkProtocol()
        val bad = jkCellFrame(false, List(4) { 3300 }, 0, 50).also { it[200] = 0x7F }
        val good = jkCellFrame(false, List(4) { 3310 }, 0, 55)
        assertEquals(1, feedChunked(p, bad + good))
        assertEquals(55, p.snapshot.soc)
    }

    // ---------------------------------------------------------------- Registry & helpers

    @Test
    fun registryPrefersNameHintsAndServices() {
        val jkFirst = ProtocolRegistry.candidates(listOf(uuid16(0xFFE0)), "JK-B2A24S15P", null)
        assertEquals("jk", jkFirst.first().id)
        val daly = ProtocolRegistry.candidates(listOf(uuid16(0xFFF0)), "DL-40D63C123456", null)
        assertEquals("daly", daly.first().id)
        val forced = ProtocolRegistry.candidates(emptyList(), null, "jbd")
        assertEquals(listOf("jbd"), forced.map { it.id })
    }

    @Test
    fun hexParsing() {
        assertEquals("DD A5 03", hexToBytes("dd a5 03")!!.toHex())
        assertEquals("DD A5 03", hexToBytes("0xDD,0xA5,0x03")!!.toHex())
        assertNull(hexToBytes("DD A"))
        assertNull(hexToBytes("ZZ"))
    }

    @Test
    fun demoProducesData() {
        val d = DemoProtocol()
        d.tick(1_000)
        d.tick(61_000)
        assertTrue(d.snapshot.hasData)
        assertEquals(16, d.snapshot.cellVoltages.size)
    }
}
