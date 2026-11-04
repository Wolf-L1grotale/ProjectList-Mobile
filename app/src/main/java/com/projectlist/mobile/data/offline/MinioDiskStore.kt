package com.projectlist.mobile.data.offline

import java.io.Closeable
import java.io.IOException
import java.util.UUID

/**
 * Чтение и запись объектов MinIO прямо на диске (SMB), без S3 API.
 * Поддерживается чтение inline-объектов и обычная запись non-inline (part.1 + bitrot).
 */
class MinioDiskStore(
    private val fs: DiskFs,
    private val bucket: String,
) : Closeable {

    private val bucketPath: String = normalizeDir(bucket)

    @Synchronized
    fun exists(key: String): Boolean = fs.exists(joinPath(bucketPath, key, "xl.meta"))

    @Synchronized
    fun get(key: String): ByteArray? {
        val metaPath = joinPath(bucketPath, key, "xl.meta")
        if (!fs.exists(metaPath)) return null
        val raw = fs.read(metaPath)
        val meta = XlMeta.parse(raw)

        meta.inlineData()?.let { return it }

        val ddir = meta.obj.ddirUuid
            ?: throw IOException("Объект $key: в xl.meta нет DDir")
        val partPath = joinPath(bucketPath, key, ddir, "part.1")
        if (!fs.exists(partPath)) {
            throw IOException("Объект $key: не найден файл данных $partPath")
        }
        val part = fs.read(partPath)
        val data = XlMeta.stripBitrot(part, meta.obj.size)
        if (data.size.toLong() != meta.obj.size) {
            throw IOException("Объект $key: размер данных не совпадает с xl.meta")
        }
        return data
    }

    @Synchronized
    fun put(key: String, data: ByteArray, contentType: String = "application/octet-stream") {
        val objectPath = joinPath(bucketPath, key)
        val oldDirs = listObjectDirs(objectPath)

        val ddir = ByteArray(16).also { putUuid(UUID.randomUUID(), it) }
        val ddirName = XlMeta.formatDdir(ddir)
        val meta = XlMeta.build(
            data = data,
            contentType = contentType,
            ddir = ddir,
            mtimeNs = System.currentTimeMillis() * 1_000_000L,
        )
        val part = XlMeta.withBitrot(data)

        // Сначала данные, затем xl.meta: до записи метаданных старый объект остаётся целым.
        val partPath = joinPath(objectPath, ddirName, "part.1")
        fs.write(partPath, part)
        fs.write(joinPath(objectPath, "xl.meta"), meta)

        oldDirs.filter { it != ddirName }.forEach { old ->
            runCatching { fs.deleteTree(joinPath(objectPath, old)) }
        }
    }

    @Synchronized
    fun delete(key: String) {
        fs.deleteTree(joinPath(bucketPath, key))
    }

    @Synchronized
    fun listKeys(prefix: String = ""): List<String> {
        val prefixNorm = normalizeDir(prefix)
        val start = joinPath(bucketPath, prefixNorm)
        if (!fs.exists(start)) return emptyList()
        val result = ArrayList<String>()
        walk(start, prefixNorm, result)
        return result.sorted()
    }

    private fun walk(dir: String, relative: String, out: MutableList<String>) {
        val entries = runCatching { fs.list(dir) }.getOrElse { return }
        val hasMeta = entries.any { it.name == "xl.meta" && !it.directory }
        if (hasMeta) {
            if (relative.isNotEmpty()) out += relative
            return
        }
        for (entry in entries) {
            if (!entry.directory) continue
            if (entry.name == ".minio.sys" || entry.name == ".minio.sys.tmp") continue
            val childRelative = joinPath(relative, entry.name)
            walk(joinPath(dir, entry.name), childRelative, out)
        }
    }

    private fun listObjectDirs(objectPath: String): List<String> {
        if (!fs.exists(objectPath)) return emptyList()
        return runCatching {
            fs.list(objectPath)
                .filter { it.directory && it.name.length == 36 && it.name.count { ch -> ch == '-' } == 4 }
                .map { it.name }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    override fun close() {
        fs.close()
    }

    private fun putUuid(uuid: UUID, target: ByteArray) {
        for (i in 0 until 8) {
            target[i] = ((uuid.mostSignificantBits ushr (56 - i * 8)) and 0xff).toByte()
            target[8 + i] = ((uuid.leastSignificantBits ushr (56 - i * 8)) and 0xff).toByte()
        }
    }
}
