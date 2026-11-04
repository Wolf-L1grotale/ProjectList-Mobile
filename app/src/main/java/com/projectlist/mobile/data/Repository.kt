package com.projectlist.mobile.data

import android.content.Context
import com.projectlist.mobile.data.offline.OfflineStore
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class Repository(
    private val api: ApiClient,
    private val session: SessionStore,
    private val offline: OfflineStore,
    context: Context,
    private val pairing: PairingClient = PairingClient(),
) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
    private val cacheFile = File(context.applicationContext.filesDir, "projects_cache.json")

    private val _projects = MutableStateFlow<List<Project>?>(null)
    val projectsFlow: StateFlow<List<Project>?> = _projects.asStateFlow()

    val offlineActive: Boolean get() = session.current.offlineActive

    // ---------------------------------------------------------------- auth

    suspend fun connect(
        server: String,
        key: String,
        scheme: String = ServerAddress.DEFAULT_SCHEME,
        port: Int = ServerAddress.DEFAULT_PORT,
    ): SessionState {
        val normalized = normalizeServer(server, scheme, port)
        val result = api.unlock(normalized, key.trim())
        if (!result.ok || result.token.isBlank()) {
            throw ApiException("Сервер не подтвердил вход", 0, "no-token")
        }
        // Кэш принадлежит другому серверу — сбрасываем.
        clearCache()
        session.saveLogin(normalized, result.token, key.trim(), result.admin, result.name)
        return session.current
    }

    /**
     * Подключает телефон к доверенному АРМ: запрос в локальной сети, одобрение
     * мастер-ключом FIDO на компьютере, получение ключа шифрования и сохранение
     * его в Android Keystore. После этого серверная сессия не нужна.
     */
    suspend fun pairWithTrustedPc(
        server: String,
        deviceName: String,
        scheme: String = ServerAddress.DEFAULT_SCHEME,
        port: Int = ServerAddress.DEFAULT_PORT,
        onProgress: (String) -> Unit,
    ): SessionState {
        val normalized = normalizeServer(server, scheme, port)
        val name = deviceName.trim().ifBlank { "Android" }
        onProgress("Отправляем запрос на $normalized…")
        val request = pairing.request(normalized, session.deviceId(), name)
        onProgress("Ожидаем подтверждения мастер-ключом FIDO на компьютере…")
        val encryptionKey = pairing.awaitApproval(request) { secondsLeft ->
            withContext(Dispatchers.Main) {
                onProgress("Ожидаем подтверждения на компьютере — осталось $secondsLeft с")
            }
        }
        clearCache()
        session.savePairing(normalized, request.deviceId, name, encryptionKey)
        return session.current
    }

    /** Забыть сопряжение и удалить полученный ключ с устройства. */
    fun forgetDevice() {
        session.clearPairing()
        clearCache()
    }

    fun normalizeServer(
        input: String,
        scheme: String = ServerAddress.DEFAULT_SCHEME,
        port: Int = ServerAddress.DEFAULT_PORT,
    ): String = ServerAddress.normalize(input, scheme, port)

    suspend fun logout() {
        val wasOffline = offlineActive
        if (!wasOffline) runCatching { api.logout() }
        session.logout()
        // Явный выход завершает и автономный доступ: настройки SMB остаются,
        // но включать их нужно заново.
        if (wasOffline || session.current.offline.enabled) session.setOfflineEnabled(false)
        clearCache()
    }

    // ------------------------------------------------------------ projects

    suspend fun loadProjects(): List<Project> {
        val list = if (offlineActive) offline.listProjects() else api.projects()
        _projects.value = list
        saveCache(list)
        return list
    }

    suspend fun loadProject(id: String): Project {
        val project = if (offlineActive) offline.getProject(id) else api.project(id)
        updateCache(project)
        return project
    }

    fun cachedProjects(): List<Project> = runCatching {
        if (!cacheFile.exists()) emptyList()
        else json.decodeFromString<List<Project>>(cacheFile.readText())
    }.getOrDefault(emptyList())

    suspend fun createProject(name: String, description: String): Project {
        if (offlineActive) {
            return offline.createProject(name, description).also { updateCache(it) }
        }
        val id = api.createProject(name, description)
        if (id.isBlank()) throw ApiException("Сервер не вернул идентификатор проекта")
        return api.project(id).also { updateCache(it) }
    }

    suspend fun patchProject(id: String, data: JsonObject? = null, name: String? = null, description: String? = null): Project {
        if (offlineActive) {
            return offline.patchProject(id, data, name, description).also { updateCache(it) }
        }
        val body = buildJsonObject {
            if (name != null) put("name", name)
            if (description != null) put("description", description)
            if (data != null) put("data", data)
        }
        val project = api.patchProject(id, body)
        updateCache(project)
        return project
    }

    suspend fun deleteProject(id: String) {
        if (offlineActive) {
            offline.deleteProject(id)
        } else {
            api.deleteProject(id)
        }
        _projects.update { current -> (current ?: cachedProjects()).filterNot { it.id == id } }
        _projects.value?.let { saveCache(it) }
    }

    // --------------------------------------------------------------- files

    /**
     * Партия загрузки: файлы можно добавлять по одному (в память попадает
     * только текущий файл и накопленный чанк), коммит создаётся в конце.
     * В автономном режиме загрузка файлов недоступна.
     */
    inner class UploadSession internal constructor(private val projectId: String) {
        private val batch = "android-" + UUID.randomUUID().toString()
        private var chunk = ArrayList<UploadFile>()
        private var chunkBytes = 0L
        private var uploaded = 0

        val uploadedCount: Int get() = uploaded

        suspend fun add(file: UploadFile) {
            if (offlineActive) {
                throw ApiException(
                    "Загрузка файлов недоступна в автономном режиме",
                    0,
                    "offline-no-upload",
                )
            }
            if (file.bytes.size > RAW_UPLOAD_THRESHOLD) {
                flush()
                api.uploadFile(projectId, batch, file)
            } else {
                if (chunkBytes + file.bytes.size > BATCH_CHUNK_BYTES || chunk.size >= BATCH_MAX_FILES) {
                    flush()
                }
                chunk.add(file)
                chunkBytes += file.bytes.size
            }
            uploaded += 1
        }

        private suspend fun flush() {
            if (chunk.isEmpty()) return
            api.uploadBatch(projectId, batch, chunk)
            chunk = ArrayList()
            chunkBytes = 0L
        }

        suspend fun commit(
            message: String,
            branch: String? = null,
            version: String? = null,
            remove: List<String> = emptyList(),
        ): CommitResponse {
            if (offlineActive) {
                val result = offline.createCommit(projectId, message, branch, version, remove)
                if (result.project.id.isNotBlank()) updateCache(result.project)
                return result
            }
            flush()
            val result = api.createCommit(
                projectId = projectId,
                batch = if (uploaded > 0) batch else null,
                message = message,
                branch = branch,
                version = version,
                remove = remove,
            )
            if (result.project.id.isNotBlank()) updateCache(result.project)
            return result
        }

        suspend fun cancel() {
            chunk = ArrayList()
            chunkBytes = 0L
            if (!offlineActive) api.cancelBatch(projectId, batch)
        }
    }

    fun uploadSession(projectId: String): UploadSession = UploadSession(projectId)

    suspend fun uploadAndCommit(
        projectId: String,
        files: List<UploadFile>,
        message: String,
        branch: String? = null,
        version: String? = null,
        remove: List<String> = emptyList(),
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): CommitResponse {
        if (files.isEmpty() && remove.isEmpty()) {
            throw ApiException("Нет изменений для коммита", 0, "empty")
        }
        if (offlineActive) {
            if (files.isNotEmpty()) {
                throw ApiException(
                    "Загрузка файлов недоступна в автономном режиме",
                    0,
                    "offline-no-upload",
                )
            }
            return offline.createCommit(projectId, message, branch, version, remove).also {
                if (it.project.id.isNotBlank()) updateCache(it.project)
            }
        }
        val session = uploadSession(projectId)
        try {
            files.forEachIndexed { index, file ->
                session.add(file)
                onProgress(index + 1, files.size)
            }
            return session.commit(message, branch, version, remove)
        } catch (t: Throwable) {
            session.cancel()
            throw t
        }
    }

    suspend fun createBranch(projectId: String, name: String, at: String? = null): Project {
        if (offlineActive) {
            return offline.createBranch(projectId, name, at).also { updateCache(it) }
        }
        return api.createBranch(projectId, name, at).project.also { updateCache(it) }
    }

    suspend fun rollback(projectId: String, commitId: String): CommitResponse {
        if (offlineActive) {
            return offline.rollback(projectId, commitId).also {
                if (it.project.id.isNotBlank()) updateCache(it.project)
            }
        }
        return api.rollback(projectId, commitId).also {
            if (it.project.id.isNotBlank()) updateCache(it.project)
        }
    }

    suspend fun merge(projectId: String, src: String, dst: String): CommitResponse {
        if (offlineActive) {
            return offline.merge(projectId, src, dst).also {
                if (it.project.id.isNotBlank()) updateCache(it.project)
            }
        }
        return api.merge(projectId, src, dst).also {
            if (it.project.id.isNotBlank()) updateCache(it.project)
        }
    }

    suspend fun downloadBlob(projectId: String, blobId: String): ByteArray =
        if (offlineActive) offline.downloadBlob(projectId, blobId) else api.blob(projectId, blobId)

    fun blobUrl(projectId: String, blobId: String): String =
        if (offlineActive) "offline://$projectId/$blobId" else api.blobUrl(projectId, blobId)

    suspend fun uploadScreenshot(projectId: String, name: String, mime: String, bytes: ByteArray): Project {
        if (offlineActive) {
            throw ApiException(
                "Добавление скриншотов недоступно в автономном режиме",
                0,
                "offline-no-upload",
            )
        }
        return api.uploadScreenshot(projectId, name, mime, bytes).project.also { updateCache(it) }
    }

    suspend fun deleteScreenshot(projectId: String, screenshotId: String): Project {
        if (offlineActive) {
            return offline.deleteScreenshot(projectId, screenshotId).also { updateCache(it) }
        }
        return api.deleteScreenshot(projectId, screenshotId).project.also { updateCache(it) }
    }

    // ------------------------------------------------------- notifications

    suspend fun notifications(limit: Int = 100): NotificationsResponse =
        if (offlineActive) NotificationsResponse(ok = true) else api.notifications(limit)

    suspend fun markAllNotificationsRead() {
        if (!offlineActive) api.markAllNotificationsRead()
    }

    suspend fun markNotificationRead(id: String) {
        if (!offlineActive) api.markNotificationRead(id)
    }

    suspend fun deleteNotification(id: String) {
        if (!offlineActive) api.deleteNotification(id)
    }

    suspend fun clearNotifications() {
        if (!offlineActive) api.clearNotifications()
    }

    // ------------------------------------------------------------- system

    suspend fun versionInfo(): VersionInfo =
        if (offlineActive) {
            VersionInfo(
                ok = true,
                name = "ProjectList",
                version = "автономный режим",
                maxUploadMb = 0,
                maxBatchFiles = 0,
                maxBatchRawMb = 0,
                uptime = 0.0,
            )
        } else {
            api.versionInfo()
        }

    suspend fun health(): HealthInfo =
        if (offlineActive) {
            HealthInfo(
                ok = true,
                minio = "SMB: ${session.current.offline.describe()}",
                serverTime = Instant.now().toString(),
            )
        } else {
            api.health()
        }

    suspend fun keyStatus(): KeyStatus =
        if (offlineActive) {
            KeyStatus(
                available = true,
                locked = false,
                deviceId = null,
                fingerprint = offline.fingerprint(session.current.offline),
            )
        } else {
            api.keyStatus()
        }

    suspend fun logs(limit: Int = 200): LogsResponse =
        if (offlineActive) {
            LogsResponse(
                entries = listOf(
                    LogEntry(
                        t = Instant.now().toString(),
                        level = "info",
                        category = "offline",
                        message = "Автономный режим: прямое чтение данных MinIO по SMB (${session.current.offline.describe()})",
                    )
                ),
                total = 1,
            )
        } else {
            api.logs(limit)
        }

    // ------------------------------------------------------------ helpers

    private fun saveCache(projects: List<Project>) {
        runCatching { cacheFile.writeText(json.encodeToString(projects)) }
    }

    private fun clearCache() {
        _projects.value = null
        runCatching { if (cacheFile.exists()) cacheFile.delete() }
    }

    private fun updateCache(project: Project) {
        if (project.id.isBlank()) return
        val current = _projects.value ?: cachedProjects()
        val next = if (current.any { it.id == project.id }) {
            current.map { if (it.id == project.id) project else it }
        } else {
            current + project
        }
        _projects.value = next
        saveCache(next)
    }

    private companion object {
        const val BATCH_CHUNK_BYTES = 8L * 1024L * 1024L
        const val BATCH_MAX_FILES = 100
        const val RAW_UPLOAD_THRESHOLD = 9L * 1024L * 1024L
    }
}
