package com.projectlist.mobile.data.offline

import java.io.ByteArrayOutputStream

/**
 * Минимальный MessagePack-кодек: ровно те типы, которые встречаются в xl.meta MinIO.
 * Значения неизвестных типов приводят к ошибке — молча портить файл опаснее.
 */
sealed class MsgValue {
    data object Nil : MsgValue()
    data class BoolV(val value: Boolean) : MsgValue()
    data class IntV(val value: Long) : MsgValue()

    /** Беззнаковые значения длиннее Int.MAX_VALUE храним как неотрицательный Long. */
    data class UIntV(val value: Long) : MsgValue()
    data class StrV(val value: String) : MsgValue()
    data class BinV(val value: ByteArray) : MsgValue()
    data class ArrV(val value: List<MsgValue>) : MsgValue()
    data class MapV(val value: Map<String, MsgValue>) : MsgValue() {
        operator fun get(key: String): MsgValue? = value[key]
        fun contains(key: String): Boolean = value.containsKey(key)
    }

    fun asLong(): Long = when (this) {
        is IntV -> value
        is UIntV -> value
        is StrV -> value.toLongOrNull() ?: fail("строку '$value' нельзя прочитать как число")
        else -> fail("ожидалось число, получено $this")
    }

    fun asLongOrNull(): Long? = when (this) {
        is IntV -> value
        is UIntV -> value
        else -> null
    }

    fun asBytesOrNull(): ByteArray? = when (this) {
        is BinV -> value
        is StrV -> value.toByteArray(Charsets.UTF_8)
        else -> null
    }

    fun asStringOrNull(): String? = when (this) {
        is StrV -> value
        is BinV -> value.toString(Charsets.UTF_8)
        else -> null
    }

    fun asListOrNull(): List<MsgValue>? = (this as? ArrV)?.value

    fun asMapOrNull(): Map<String, MsgValue>? = (this as? MapV)?.value

    override fun toString(): String = when (this) {
        Nil -> "nil"
        is BoolV -> value.toString()
        is IntV -> value.toString()
        is UIntV -> value.toString()
        is StrV -> "'$value'"
        is BinV -> "bin(${value.size})"
        is ArrV -> "array(${value.size})"
        is MapV -> "map(${value.size})"
    }

    private fun fail(message: String): Nothing =
        throw IllegalArgumentException("MessagePack: $message")
}

/** Пишет MessagePack в буфер. Методы возвращают this для цепочек. */
class MsgWriter {
    private val out = ByteArrayOutputStream(256)

    fun raw(bytes: ByteArray): MsgWriter {
        out.write(bytes)
        return this
    }

    fun nil(): MsgWriter {
        out.write(0xc0)
        return this
    }

    fun bool(value: Boolean): MsgWriter {
        out.write(if (value) 0xc3 else 0xc2)
        return this
    }

    /** Кодирует число в минимальном знаковом представлении (как это делает MinIO). */
    fun int(value: Long): MsgWriter {
        when {
            value in 0..0x7fL -> out.write(value.toInt())
            value in -32L..-1L -> out.write(0x100 + value.toInt())
            value in -128L..0x7fL -> {
                out.write(0xd0)
                out.write(value.toInt() and 0xff)
            }
            value in Short.MIN_VALUE.toLong()..Short.MAX_VALUE.toLong() -> {
                out.write(0xd1)
                writeShortBE(out, value.toInt())
            }
            value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() -> {
                out.write(0xd2)
                writeIntBE(out, value.toInt())
            }
            else -> {
                out.write(0xd3)
                writeLongBE(out, value)
            }
        }
        return this
    }

    fun uint(value: Long): MsgWriter {
        require(value >= 0) { "uint не может быть отрицательным: $value" }
        when {
            value < 0x80L -> out.write(value.toInt())
            value < 0x100L -> {
                out.write(0xcc)
                out.write(value.toInt())
            }
            value < 0x10000L -> {
                out.write(0xcd)
                writeShortBE(out, value.toInt())
            }
            value < 0x1_0000_0000L -> {
                out.write(0xce)
                writeIntBE(out, value.toInt())
            }
            else -> {
                out.write(0xcf)
                writeLongBE(out, value)
            }
        }
        return this
    }

    fun str(value: String): MsgWriter {
        val bytes = value.toByteArray(Charsets.UTF_8)
        when {
            bytes.size < 32 -> out.write(0xa0 or bytes.size)
            bytes.size < 0x100 -> {
                out.write(0xd9)
                out.write(bytes.size)
            }
            bytes.size < 0x10000 -> {
                out.write(0xda)
                writeShortBE(out, bytes.size)
            }
            else -> {
                out.write(0xdb)
                writeIntBE(out, bytes.size)
            }
        }
        out.write(bytes)
        return this
    }

    fun bin(value: ByteArray): MsgWriter {
        when {
            value.size < 0x100 -> {
                out.write(0xc4)
                out.write(value.size)
            }
            value.size < 0x10000 -> {
                out.write(0xc5)
                writeShortBE(out, value.size)
            }
            else -> {
                out.write(0xc6)
                writeIntBE(out, value.size)
            }
        }
        out.write(value)
        return this
    }

    fun array(size: Int): MsgWriter {
        when {
            size < 16 -> out.write(0x90 or size)
            size < 0x10000 -> {
                out.write(0xdc)
                writeShortBE(out, size)
            }
            else -> {
                out.write(0xdd)
                writeIntBE(out, size)
            }
        }
        return this
    }

