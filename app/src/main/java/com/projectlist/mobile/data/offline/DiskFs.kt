package com.projectlist.mobile.data.offline

import java.io.Closeable

/** Запись каталога: имя, признак папки и размер. */
data class DiskEntry(
    val name: String,
    val directory: Boolean,
    val size: Long,
)

/**
 * Абстракция над диском с данными MinIO. Пути задаются относительно корня хранилища
 * (`<bucket>/<key>/...`) и всегда используют «/».
 */
interface DiskFs : Closeable {
    fun exists(path: String): Boolean
    fun isDirectory(path: String): Boolean
    fun list(path: String): List<DiskEntry>
    fun read(path: String): ByteArray
    fun write(path: String, data: ByteArray)
    fun mkdirs(path: String)
    fun delete(path: String)
    fun deleteTree(path: String)
}

internal fun normalizeDir(path: String): String =
    path.replace('\\', '/').trim('/').replace(Regex("/{2,}"), "/")

internal fun joinPath(vararg parts: String): String =
    parts.map { it.replace('\\', '/').trim('/') }
        .filter { it.isNotEmpty() }
        .joinToString("/")

internal fun parentPath(path: String): String {
    val normalized = normalizeDir(path)
    val idx = normalized.lastIndexOf('/')
    return if (idx <= 0) "" else normalized.substring(0, idx)
}

internal fun pathName(path: String): String {
    val normalized = normalizeDir(path)
    val idx = normalized.lastIndexOf('/')
    return if (idx < 0) normalized else normalized.substring(idx + 1)
}
