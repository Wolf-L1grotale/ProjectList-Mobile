package com.projectlist.mobile.data.offline

import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Формат xl.meta MinIO v1.3 (один объект, non-inline) и битрот-обёртка part.N.
 *
 * Структура:
 *   "XL2 " + 01 00 03 00 + 0xc6 + BE32(len(meta)) + meta + 0xce + BE32(xxhash64(meta))
 *   meta = uint(3) uint(3) int(versions) { bin(header) bin(v2obj) }...
 *
 * Данные объекта лежат в <bucket>/<key>/<DDir UUID>/part.1 блоками по 1 МиБ:
 *   [HighwayHash-256(block) 32 байта][block]
 */
object XlMeta {
    const val VERSION_MAJOR = 1
    const val VERSION_MINOR = 3
    const val SHARD_SIZE = 1024 * 1024
    const val INLINE_KEY = "x-minio-internal-inline-data"

    private val MAGIC = byteArrayOf('X'.code.toByte(), 'L'.code.toByte(), '2'.code.toByte(), ' '.code.toByte())

    // ------------------------------------------------------------------
    // Сборка
    // ------------------------------------------------------------------

    fun build(
        data: ByteArray,
        contentType: String = "application/octet-stream",
        ddir: ByteArray = UUID.randomUUID().toBytes(),
        mtimeNs: Long = System.currentTimeMillis() * 1_000_000L,
    ): ByteArray {
        require(ddir.size == 16) { "DDir должен быть 16 байт, получено ${ddir.size}" }

        val size = data.size.toLong()
        val etag = md5Hex(data)

        val v2 = MsgWriter()
            .map(17)
            .str("ID").bin(ByteArray(16))
            .str("DDir").bin(ddir)
            .str("EcAlgo").int(1)
            .str("EcM").int(1)
            .str("EcN").int(0)
            .str("EcBSize").int(SHARD_SIZE.toLong())
            .str("EcIndex").int(1)
            .str("EcDist").array(1).int(1)
            .str("CSumAlgo").int(1)
            .str("PartNums").array(1).int(1)
            .str("PartETags").nil()
            .str("PartSizes").array(1).int(size)
            .str("PartASizes").array(1).int(size)
            .str("Size").int(size)
            .str("MTime").int(mtimeNs)
            .str("MetaSys").map(0)
            .str("MetaUsr")
            .map(2)
            .str("content-type").str(contentType)
            .str("etag").str(etag)
            .toByteArray()

        val meta = MsgWriter()
            .map(3)
            .str("Type").int(1)
            .str("V2Obj").raw(v2)
            .str("v").int(0)
            .toByteArray()

        val header = MsgWriter()
            .array(7)
            .bin(ByteArray(16))          // VersionID
            .int(mtimeNs)                // ModTime
            .bin(ByteArray(4))           // Signature
            .int(1)                      // Type = Object
            .int(2)                      // Flags = UsesDataDir
            .int(0)                      // EcN
            .int(1)                      // EcM
            .toByteArray()

        val payload = MsgWriter()
            .uint(3)                     // версия формата xl.meta
            .uint(VERSION_MINOR.toLong()) // версия структуры
            .int(1)
            .bin(header)
            .bin(meta)
            .toByteArray()

        val out = ByteArrayOutputStream(payload.size + 18)
        out.write(MAGIC)
        out.write(VERSION_MAJOR and 0xff)
        out.write(VERSION_MAJOR ushr 8)
        out.write(VERSION_MINOR and 0xff)
        out.write(VERSION_MINOR ushr 8)
        out.write(0xc6)
        writeU32BE(out, payload.size)
        out.write(payload)
        out.write(0xce)
        val checksum = XxHash64.hash(payload)
        writeU32BE(out, (checksum and 0xffff_ffffuL).toInt())
        return out.toByteArray()
    }

    // ------------------------------------------------------------------
    // Разбор
    // ------------------------------------------------------------------

