@file:OptIn(kotlin.ExperimentalUnsignedTypes::class)

package com.projectlist.mobile.data.offline

import java.security.MessageDigest

// ---------------------------------------------------------------------------
// Hex / digest helpers
// ---------------------------------------------------------------------------

private const val HEX = "0123456789abcdef"

internal fun ByteArray.toHex(): String {
    val out = CharArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt() and 0xff
        out[i * 2] = HEX[v ushr 4]
        out[i * 2 + 1] = HEX[v and 0x0f]
    }
    return String(out)
}

internal fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "Нечётная длина hex-строки" }
    val out = ByteArray(length / 2)
    for (i in out.indices) {
        out[i] = ((this[i * 2].digitToInt(16) shl 4) or this[i * 2 + 1].digitToInt(16)).toByte()
    }
    return out
}

internal fun sha256Hex(data: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(data).toHex()

internal fun md5Hex(data: ByteArray): String =
    MessageDigest.getInstance("MD5").digest(data).toHex()

// ---------------------------------------------------------------------------
// xxHash64 (seed 0) — the checksum MinIO writes after the xl.meta payload.
// ---------------------------------------------------------------------------

internal object XxHash64 {
    private val P1 = 11400714785074694791uL
    private val P2 = 14029467366897019727uL
    private val P3 = 1609587929392839161uL
    private val P4 = 9650029242287828579uL
    private val P5 = 2870177450012600261uL

    private fun rotl(x: ULong, r: Int): ULong = (x shl r) or (x shr (64 - r))

    private fun round(acc: ULong, input: ULong): ULong = rotl(acc + input * P2, 31) * P1

    private fun merge(acc: ULong, value: ULong): ULong = (acc xor round(0uL, value)) * P1 + P4

    fun hash(data: ByteArray, offset: Int = 0, length: Int = data.size): ULong {
        var p = offset
        val end = offset + length
        var h: ULong

        if (length >= 32) {
            var v1 = P1 + P2
            var v2 = P2
            var v3 = 0uL
            var v4 = 0uL - P1
            val limit = end - 32
            while (p <= limit) {
                v1 = round(v1, readU64LE(data, p))
                v2 = round(v2, readU64LE(data, p + 8))
                v3 = round(v3, readU64LE(data, p + 16))
                v4 = round(v4, readU64LE(data, p + 24))
                p += 32
            }
            h = rotl(v1, 1) + rotl(v2, 7) + rotl(v3, 12) + rotl(v4, 18)
            h = merge(h, v1)
            h = merge(h, v2)
            h = merge(h, v3)
            h = merge(h, v4)
        } else {
            h = P5
        }

        h += length.toULong()
        while (p + 8 <= end) {
            h = h xor round(0uL, readU64LE(data, p))
            h = rotl(h, 27) * P1 + P4
            p += 8
        }
        if (p + 4 <= end) {
            h = h xor (readU32LE(data, p).toULong() * P1)
            h = rotl(h, 23) * P2 + P3
            p += 4
        }
        while (p < end) {
            h = h xor ((data[p].toInt() and 0xff).toULong() * P5)
            h = rotl(h, 11) * P1
            p += 1
        }

        h = h xor (h shr 33)
        h *= P2
        h = h xor (h shr 29)
        h *= P3
        h = h xor (h shr 32)
        return h
    }
}

internal fun readU64LE(b: ByteArray, i: Int): ULong {
    var v = 0uL
    for (k in 7 downTo 0) {
        v = (v shl 8) or (b[i + k].toInt() and 0xff).toULong()
    }
    return v
}

internal fun writeU64LE(b: ByteArray, i: Int, value: ULong) {
    var v = value
    for (k in 0 until 8) {
        b[i + k] = (v and 0xffuL).toByte()
        v = v shr 8
    }
}

internal fun readU32LE(b: ByteArray, i: Int): Long =
    (b[i].toLong() and 0xff) or
        ((b[i + 1].toLong() and 0xff) shl 8) or
        ((b[i + 2].toLong() and 0xff) shl 16) or
        ((b[i + 3].toLong() and 0xff) shl 24)

internal fun writeU32LE(b: ByteArray, i: Int, value: Int) {
    b[i] = (value and 0xff).toByte()
    b[i + 1] = ((value ushr 8) and 0xff).toByte()
    b[i + 2] = ((value ushr 16) and 0xff).toByte()
    b[i + 3] = ((value ushr 24) and 0xff).toByte()
}

internal fun readU32BE(b: ByteArray, i: Int): Long =
    ((b[i].toLong() and 0xff) shl 24) or
        ((b[i + 1].toLong() and 0xff) shl 16) or
        ((b[i + 2].toLong() and 0xff) shl 8) or
        (b[i + 3].toLong() and 0xff)

internal fun readU16LE(b: ByteArray, i: Int): Int =
    (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8)

// ---------------------------------------------------------------------------
// HighwayHash-256 — MinIO bitrot hash (key is hard-coded by MinIO).
// Ported from minio/highwayhash (generic implementation).
// ---------------------------------------------------------------------------

internal class HighwayHash256(private val key: ByteArray = MINIO_KEY) {
    private val state = ULongArray(16)
    private var pending = ByteArray(0)

    init {
        require(key.size == 32) { "HighwayHash key must be 32 bytes" }
        reset()
    }

    private fun reset(): HighwayHash256 {
        val kw = ULongArray(4) { readU64LE(key, it * 8) }
        for (i in 0 until 4) {
            state[8 + i] = INIT0[i]
            state[12 + i] = INIT1[i]
        }
        for (i in 0 until 4) state[i] = INIT0[i] xor kw[i]
        for (i in 0 until 4) state[4 + i] = INIT1[i] xor swap32(kw[i])
        pending = ByteArray(0)
        return this
    }

    fun update(data: ByteArray): HighwayHash256 {
        val combined = if (pending.isEmpty()) data else pending + data
        var offset = 0
        while (combined.size - offset >= 32) {
            update32(combined, offset)
            offset += 32
        }
        pending = combined.copyOfRange(offset, combined.size)
        return this
    }

    private fun update32(data: ByteArray, off: Int) {
        val s = state

        s[4] += readU64LE(data, off) + s[8]
        s[8] = s[8] xor (lo32(s[4]) * (s[0] shr 32))
        s[0] += s[12]
        s[12] = s[12] xor (lo32(s[0]) * (s[4] shr 32))

        s[5] += readU64LE(data, off + 8) + s[9]
        s[9] = s[9] xor (lo32(s[5]) * (s[1] shr 32))
        s[1] += s[13]
        s[13] = s[13] xor (lo32(s[1]) * (s[5] shr 32))

        s[6] += readU64LE(data, off + 16) + s[10]
        s[10] = s[10] xor (lo32(s[6]) * (s[2] shr 32))
        s[2] += s[14]
        s[14] = s[14] xor (lo32(s[2]) * (s[6] shr 32))

        s[7] += readU64LE(data, off + 24) + s[11]
        s[11] = s[11] xor (lo32(s[7]) * (s[3] shr 32))
        s[3] += s[15]
        s[15] = s[15] xor (lo32(s[3]) * (s[7] shr 32))

        var z = zipper(s[4], s[5]); s[0] += z.first; s[1] += z.second
        z = zipper(s[6], s[7]); s[2] += z.first; s[3] += z.second
        z = zipper(s[0], s[1]); s[4] += z.first; s[5] += z.second
        z = zipper(s[2], s[3]); s[6] += z.first; s[7] += z.second
    }

    private fun hashBuffer() {
        val offset = pending.size
        if (offset == 0) return
        val s = state
        val block = ByteArray(32)
        val mod32 = offset.toULong().shl(32) + offset.toULong()
        for (i in 0 until 4) s[i] += mod32

        for (i in 4 until 8) {
            val t0 = Integer.rotateLeft((s[i] and 0xffffffffuL).toUInt().toInt(), offset)
            val t1 = Integer.rotateLeft((s[i] shr 32).toUInt().toInt(), offset)
            s[i] = (t1.toUInt().toULong() shl 32) or t0.toUInt().toULong()
        }

        val mod4 = offset and 3
        val remain = offset - mod4
        pending.copyInto(block, 0, 0, remain)
        if (offset >= 16) {
            pending.copyInto(block, 28, offset - 4, offset)
        } else if (mod4 != 0) {
            var last = pending[remain].toInt() and 0xff
            // В Go приоритет сдвига выше сложения: remain + (mod4 >> 1).
            last += (pending[remain + (mod4 shr 1)].toInt() and 0xff) shl 8
            last += (pending[offset - 1].toInt() and 0xff) shl 16
            writeU32LE(block, 16, last)
        }

        update32(block, 0)
        pending = ByteArray(0)
    }

    fun digest(): ByteArray {
        val s = state
        hashBuffer()

        val block = ByteArray(32)
        repeat(10) {
            writeU64LE(block, 0, swap32(s[2]))
            writeU64LE(block, 8, swap32(s[3]))
            writeU64LE(block, 16, swap32(s[0]))
            writeU64LE(block, 24, swap32(s[1]))
            update32(block, 0)
        }

        val out = ByteArray(32)
        var h = reduce(s[0] + s[8], s[1] + s[9], s[4] + s[12], s[5] + s[13])
        writeU64LE(out, 0, h.first)
        writeU64LE(out, 8, h.second)
        h = reduce(s[2] + s[10], s[3] + s[11], s[6] + s[14], s[7] + s[15])
        writeU64LE(out, 16, h.first)
        writeU64LE(out, 24, h.second)
        return out
    }

    private fun reduce(v0: ULong, v1: ULong, v2: ULong, v3: ULong): Pair<ULong, ULong> {
        val m3 = v3 and 0x3fffffffffffffffuL
        var r0 = v2
        var r1 = m3
        val t3 = (m3 shl 1) or (v2 shr 63)
        val t2 = v2 shl 1
        r1 = (r1 shl 2) or (r0 shr 62)
        r0 = r0 shl 2
        r0 = r0 xor v0 xor t2
        r1 = r1 xor v1 xor t3
        return r0 to r1
    }

    private fun zipper(v0: ULong, v1: ULong): Pair<ULong, ULong> {
        var res = v0 and (0xffuL shl 16)
        var res2 = (v0 and (0xffuL shl 56)) + (v1 and (0xffuL shl 16))
        res += (v1 and (0xffuL shl 56)) shr 8
        res2 += (v0 and (0xffuL shl 48)) shr 8
        res += ((v0 and (0xffuL shl 40)) + (v1 and (0xffuL shl 48))) shr 16
        res2 += (v1 and (0xffuL shl 40)) shr 16
        res += ((v0 and (0xffuL shl 24)) + (v1 and (0xffuL shl 32))) shr 24
        res2 += ((v1 and (0xffuL shl 24)) + (v0 and (0xffuL shl 32))) shr 24
        res += (v0 and (0xffuL shl 8)) shl 32
        res2 += (v1 and 0xffuL) shl 48
        res += v0 shl 56
        res2 += (v1 and (0xffuL shl 8)) shl 24
        return res to res2
    }

    private fun lo32(x: ULong): ULong = x and 0xffffffffuL

    private fun swap32(x: ULong): ULong = (x shr 32) or (x shl 32)

    companion object {
        val MINIO_KEY: ByteArray = "4be734fa8e238acd263e83e6bb968552040f935da39f441497e09d1322de36a0".hexToBytes()

        private val INIT0 = ulongArrayOf(
            0xdbe6d5d5fe4cce2fuL,
            0xa4093822299f31d0uL,
            0x13198a2e03707344uL,
            0x243f6a8885a308d3uL,
        )
        private val INIT1 = ulongArrayOf(
            0x3bd39e10cb0ef593uL,
            0xc0acf169b5f18a8cuL,
            0xbe5466cf34e90c6cuL,
            0x452821e638d01377uL,
        )
    }
}

internal fun highwayHash256(data: ByteArray, key: ByteArray = HighwayHash256.MINIO_KEY): ByteArray =
    HighwayHash256(key).update(data).digest()
