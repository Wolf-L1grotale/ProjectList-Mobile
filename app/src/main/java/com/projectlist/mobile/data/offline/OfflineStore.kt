package com.projectlist.mobile.data.offline

import com.projectlist.mobile.data.ApiException
import com.projectlist.mobile.data.Branch
import com.projectlist.mobile.data.Changes
import com.projectlist.mobile.data.Commit
import com.projectlist.mobile.data.CommitResponse
import com.projectlist.mobile.data.CommitStats
import com.projectlist.mobile.data.FileEntry
import com.projectlist.mobile.data.Project
import com.projectlist.mobile.data.Screenshot
import com.projectlist.mobile.data.SessionStore
import java.io.Closeable
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Автономные операции над данными ProjectList, лежащими в MinIO на SMB-диске.
 *
 * Записи проектов хранятся как «сырые» JSON-объекты: так мы не теряем поля,
 * которых нет в мобильной модели (например, будущие расширения формата).
 * Правка проекта повторяет семантику PATCH сервера: поверхностный merge `data`.
 */
class OfflineStore(
    private val configProvider: () -> OfflineConfig,
    private val activeProvider: () -> Boolean,
    private val diskFactory: (OfflineConfig) -> DiskFs = { it.disk() },
) : Closeable {

    constructor(session: SessionStore) : this(
        { session.current.offline },
        { session.current.offlineActive },
    )

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
    }

    private val mutex = Mutex()

    private var activeConfig: OfflineConfig? = null
    private var disk: MinioDiskStore? = null
    private var key: ByteArray? = null

    // ------------------------------------------------------------ настройка

    /** Проверка SMB-подключения и ключа шифрования. Возвращает описание хранилища. */
    suspend fun testConnection(config: OfflineConfig): String = withContext(Dispatchers.IO) {
        if (!config.configured) {
            throw ApiException("Заполните адрес, логин, пароль, bucket и ключ шифрования", 0, "offline-config")
        }
        val fs = config.disk()
        try {
            if (fs is SmbDiskFs) fs.testConnection()
            if (!fs.isDirectory(config.bucket)) {
                throw IOException("Папка «${config.bucket}» не найдена в ${config.describe()}")
            }
            val encryptionKey = CryptoBox.parseKey(config.encryptionKey)
            val store = MinioDiskStore(fs, config.bucket)
            val indexBytes = store.get(PROJECT_INDEX)
                ?: throw IOException("Не найден $PROJECT_INDEX в bucket «${config.bucket}»")
            val index = runCatching {
                json.parseToJsonElement(indexBytes.toString(Charsets.UTF_8)) as JsonObject
            }.getOrElse {
                throw IOException("Файл $PROJECT_INDEX повреждён")
            }
            // Пробуем расшифровать первую запись — так проверяется и ключ.
            val first = index.keys.firstOrNull { it.endsWith(".enc") }
            if (first != null) {
                val id = first.removeSuffix(".enc")
                val record = store.get(projectKey(id))
                    ?: throw IOException("Не найден файл проекта $id")
                runCatching {
                    CryptoBox.decryptRecord(record.toString(Charsets.UTF_8), encryptionKey)
                }.getOrElse {
                    throw IOException("Ключ шифрования не подходит к данным ProjectList")
                }
            }
            "Подключено: ${config.describe()}, проектов: ${index.size}"
        } finally {
            fs.close()
        }
    }

    fun fingerprint(config: OfflineConfig): String? = runCatching {
        CryptoBox.keyFingerprint(CryptoBox.parseKey(config.encryptionKey))
    }.getOrNull()

    fun isActive(): Boolean = activeProvider()

    // ------------------------------------------------------------- проекты

    suspend fun listProjects(): List<Project> = io { store, encryptionKey ->
        projectIds(store).mapNotNull { id ->
            runCatching { readProject(store, encryptionKey, id) }.getOrNull()
        }.sortedWith(
            compareByDescending<Project> { it.updatedAt ?: it.createdAt }
                .thenBy { it.name.lowercase() }
        )
    }

    suspend fun getProject(id: String): Project = io { store, encryptionKey ->
        readProject(store, encryptionKey, id)
    }

    suspend fun createProject(name: String, description: String): Project = io { store, encryptionKey ->
        val cleanName = name.trim().take(200)
        if (cleanName.isEmpty()) throw ApiException("Укажите название проекта", 400, "name is required")
        val now = isoNow()
        val id = UUID.randomUUID().toString()
        val record = buildJsonObject {
            put("id", id)
            put("name", cleanName)
            put("description", description.trim().take(2000))
            put("data", JsonObject(emptyMap()))
            put("createdAt", now)
        }
        saveRecord(store, encryptionKey, record)
    }

    suspend fun patchProject(
        id: String,
        data: JsonObject? = null,
        name: String? = null,
        description: String? = null,
    ): Project = io { store, encryptionKey ->
        var record = readRecord(store, encryptionKey, id)

        if (name != null) {
            val clean = name.trim().take(200)
            if (clean.isEmpty()) throw ApiException("Название проекта не может быть пустым", 400, "name is required")
            record = withValue(record, "name", JsonPrimitive(clean))
        }
        if (description != null) {
            record = withValue(record, "description", JsonPrimitive(description.trim().take(2000)))
        }
        if (data != null) {
            val current = record.dataMap()
            val merged = current.toMutableMap()
            data.forEach { (key, value) -> merged[key] = value }
            record = withValue(record, "data", JsonObject(merged))
        }
        record = withValue(record, "updatedAt", JsonPrimitive(isoNow()))
        saveRecord(store, encryptionKey, record)
    }

    suspend fun deleteProject(id: String) = io { store, encryptionKey ->
        readRecord(store, encryptionKey, id)
        store.delete(projectKey(id))
        store.delete("blobs/$id")
        updateIndex(store, id, null)
    }

    // ------------------------------------------------------------- коммиты

    suspend fun createCommit(
        projectId: String,
        message: String,
        branch: String? = null,
        version: String? = null,
        remove: List<String> = emptyList(),
    ): CommitResponse = io { store, encryptionKey ->
        var record = readRecord(store, encryptionKey, projectId)
        val data = record.dataMap().toMutableMap()
        val commits = commitObjects(data)
        ensureBranchState(data)
        val branches = branchObjects(data).toMutableList()

        val branchName = sanitizeBranchName(branch, data.text("currentBranch") ?: "main")
        var branchObj = branches.firstOrNull { it.text("name") == branchName }
        if (branchObj == null) {
            val currentHead = branchHead(commits, branches, data.text("currentBranch"))
            branchObj = buildJsonObject {
                put("name", branchName)
                put("head", currentHead?.text("id"))
                put("createdAt", isoNow())
            }
            branches += branchObj
            data["currentBranch"] = JsonPrimitive(branchName)
        }

        val head = commitById(commits, branchObj.text("head"))
        val prevFiles = head?.let(::commitFiles).orEmpty()
        val nextFiles = mergeFiles(prevFiles, remove)
        val changes = diffTrees(prevFiles, nextFiles)
        if (changes.added.isEmpty() && changes.modified.isEmpty() && changes.removed.isEmpty()) {
            throw ApiException("Нет изменений для коммита", 400, "nothing-to-commit")
        }

        val date = isoNow()
        val commitMessage = message.trim().take(500).ifBlank { "Обновление файлов" }
        val customVersion = version.orEmpty().trim().replace(Regex("\\s+"), " ").take(40)
        val versionLabel = customVersion.ifBlank { head?.text("version") ?: "v1" }

        val versions = (data["versions"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        if (!versions.containsKey(versionLabel)) {
            versions[versionLabel] = buildJsonObject {
                put("name", "")
                put("description", "")
                put("createdAt", date)
            }
        }
        data["versions"] = JsonObject(versions)

        val commitId = commitShortId(
            buildJsonObject {
                put("parent", head?.text("id"))
                put("message", commitMessage)
                put("date", date)
                put("files", buildJsonArray { nextFiles.forEach { add(JsonPrimitive("${it.path}:${it.sha}")) } })
                put("r", randomHex(4))
            }.toString(),
        )
        val commit = buildJsonObject {
            put("id", commitId)
            put("version", versionLabel)
            put("message", commitMessage)
            put("date", date)
            put("parent", head?.text("id"))
            put("branch", branchName)
            put("files", json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(FileEntry.serializer()), nextFiles))
            put("changes", changesJson(changes))
            put("stats", statsJson(nextFiles))
        }

        data["commits"] = JsonArray(listOf(commit) + commits)
        data["head"] = JsonPrimitive(commitId)
        data["version"] = JsonPrimitive(versionLabel)
        data["branches"] = JsonArray(
            branches.map { item ->
                if (item.text("name") == branchName) withValue(item, "head", JsonPrimitive(commitId)) else item
            }
        )
        data.remove("files")
        record = withValue(withValue(record, "data", JsonObject(data)), "updatedAt", JsonPrimitive(date))
        saveRecord(store, encryptionKey, record)

        CommitResponse(
            commit = json.decodeFromJsonElement(Commit.serializer(), commit),
            project = json.decodeFromJsonElement(Project.serializer(), record),
            branch = branchName,
        )
    }

    // -------------------------------------------------------------- ветки

    suspend fun createBranch(projectId: String, name: String, at: String? = null): Project =
        io { store, encryptionKey ->
            var record = readRecord(store, encryptionKey, projectId)
            val data = record.dataMap().toMutableMap()
            val commits = commitObjects(data)
            ensureBranchState(data)
            val branches = branchObjects(data).toMutableList()

            val branchName = sanitizeBranchName(name, "")
            if (branchName.isEmpty()) {
                throw ApiException("Название ветки некорректно", 400, "bad-branch")
            }
            if (branches.any { it.text("name") == branchName }) {
                throw ApiException("Ветка с таким именем уже существует", 409, "branch-exists")
            }
            val target = if (!at.isNullOrBlank()) commitById(commits, at) else null
            val head = target ?: branchHead(commits, branches, data.text("currentBranch"))
            branches += buildJsonObject {
                put("name", branchName)
                put("head", head?.text("id"))
                put("createdAt", isoNow())
            }
            data["branches"] = JsonArray(branches)
            data["currentBranch"] = JsonPrimitive(branchName)
            record = withValue(
                withValue(record, "data", JsonObject(data)),
                "updatedAt",
                JsonPrimitive(isoNow()),
            )
            saveRecord(store, encryptionKey, record)
        }

    suspend fun merge(
        projectId: String,
        src: String,
        dst: String,
        message: String? = null,
    ): CommitResponse = io { store, encryptionKey ->
        var record = readRecord(store, encryptionKey, projectId)
        val data = record.dataMap().toMutableMap()
        val commits = commitObjects(data)
        ensureBranchState(data)
        var branches = branchObjects(data).toMutableList()

        val srcName = sanitizeBranchName(src, "")
        val dstName = sanitizeBranchName(dst, data.text("currentBranch") ?: "main")
        val srcBranch = branches.firstOrNull { it.text("name") == srcName }
            ?: throw ApiException("Исходная ветка не найдена", 404, "Not found")
        val dstBranch = branches.firstOrNull { it.text("name") == dstName }
            ?: throw ApiException("Целевая ветка не найдена", 404, "Not found")
        if (srcName == dstName) throw ApiException("Нельзя слить ветку саму с собой", 400, "same-branch")

        val srcHead = commitById(commits, srcBranch.text("head"))
            ?: throw ApiException("Исходная ветка пуста: нет коммитов", 409, "empty-branch")
        val dstHead = commitById(commits, dstBranch.text("head"))
            ?: throw ApiException("Целевая ветка пуста: нет коммитов", 409, "empty-branch")

        val baseFiles = mergeBase(commits, dstHead, srcHead)?.let(::commitFiles).orEmpty()
        val dstFiles = commitFiles(dstHead)
        val srcFiles = commitFiles(srcHead)
        val merged = threeWayMerge(baseFiles, dstFiles, srcFiles)
        if (merged.conflicts.isNotEmpty()) {
            throw ApiException(
                "Конфликты слияния: " + merged.conflicts.joinToString(", "),
                409,
                "conflicts",
            )
        }
        val changes = diffTrees(dstFiles, merged.files)
        if (changes.added.isEmpty() && changes.modified.isEmpty() && changes.removed.isEmpty()) {
            throw ApiException(
                "Нечего сливать: ветка «$dstName» уже содержит изменения «$srcName»",
                409,
                "nothing-to-merge",
            )
        }

        val date = isoNow()
        val commitMessage = message.orEmpty().trim().take(500)
            .ifBlank { "Слияние ветки <$srcName> в <$dstName>" }
        val versionLabel = dstHead.text("version") ?: "v1"
        val commitId = commitShortId(
            buildJsonObject {
                put("parent", dstHead.text("id"))
                put("mergeOf", srcHead.text("id"))
                put("message", commitMessage)
                put("date", date)
                put("files", buildJsonArray { merged.files.forEach { add(JsonPrimitive("${it.path}:${it.sha}")) } })
                put("r", randomHex(4))
            }.toString(),
        )
        val commit = buildJsonObject {
            put("id", commitId)
            put("version", versionLabel)
            put("message", commitMessage)
            put("date", date)
            put("parent", dstHead.text("id"))
            put("mergeOf", srcHead.text("id"))
            put("branch", dstName)
            put("files", json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(FileEntry.serializer()), merged.files))
            put("changes", changesJson(changes))
            put("stats", statsJson(merged.files))
        }

        branches = branches.map { item ->
            when (item.text("name")) {
                dstName -> withValue(item, "head", JsonPrimitive(commitId))
                srcName -> withValue(
                    withValue(item, "mergedInto", JsonPrimitive(dstName)),
                    "mergedHead",
                    JsonPrimitive(commitId),
                )
                else -> item
            }
        }.toMutableList()
        data["commits"] = JsonArray(listOf(commit) + commits)
        data["branches"] = JsonArray(branches)
        data["head"] = JsonPrimitive(commitId)
        data["version"] = JsonPrimitive(versionLabel)
        record = withValue(
            withValue(record, "data", JsonObject(data)),
            "updatedAt",
            JsonPrimitive(date),
        )
        saveRecord(store, encryptionKey, record)

        CommitResponse(
            commit = json.decodeFromJsonElement(Commit.serializer(), commit),
            project = json.decodeFromJsonElement(Project.serializer(), record),
            branch = dstName,
        )
    }

    suspend fun rollback(projectId: String, commitId: String, message: String? = null): CommitResponse =
        io { store, encryptionKey ->
            var record = readRecord(store, encryptionKey, projectId)
            val data = record.dataMap().toMutableMap()
            val commits = commitObjects(data)
            ensureBranchState(data)
            var branches = branchObjects(data).toMutableList()

            val target = commitById(commits, commitId)
                ?: throw ApiException("Коммит не найден", 404, "Not found")
            val branchName = data.text("currentBranch") ?: "main"
            val branch = branches.firstOrNull { it.text("name") == branchName }
                ?: throw ApiException("Текущая ветка не найдена", 404, "Not found")
            val head = commitById(commits, branch.text("head"))
            val targetFiles = commitFiles(target)
            val prevFiles = head?.let(::commitFiles).orEmpty()
            val changes = diffTrees(prevFiles, targetFiles)
            if (changes.added.isEmpty() && changes.modified.isEmpty() && changes.removed.isEmpty()) {
                throw ApiException("Проект уже находится в этом состоянии", 409, "nothing-to-change")
            }

            val date = isoNow()
            val targetVersion = target.text("version").orEmpty().ifBlank { "версии" }
            val commitMessage = message.orEmpty().trim().take(500)
                .ifBlank { "Откат к версии $targetVersion ($commitId)" }
            val versionLabel = head?.text("version") ?: "v1"
            val newId = commitShortId(
                buildJsonObject {
                    put("parent", head?.text("id"))
                    put("message", commitMessage)
                    put("date", date)
                    put("files", buildJsonArray { targetFiles.forEach { add(JsonPrimitive("${it.path}:${it.sha}")) } })
                    put("r", randomHex(4))
                }.toString(),
            )
            val commit = buildJsonObject {
                put("id", newId)
                put("version", versionLabel)
                put("message", commitMessage)
                put("date", date)
                put("parent", head?.text("id"))
                put("branch", branchName)
                put("files", json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(FileEntry.serializer()), targetFiles))
                put("changes", changesJson(changes))
                put("stats", statsJson(targetFiles))
            }

            data["commits"] = JsonArray(listOf(commit) + commits)
            data["head"] = JsonPrimitive(newId)
            data["version"] = JsonPrimitive(versionLabel)
            data["branches"] = JsonArray(
                branches.map { item ->
                    if (item.text("name") == branchName) withValue(item, "head", JsonPrimitive(newId)) else item
                }
            )
            record = withValue(
                withValue(record, "data", JsonObject(data)),
                "updatedAt",
                JsonPrimitive(date),
            )
            saveRecord(store, encryptionKey, record)

            CommitResponse(
                commit = json.decodeFromJsonElement(Commit.serializer(), commit),
                project = json.decodeFromJsonElement(Project.serializer(), record),
                branch = branchName,
            )
        }

    // --------------------------------------------------------- скриншоты

    suspend fun deleteScreenshot(projectId: String, screenshotId: String): Project =
        io { store, encryptionKey ->
            var record = readRecord(store, encryptionKey, projectId)
            val data = record.dataMap().toMutableMap()
            val shots = (data["screenshots"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
            val next = shots.filterNot { it.text("id") == screenshotId }
            if (next.size == shots.size) {
                throw ApiException("Скриншот не найден", 404, "Not found")
            }
            data["screenshots"] = JsonArray(next)
            record = withValue(
                withValue(record, "data", JsonObject(data)),
                "updatedAt",
                JsonPrimitive(isoNow()),
            )
            saveRecord(store, encryptionKey, record)
        }

    // -------------------------------------------------------------- блобы

    suspend fun downloadBlob(projectId: String, blobId: String): ByteArray = io { store, encryptionKey ->
        val packed = store.get("blobs/$projectId/$blobId")
            ?: throw ApiException("Файл не найден в хранилище", 404, "Not found")
        CryptoBox.decryptBlob(packed, encryptionKey)
    }

    // ---------------------------------------------------------- внутреннее

    private suspend fun <T> io(block: (MinioDiskStore, ByteArray) -> T): T =
        withContext(Dispatchers.IO) {
            mutex.withLock { openStore().let { (store, encryptionKey) -> block(store, encryptionKey) } }
        }

    private fun openStore(): Pair<MinioDiskStore, ByteArray> {
        val config = configProvider()
        if (!config.enabled) {
            closeInternal()
            throw ApiException("Автономный режим выключен", 0, "offline-disabled")
        }
        if (!config.configured) {
            closeInternal()
            throw ApiException(
                "Не заполнены параметры автономного доступа (SMB и ключ шифрования)",
                0,
                "offline-config",
            )
        }
        if (disk == null || config != activeConfig) {
            closeInternal()
            val newDisk = MinioDiskStore(diskFactory(config), config.bucket)
            val newKey = try {
                CryptoBox.parseKey(config.encryptionKey)
            } catch (t: Throwable) {
                newDisk.close()
                throw ApiException(t.message ?: "Неверный ключ шифрования", 0, "offline-key")
            }
            disk = newDisk
            key = newKey
            activeConfig = config
        }
        return disk!! to key!!
    }

    private fun closeInternal() {
        runCatching { disk?.close() }
        disk = null
        key = null
        activeConfig = null
    }

    @Synchronized
    override fun close() {
        closeInternal()
    }

    private fun readProject(store: MinioDiskStore, encryptionKey: ByteArray, id: String): Project =
        json.decodeFromJsonElement(Project.serializer(), readRecord(store, encryptionKey, id))

    private fun readRecord(store: MinioDiskStore, encryptionKey: ByteArray, id: String): JsonObject {
        val packed = store.get(projectKey(id))
            ?: throw ApiException("Проект не найден", 404, "Not found")
        val text = try {
            CryptoBox.decryptRecord(packed.toString(Charsets.UTF_8), encryptionKey)
        } catch (t: Throwable) {
            throw ApiException("Не удалось расшифровать проект: ${t.message ?: "неверный ключ"}", 0, "offline-key")
        }
        return runCatching { json.parseToJsonElement(text) as JsonObject }
            .getOrElse { throw ApiException("Файл проекта повреждён", 0, "offline-format") }
    }

    private fun saveRecord(
        store: MinioDiskStore,
        encryptionKey: ByteArray,
        record: JsonObject,
    ): Project {
        val id = record.text("id")
            ?: throw ApiException("В записи проекта нет id", 0, "offline-format")
        val packed = CryptoBox.encryptRecord(record.toString(), encryptionKey)
        store.put(projectKey(id), packed.toByteArray(Charsets.UTF_8), "application/json")
        updateIndex(store, id, record.text("name").orEmpty())
        return json.decodeFromJsonElement(Project.serializer(), record)
    }

    private fun projectIds(store: MinioDiskStore): List<String> {
        val index = readIndex(store)
        val fromIndex = index.keys
            .filter { it.endsWith(".enc") }
            .map { it.removeSuffix(".enc") }
        if (fromIndex.isNotEmpty()) return fromIndex
        return store.listKeys("projects")
            .filter { it.endsWith(".enc") }
            .map { pathName(it).removeSuffix(".enc") }
            .filter { it.isNotBlank() }
    }

    private fun readIndex(store: MinioDiskStore): JsonObject {
        val bytes = store.get(PROJECT_INDEX) ?: return JsonObject(emptyMap())
        return runCatching { json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as JsonObject }
            .getOrElse { JsonObject(emptyMap()) }
    }

    private fun updateIndex(store: MinioDiskStore, id: String, name: String?) {
        val map = readIndex(store).toMutableMap()
        val entry = "$id.enc"
        if (name == null) {
            map.remove(entry)
        } else {
            map[entry] = buildJsonObject {
                put("name", name)
                put("updatedAt", isoNow())
            }
        }
        val text = JsonObject(map).toString()
        store.put(PROJECT_INDEX, text.toByteArray(Charsets.UTF_8), "application/json")
    }

    private fun projectKey(id: String): String = "projects/$id.enc"

    // ------------------------------------------------------- сериализация

    private fun Map<String, JsonElement>.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun Map<String, JsonElement>.num(key: String): Long? =
        (this[key] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.dataMap(): Map<String, JsonElement> =
        (this["data"] as? JsonObject) ?: emptyMap()

    private fun withValue(obj: JsonObject, key: String, value: JsonElement): JsonObject =
        JsonObject(obj.toMutableMap().apply { put(key, value) })

    private fun commitObjects(data: Map<String, JsonElement>): List<JsonObject> =
        (data["commits"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

    private fun branchObjects(data: Map<String, JsonElement>): List<JsonObject> =
        (data["branches"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

    private fun commitById(commits: List<JsonObject>, id: String?): JsonObject? {
        if (id.isNullOrBlank()) return null
        return commits.firstOrNull { it.text("id") == id }
    }

    private fun branchHead(
        commits: List<JsonObject>,
        branches: List<JsonObject>,
        name: String?,
    ): JsonObject? {
        val branch = branches.firstOrNull { it.text("name") == name } ?: return null
        return commitById(commits, branch.text("head"))
    }

    private fun commitFiles(commit: JsonObject): List<FileEntry> =
        (commit["files"] as? JsonArray)?.mapNotNull { element ->
            runCatching { json.decodeFromJsonElement<FileEntry>(element) }.getOrNull()
        }.orEmpty()

    /** Создаёт ветку main и корректный currentBranch, если данных ещё нет. */
    private fun ensureBranchState(data: MutableMap<String, JsonElement>) {
        val commits = commitObjects(data)
        var branches = branchObjects(data)
        if (branches.isEmpty()) {
            val headId = data.text("head")?.takeIf { id -> commits.any { it.text("id") == id } }
                ?: commits.firstOrNull()?.text("id")
            val head = commitById(commits, headId)
            branches = listOf(
                buildJsonObject {
                    put("name", "main")
                    put("head", headId)
                    put("createdAt", head?.text("date") ?: isoNow())
                }
            )
            data["branches"] = JsonArray(branches)
        }
        val current = data.text("currentBranch")
        if (current == null || branches.none { it.text("name") == current }) {
            data["currentBranch"] = JsonPrimitive(branches.first().text("name") ?: "main")
        }
    }

    private fun mergeFiles(prevFiles: List<FileEntry>, remove: List<String>): List<FileEntry> {
        val map = LinkedHashMap<String, FileEntry>()
        prevFiles.forEach { map[it.path] = it }
        remove.mapNotNull(::sanitizePath).forEach { map.remove(it) }
        return map.values.sortedBy { it.path }
    }

    private fun diffTrees(prev: List<FileEntry>, next: List<FileEntry>): Changes {
        val prevMap = prev.associateBy { it.path }
        val nextMap = next.associateBy { it.path }
        val added = ArrayList<String>()
        val modified = ArrayList<String>()
        val removed = ArrayList<String>()
        nextMap.forEach { (path, file) ->
            val old = prevMap[path]
            if (old == null) added += path else if (old.sha != file.sha) modified += path
        }
        prevMap.forEach { (path, _) -> if (path !in nextMap) removed += path }
        return Changes(added = added, modified = modified, removed = removed)
    }

    private fun changesJson(changes: Changes): JsonObject = buildJsonObject {
        put("added", buildJsonArray { changes.added.forEach { add(JsonPrimitive(it)) } })
        put("modified", buildJsonArray { changes.modified.forEach { add(JsonPrimitive(it)) } })
        put("removed", buildJsonArray { changes.removed.forEach { add(JsonPrimitive(it)) } })
    }

    private fun statsJson(files: List<FileEntry>): JsonObject = buildJsonObject {
        put("files", files.size)
        put("bytes", files.sumOf { it.size })
    }

    private fun mergeBase(
        commits: List<JsonObject>,
        aHead: JsonObject,
        bHead: JsonObject,
    ): JsonObject? {
        val ancestors = Ancestors(commits)
        val aSet = ancestors.of(aHead)
        if (bHead.text("id") in aSet) return bHead
        val byId = commits.associateBy { it.text("id") }
        val seen = HashSet<String>()
        val stack = ArrayDeque<JsonObject>()
        stack.addLast(bHead)
        while (stack.isNotEmpty()) {
            val commit = stack.removeLast()
            val id = commit.text("id") ?: continue
            if (!seen.add(id)) continue
            if (id in aSet) return commit
            commit.text("parent")?.let { byId[it] }?.let(stack::addLast)
            commit.text("mergeOf")?.let { byId[it] }?.let(stack::addLast)
        }
        return null
    }

    private inner class Ancestors(private val commits: List<JsonObject>) {
        private val byId = commits.associateBy { it.text("id") }

        fun of(start: JsonObject): Set<String> {
            val seen = HashSet<String>()
            val stack = ArrayDeque<JsonObject>()
            stack.addLast(start)
            while (stack.isNotEmpty()) {
                val commit = stack.removeLast()
                val id = commit.text("id") ?: continue
                if (!seen.add(id)) continue
                commit.text("parent")?.let { byId[it] }?.let(stack::addLast)
                commit.text("mergeOf")?.let { byId[it] }?.let(stack::addLast)
            }
            return seen
        }
    }

    private data class MergeResult(
        val files: List<FileEntry> = emptyList(),
        val conflicts: List<String> = emptyList(),
    )

    private fun threeWayMerge(
        baseFiles: List<FileEntry>,
        oursFiles: List<FileEntry>,
        theirsFiles: List<FileEntry>,
    ): MergeResult {
        val base = baseFiles.associateBy { it.path }
        val ours = oursFiles.associateBy { it.path }
        val theirs = theirsFiles.associateBy { it.path }
        val result = LinkedHashMap(ours)
        val paths = LinkedHashSet<String>()
        paths += base.keys
        paths += ours.keys
        paths += theirs.keys
        val conflicts = ArrayList<String>()

        for (path in paths) {
            val b = base[path]
            val o = ours[path]
            val t = theirs[path]

            if (b == null) {
                if (o == null && t != null) {
                    result[path] = t
                    continue
                }
                if (o != null && t == null) continue
                if (o != null && t != null && o.blobId != t.blobId) conflicts += path
                continue
            }

            if (o == null && t == null) continue
            val oursChanged = o == null || o.blobId != b.blobId
            val theirsChanged = t == null || t.blobId != b.blobId
            if (!oursChanged && !theirsChanged) continue
            if (!oursChanged) {
                if (t != null) result[path] = t else result.remove(path)
            } else if (!theirsChanged) {
                // оставляем нашу версию
            } else if (o != null && t != null && o.blobId == t.blobId) {
                // одинаковые изменения
            } else {
                conflicts += path
            }
        }

        if (conflicts.isNotEmpty()) return MergeResult(conflicts = conflicts.sorted())
        return MergeResult(files = result.values.sortedBy { it.path })
    }

    private companion object {
        const val PROJECT_INDEX = "projects/_index.json"
        val ISO: DateTimeFormatter = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC)

        fun isoNow(): String = ISO.format(Instant.now())

        fun sanitizePath(raw: String?): String? {
            if (raw == null) return null
            val parts = raw.replace('\\', '/').split('/')
                .filter { it.isNotEmpty() && it != "." && it != ".." }
            return if (parts.isEmpty()) null else parts.joinToString("/")
        }

        fun sanitizeBranchName(raw: String?, fallback: String?): String {
            var value = raw.orEmpty().trim()
            value = value.replace(Regex("[^A-Za-z0-9 ._\\-]"), "")
                .replace(Regex("\\s+"), "-")
                .replace(Regex("-+"), "-")
                .take(40)
                .trim()
            return value.ifEmpty { fallback.orEmpty().ifEmpty { "main" } }
        }

        fun commitShortId(seed: String): String =
            sha256Hex(seed.toByteArray(Charsets.UTF_8)).take(7)

        fun randomHex(bytes: Int): String {
            val data = ByteArray(bytes)
            java.security.SecureRandom().nextBytes(data)
            return data.toHex()
        }
    }
}