    fun parse(raw: ByteArray): XlMetaFile {
        require(raw.size > 18) { "xl.meta слишком маленький: ${raw.size} байт" }
        require(raw[0] == MAGIC[0] && raw[1] == MAGIC[1] && raw[2] == MAGIC[2] && raw[3] == MAGIC[3]) {
            "xl.meta: неверная сигнатура"
        }
        val major = readU16LE(raw, 4)
        val minor = readU16LE(raw, 6)
        require(major == VERSION_MAJOR && minor == VERSION_MINOR) {
            "xl.meta: неподдерживаемая версия $major.$minor"
        }
        val binTag = raw[8].toInt() and 0xff
        require(binTag == 0xc6) { "xl.meta: ожидался bin32, получен тег 0x${binTag.toString(16)}" }

        val metaLen = readU32BE(raw, 9).toInt()
        val metaStart = 13
        val metaEnd = metaStart + metaLen
        require(metaEnd <= raw.size) {
            "xl.meta: длина метаданных $metaLen выходит за пределы файла ${raw.size}"
        }

        val payload = raw.copyOfRange(metaStart, metaEnd)
        val reader = MsgReader(payload)
        val headerV = reader.read()
        val metaV = reader.read()
        val count = reader.read().asLong()
        require(headerV.asLongOrNull() != null) {
            "xl.meta: неверный заголовок"
        }
        require(count in 0..256) { "xl.meta: подозрительное число версий $count" }

        val versions = ArrayList<XlMetaVersion>(count.toInt())
        repeat(count.toInt()) {
            val headerNode = reader.read()
            val metaNode = reader.read()
            val headerBytes = headerNode.asBytesOrNull()
                ?: throw IllegalArgumentException("xl.meta: заголовок версии не является bin")
            val metaBytes = metaNode.asBytesOrNull()
                ?: throw IllegalArgumentException("xl.meta: метаданные версии не являются bin")
            versions += XlMetaVersion(parseHeader(headerBytes), parseV2(metaBytes))        }

        // Контрольная сумма идёт сразу за payload. В разных сборках MinIO тег тот же (uint32),
        // но читаем через MessagePack, чтобы не зависеть от точного тега.
        var afterCrc = metaEnd
        if (metaEnd < raw.size) {
            val crcReader = MsgReader(raw, metaEnd)
            runCatching { crcReader.read() }
                .onSuccess { afterCrc = crcReader.pos }
                .onFailure { afterCrc = metaEnd + 5 }
        }
        require(afterCrc <= raw.size) { "xl.meta: некорректная контрольная сумма" }

        return XlMetaFile(versions, raw.copyOfRange(afterCrc, raw.size))
    }

    private fun parseHeader(bytes: ByteArray): VersionHeader {
        val value = MsgReader(bytes).read()
        val items = value.asListOrNull()
            ?: return VersionHeader()
        return VersionHeader(
            versionId = items.getOrNull(0)?.asBytesOrNull() ?: ByteArray(16),
            modTime = items.getOrNull(1)?.asLongOrNull() ?: 0L,
            signature = items.getOrNull(2)?.asBytesOrNull() ?: ByteArray(0),
            type = items.getOrNull(3)?.asLongOrNull() ?: 1L,
            flags = items.getOrNull(4)?.asLongOrNull() ?: 2L,
            ecN = items.getOrNull(5)?.asLongOrNull() ?: 0L,
            ecM = items.getOrNull(6)?.asLongOrNull() ?: 1L,
        )
    }

    private fun parseV2(bytes: ByteArray): V2Obj {
        val metaMap = MsgReader(bytes).readMapValue()
        val v2Value = metaMap["V2Obj"] ?: MsgValue.MapV(metaMap)
        val map = v2Value.asMapOrNull()
            ?: MsgReader(
                v2Value.asBytesOrNull()
                    ?: throw IllegalArgumentException("xl.meta: V2Obj не является map")
            ).readMapValue()

        fun long(key: String, def: Long = 0L): Long = map[key]?.asLongOrNull() ?: def
        fun longs(key: String): List<Long> =
            map[key]?.asListOrNull()?.mapNotNull { it.asLongOrNull() } ?: emptyList()

        fun msgMap(key: String): Map<String, MsgValue> {
            val value = map[key] ?: return emptyMap()
            return value.asMapOrNull() ?: runCatching {
                MsgReader(value.asBytesOrNull() ?: return emptyMap()).readMapValue()
            }.getOrDefault(emptyMap())
        }

        return V2Obj(
            versionId = map["ID"]?.asBytesOrNull() ?: ByteArray(16),
            ddir = map["DDir"]?.asBytesOrNull() ?: ByteArray(0),
            ecAlgo = long("EcAlgo", 1),
            ecM = long("EcM", 1),
            ecN = long("EcN", 0),
            ecBSize = long("EcBSize", SHARD_SIZE.toLong()),
            ecIndex = long("EcIndex", 1),
            ecDist = longs("EcDist"),
            cSumAlgo = long("CSumAlgo", 1),
            partNums = longs("PartNums"),
            partSizes = longs("PartSizes"),
            partASizes = longs("PartASizes"),
            size = long("Size"),
            mtime = long("MTime"),
            metaSys = msgMap("MetaSys"),
            metaUsr = msgMap("MetaUsr"),
        )
    }

    // ------------------------------------------------------------------
    // Bitrot
    // ------------------------------------------------------------------

    /** Оборачивает данные так, как это делает MinIO в part.1. */
    fun withBitrot(data: ByteArray): ByteArray {
        if (data.isEmpty()) return ByteArray(0)
        val out = ByteArrayOutputStream(data.size + (data.size / SHARD_SIZE + 1) * 32)
        var offset = 0
        while (offset < data.size) {
            val length = minOf(SHARD_SIZE, data.size - offset)
            val block = data.copyOfRange(offset, offset + length)
            out.write(highwayHash256(block))
            out.write(block)
            offset += length
        }
        return out.toByteArray()
    }

