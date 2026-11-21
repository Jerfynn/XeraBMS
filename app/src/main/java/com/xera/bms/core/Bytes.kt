package com.xera.bms.core

/*
 * Byte helpers shared by every protocol. Pure Kotlin (no Android imports) so the
 * protocol layer can be unit-tested on the JVM.
 */

fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF

fun ByteArray.u16be(i: Int): Int = (u8(i) shl 8) or u8(i + 1)
fun ByteArray.s16be(i: Int): Int = u16be(i).toShort().toInt()
fun ByteArray.u16le(i: Int): Int = (u8(i + 1) shl 8) or u8(i)
fun ByteArray.s16le(i: Int): Int = u16le(i).toShort().toInt()

fun ByteArray.u32be(i: Int): Long =
    (u8(i).toLong() shl 24) or (u8(i + 1).toLong() shl 16) or (u8(i + 2).toLong() shl 8) or u8(i + 3).toLong()

fun ByteArray.u32le(i: Int): Long =
    u8(i).toLong() or (u8(i + 1).toLong() shl 8) or (u8(i + 2).toLong() shl 16) or (u8(i + 3).toLong() shl 24)

fun ByteArray.s32le(i: Int): Int = u32le(i).toInt()

/** Printable ASCII between [from] and [from]+[len], with NULs and junk removed. */
fun ByteArray.ascii(from: Int, len: Int): String {
    if (from >= size) return ""
    val end = minOf(size, from + len)
    val sb = StringBuilder()
    for (i in from until end) {
        val c = u8(i)
        if (c == 0) break
        if (c in 0x20..0x7E) sb.append(c.toChar())
    }
    return sb.toString().trim()
}

private val HEX = "0123456789ABCDEF".toCharArray()

fun ByteArray.toHex(separator: String = " "): String {
    val sb = StringBuilder(size * (2 + separator.length))
    forEachIndexed { index, b ->
        if (index > 0) sb.append(separator)
        val v = b.toInt() and 0xFF
        sb.append(HEX[v shr 4]).append(HEX[v and 0x0F])
    }
    return sb.toString()
}

/** Parses "DD A5 03 00 FF FD 77", "dda50300fffd77" or "0xDD,0xA5,...". Returns null if invalid. */
fun hexToBytes(text: String): ByteArray? {
    val clean = text.replace("0x", "", ignoreCase = true).filter { !it.isWhitespace() && it != ',' && it != ':' && it != '-' }
    if (clean.isEmpty() || clean.length % 2 != 0) return null
    if (clean.any { Character.digit(it, 16) < 0 }) return null
    return ByteArray(clean.length / 2) { i ->
        ((Character.digit(clean[i * 2], 16) shl 4) or Character.digit(clean[i * 2 + 1], 16)).toByte()
    }
}

fun bytesOf(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

/** Expands a 16-bit Bluetooth SIG short UUID to its full 128-bit lowercase form. */
fun uuid16(short: Int): String {
    val h = Integer.toHexString(short).padStart(4, '0')
    return "0000$h-0000-1000-8000-00805f9b34fb"
}

/** Growable receive buffer for reassembling BLE notifications into protocol frames. */
class FrameBuffer(private val maxSize: Int = 4096) {
    private var data = ByteArray(0)

    val size: Int get() = data.size

    fun append(bytes: ByteArray) {
        data += bytes
        if (data.size > maxSize) data = data.copyOfRange(data.size - maxSize, data.size)
    }

    fun peek(): ByteArray = data

    fun drop(count: Int) {
        data = if (count >= data.size) ByteArray(0) else data.copyOfRange(count, data.size)
    }

    fun clear() {
        data = ByteArray(0)
    }

    /** Index of the first occurrence of [pattern], or -1. */
    fun indexOf(pattern: ByteArray, from: Int = 0): Int {
        if (pattern.isEmpty()) return -1
        var i = from
        while (i <= data.size - pattern.size) {
            var match = true
            for (j in pattern.indices) {
                if (data[i + j] != pattern[j]) {
                    match = false
                    break
                }
            }
            if (match) return i
            i++
        }
        return -1
    }
}