    fun map(size: Int): MsgWriter {
        when {
            size < 16 -> out.write(0x80 or size)
            size < 0x10000 -> {
                out.write(0xde)
                writeShortBE(out, size)
            }
            else -> {
                out.write(0xdf)
                writeIntBE(out, size)
            }
        }
        return this
    }

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun writeShortBE(target: ByteArrayOutputStream, value: Int) {
        target.write((value ushr 8) and 0xff)
        target.write(value and 0xff)
    }

    private fun writeIntBE(target: ByteArrayOutputStream, value: Int) {
        target.write((value ushr 24) and 0xff)
        target.write((value ushr 16) and 0xff)
        target.write((value ushr 8) and 0xff)
        target.write(value and 0xff)
    }

    private fun writeLongBE(target: ByteArrayOutputStream, value: Long) {
        for (shift in 56 downTo 0 step 8) {
            target.write(((value ushr shift) and 0xff).toInt())
        }
    }
}

class MsgReader(private val buf: ByteArray, var pos: Int = 0) {

    val remaining: Int get() = buf.size - pos

    fun read(): MsgValue {
        val tag = readU8()
        return when {
            tag < 0x80 -> MsgValue.UIntV(tag.toLong())
            tag < 0x90 -> readMap(tag and 0x0f)
            tag < 0xa0 -> readArray(tag and 0x0f)
            tag < 0xc0 -> readStr(tag and 0x1f)
            tag >= 0xe0 -> MsgValue.IntV((tag - 0x100).toLong())
            else -> when (tag) {
                0xc0 -> MsgValue.Nil
                0xc2 -> MsgValue.BoolV(false)
                0xc3 -> MsgValue.BoolV(true)
                0xc4 -> MsgValue.BinV(readBytes(readU8()))
                0xc5 -> MsgValue.BinV(readBytes(readU16BE()))
                0xc6 -> MsgValue.BinV(readBytes(readI32BE()))
                0xcc -> MsgValue.UIntV(readU8().toLong())
                0xcd -> MsgValue.UIntV(readU16BE().toLong())
                0xce -> MsgValue.UIntV(readU32BE())
                0xcf -> MsgValue.UIntV(readI64BEUnsigned())
                0xd0 -> MsgValue.IntV(readI8().toLong())
                0xd1 -> MsgValue.IntV(readI16BE().toLong())
                0xd2 -> MsgValue.IntV(readI32BE().toLong())
                0xd3 -> MsgValue.IntV(readI64BE())
                0xd9 -> MsgValue.StrV(readBytes(readU8()).toString(Charsets.UTF_8))
                0xda -> MsgValue.StrV(readBytes(readU16BE()).toString(Charsets.UTF_8))
                0xdb -> MsgValue.StrV(readBytes(readI32BE()).toString(Charsets.UTF_8))
                0xdc -> readArray(readU16BE())
                0xdd -> readArray(readI32BE())
                0xde -> readMap(readU16BE())
                0xdf -> readMap(readI32BE())
                else -> throw IllegalArgumentException(
                    "MessagePack: неподдерживаемый тег 0x${tag.toString(16)} в позиции ${pos - 1}"
                )
            }
        }
    }

    fun readMapValue(): Map<String, MsgValue> =
        (read() as? MsgValue.MapV)?.value
            ?: throw IllegalArgumentException("MessagePack: ожидался map")

    private fun readMap(size: Int): MsgValue.MapV {
        val map = LinkedHashMap<String, MsgValue>(size)
        repeat(size) {
            val key = keyToString(read())
            map[key] = read()
        }
        return MsgValue.MapV(map)
    }

    private fun readArray(size: Int): MsgValue.ArrV =
        MsgValue.ArrV(List(size) { read() })

    private fun readStr(size: Int): MsgValue.StrV =
        MsgValue.StrV(readBytes(size).toString(Charsets.UTF_8))

    private fun keyToString(value: MsgValue): String = when (value) {
        is MsgValue.StrV -> value.value
        is MsgValue.BinV -> value.value.toString(Charsets.UTF_8)
        is MsgValue.IntV -> value.value.toString()
        is MsgValue.UIntV -> value.value.toString()
        MsgValue.Nil -> "null"
        else -> value.toString()
    }

    private fun readBytes(size: Int): ByteArray {
        require(size >= 0) { "MessagePack: отрицательная длина $size" }
        require(pos + size <= buf.size) {
            "MessagePack: не хватает данных (нужно $size, осталось ${buf.size - pos})"
        }
        val out = buf.copyOfRange(pos, pos + size)
        pos += size
        return out
    }

    private fun readU8(): Int {
        require(pos < buf.size) { "MessagePack: неожиданный конец данных" }
        return buf[pos++].toInt() and 0xff
    }

    private fun readI8(): Byte = readU8().toByte()

    private fun readU16BE(): Int = (readU8() shl 8) or readU8()

    private fun readI16BE(): Short = readU16BE().toShort()

    private fun readU32BE(): Long {
        var v = 0L
        repeat(4) { v = (v shl 8) or readU8().toLong() }
        return v
    }

    private fun readI32BE(): Int = readU32BE().toInt()

    private fun readI64BE(): Long {
        var v = 0L
        repeat(8) { v = (v shl 8) or readU8().toLong() }
        return v
    }

    private fun readI64BEUnsigned(): Long = readI64BE()
}

internal fun msgpackMap(vararg pairs: Pair<String, MsgValue>): MsgValue.MapV =
    MsgValue.MapV(linkedMapOf(*pairs))