    /** Снимает битрот-обёртку, проверяя хеши блоков. */
    fun stripBitrot(part: ByteArray, expectedSize: Long): ByteArray {
        if (expectedSize <= 0) return ByteArray(0)
        require(expectedSize <= Int.MAX_VALUE) { "Объект слишком большой для чтения в память" }
        val out = ByteArrayOutputStream(expectedSize.toInt())
        var offset = 0
        var remaining = expectedSize
        while (remaining > 0) {
            require(offset + 32 <= part.size) { "part.1: нет хеша блока по смещению $offset" }
            val hash = part.copyOfRange(offset, offset + 32)
            offset += 32
            val length = minOf(SHARD_SIZE.toLong(), remaining).toInt()
            require(offset + length <= part.size) { "part.1: блок оборван по смещению $offset" }
            val block = part.copyOfRange(offset, offset + length)
            offset += length
            val actual = highwayHash256(block)
            require(actual.contentEquals(hash)) { "part.1: неверная контрольная сумма блока" }
            out.write(block)
            remaining -= length
        }
        return out.toByteArray()
    }

    // ------------------------------------------------------------------
    // Утилиты
    // ------------------------------------------------------------------

    fun formatDdir(ddir: ByteArray): String {
        val hex = ddir.toHex()
        if (hex.length != 32) return hex
        return buildString(36) {
            append(hex, 0, 8); append('-')
            append(hex, 8, 12); append('-')
            append(hex, 12, 16); append('-')
            append(hex, 16, 20); append('-')
            append(hex, 20, 32)
        }
    }

    private fun UUID.toBytes(): ByteArray {
        val buffer = java.nio.ByteBuffer.allocate(16)
        buffer.putLong(mostSignificantBits)
        buffer.putLong(leastSignificantBits)
        return buffer.array()
    }

    private fun writeU32BE(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 24) and 0xff)
        out.write((value ushr 16) and 0xff)
        out.write((value ushr 8) and 0xff)
        out.write(value and 0xff)
    }
}

data class XlMetaVersion(
    val header: VersionHeader,
    val obj: V2Obj,
)

data class VersionHeader(
    val versionId: ByteArray = ByteArray(16),
    val modTime: Long = 0L,
    val signature: ByteArray = ByteArray(0),
    val type: Long = 1L,
    val flags: Long = 2L,
    val ecN: Long = 0L,
    val ecM: Long = 1L,
)

data class V2Obj(
    val versionId: ByteArray = ByteArray(16),
    val ddir: ByteArray = ByteArray(0),
    val ecAlgo: Long = 1L,
    val ecM: Long = 1L,
    val ecN: Long = 0L,
    val ecBSize: Long = XlMeta.SHARD_SIZE.toLong(),
    val ecIndex: Long = 1L,
    val ecDist: List<Long> = emptyList(),
    val cSumAlgo: Long = 1L,
    val partNums: List<Long> = emptyList(),
    val partSizes: List<Long> = emptyList(),
    val partASizes: List<Long> = emptyList(),
    val size: Long = 0L,
    val mtime: Long = 0L,
    val metaSys: Map<String, MsgValue> = emptyMap(),
    val metaUsr: Map<String, MsgValue> = emptyMap(),
) {
    val inline: Boolean get() = metaSys.containsKey(XlMeta.INLINE_KEY)
    val etag: String? get() = metaUsr["etag"]?.asStringOrNull()
    val contentType: String? get() = metaUsr["content-type"]?.asStringOrNull()
    val ddirUuid: String? get() = ddir.takeIf { it.isNotEmpty() }?.let(XlMeta::formatDdir)
}

class XlMetaFile(
    val versions: List<XlMetaVersion>,
    val inlineBytes: ByteArray,
) {
    val primary: XlMetaVersion
        get() = versions.firstOrNull() ?: throw IllegalStateException("xl.meta не содержит версий")

    val obj: V2Obj get() = primary.obj

    /** Для inline-объектов MinIO хранит данные прямо в xl.meta после контрольной суммы. */
    fun inlineData(): ByteArray? {
        if (!obj.inline) return null
        if (inlineBytes.isEmpty()) return null
        val map = MsgReader(inlineBytes, 1).readMapValue()
        val packed = map.values.firstOrNull()?.asBytesOrNull() ?: return null
        val size = obj.size.toInt()
        if (size <= 0) return ByteArray(0)
        require(packed.size >= size) { "xl.meta: inline-данные короче заявленного размера" }
        return packed.copyOfRange(packed.size - size, packed.size)
    }
}
