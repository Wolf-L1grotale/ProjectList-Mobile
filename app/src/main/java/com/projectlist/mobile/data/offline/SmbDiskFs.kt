package com.projectlist.mobile.data.offline

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.io.IOException
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * Доступ к данным MinIO через SMB. Все операции блокирующие — вызывать из Dispatchers.IO.
 */
class SmbDiskFs(private val config: OfflineConfig) : DiskFs {

    private val root = normalizeDir(config.basePath)

    private var client: SMBClient? = null
    private var connection: Connection? = null
    private var session: Session? = null
    private var share: DiskShare? = null

    @Synchronized
    fun ensureConnected(): DiskShare {
        share?.let { if (connection?.isConnected == true) return it }

        closeQuietly()
        val smbConfig = SmbConfig.builder()
            .withReadTimeout(45, TimeUnit.SECONDS)
            .withWriteTimeout(45, TimeUnit.SECONDS)
            .withTransactTimeout(45, TimeUnit.SECONDS)
            .withSoTimeout(45, TimeUnit.SECONDS)
            .build()

        val newClient = SMBClient(smbConfig)
        try {
            val newConnection = newClient.connect(config.host, config.port)
            val domain = config.domain.trim().ifBlank { null }
            val auth = AuthenticationContext(config.user, config.password.toCharArray(), domain)
            val newSession = newConnection.authenticate(auth)
            val newShare = newSession.connectShare(config.share)
            if (newShare !is DiskShare) {
                throw IOException("Ресурс «${config.share}» не является общей папкой")
            }
            client = newClient
            connection = newConnection
            session = newSession
            share = newShare
            return newShare
        } catch (t: Throwable) {
            runCatching { newClient.close() }
            throw IOException(
                "Не удалось подключиться к \\\\${config.host}\\${config.share}: " +
                    (t.message ?: t.javaClass.simpleName),
                t,
            )
        }
    }

    /** Проверка доступа: подключение и чтение корневого каталога. */
    fun testConnection() {
        val fs = ensureConnected()
        val base = if (root.isEmpty()) "" else root
        if (!fs.folderExists(base)) {
            throw IOException("Папка «$base» не найдена на \\\\${config.host}\\${config.share}")
        }
        fs.list(base)
    }

    private fun full(path: String): String = joinPath(root, path)

    private fun <T> run(block: (DiskShare) -> T): T {
        val first = ensureConnected()
        return try {
            block(first)
        } catch (e: SMBApiException) {
            throw e
        } catch (e: Exception) {
            // Соединение могло отвалиться по таймауту — один раз переподключаемся.
            closeQuietly()
            block(ensureConnected())
        }
    }

    override fun exists(path: String): Boolean = run { fs ->
        val target = full(path)
        fs.fileExists(target) || fs.folderExists(target)
    }

    override fun isDirectory(path: String): Boolean = run { fs ->
        fs.folderExists(full(path))
    }

    override fun list(path: String): List<DiskEntry> = run { fs ->
        fs.list(full(path))
            .asSequence()
            .filter { it.fileName != "." && it.fileName != ".." }
            .map {
                val dir = (it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
                DiskEntry(it.fileName, dir, it.endOfFile)
            }
            .sortedBy { it.name.lowercase() }
            .toList()
    }

    override fun read(path: String): ByteArray = run { fs ->
        val file = fs.openFile(
            full(path),
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        )
        file.use { it.inputStream.use { input -> input.readBytes() } }
    }

    override fun write(path: String, data: ByteArray) = run { fs ->
        val target = full(path)
        mkdirs(parentPath(target))
        val file = fs.openFile(
            target,
            EnumSet.of(AccessMask.GENERIC_WRITE, AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OVERWRITE_IF,
            null,
        )
        file.use {
            it.outputStream.use { output -> output.write(data) }
        }
    }

    override fun mkdirs(path: String) {
        val relative = normalizeDir(path)
        if (relative.isEmpty()) return
        val share = ensureConnected()
        var current = ""
        for (part in relative.split('/')) {
            if (part.isEmpty()) continue
            current = if (current.isEmpty()) part else "$current/$part"
            if (!share.folderExists(current)) {
                runCatching { share.mkdir(current) }
                    .onFailure { if (!share.folderExists(current)) throw it }
            }
        }
    }

    override fun delete(path: String) = run { fs ->
        val target = full(path)
        when {
            fs.fileExists(target) -> fs.rm(target)
            fs.folderExists(target) -> fs.rmdir(target, false)
            else -> Unit
        }
    }

    override fun deleteTree(path: String) = run { fs ->
        val target = full(path)
        when {
            fs.folderExists(target) -> fs.rmdir(target, true)
            fs.fileExists(target) -> fs.rm(target)
            else -> Unit
        }
    }

    @Synchronized
    private fun closeQuietly() {
        runCatching { share?.close() }
        runCatching { session?.close() }
        runCatching { connection?.close() }
        runCatching { client?.close() }
        share = null
        session = null
        connection = null
        client = null
    }

    @Synchronized
    override fun close() {
        closeQuietly()
    }
}
