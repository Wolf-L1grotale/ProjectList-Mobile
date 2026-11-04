package com.projectlist.mobile.ui

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.projectlist.mobile.data.Project
import com.projectlist.mobile.data.Repository
import com.projectlist.mobile.data.SessionStore
import com.projectlist.mobile.data.Task
import com.projectlist.mobile.data.UploadFile
import com.projectlist.mobile.data.friendlyError
import com.projectlist.mobile.ui.components.currentBranchName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class DetailUiState(
    val loading: Boolean = true,
    val project: Project? = null,
    val allProjects: List<Project> = emptyList(),
    val error: String? = null,
    val busy: Boolean = false,
    val uploadProgress: Pair<Int, Int>? = null,
    val message: String? = null,
)

class ProjectDetailViewModel(
    application: Application,
    private val repository: Repository,
    private val session: SessionStore,
    private val projectId: String,
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(DetailUiState())
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.projectsFlow.collect { list ->
                if (list != null) {
                    val fresh = list.find { it.id == projectId }
                    _state.update {
                        it.copy(
                            project = fresh ?: it.project,
                            allProjects = list,
                            loading = if (fresh != null) false else it.loading,
                        )
                    }
                }
            }
        }
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = it.project == null, error = null) }
            try {
                val project = repository.loadProject(projectId)
                _state.update {
                    it.copy(
                        project = project,
                        loading = false,
                        error = null,
                        allProjects = it.allProjects.ifEmpty { repository.cachedProjects() },
                    )
                }
                if (_state.value.allProjects.isEmpty()) {
                    runCatching { repository.loadProjects() }
                }
            } catch (t: Throwable) {
                if (!session.current.hasAccess) return@launch
                _state.update { it.copy(loading = false, error = friendlyError(t)) }
            }
        }
    }

    fun refresh() = load()

    fun setStatus(status: String) {
        mutate(buildJsonObject { put("status", status) })
    }

    fun addTask(title: String) {
        val tasks = _state.value.project?.data?.tasks ?: return
        val clean = title.trim()
        if (clean.isEmpty()) return
        val next = tasks + Task(title = clean.take(300), done = false)
        mutate(tasksData(next), successMessage = "Задача добавлена")
    }

    fun toggleTask(index: Int) {
        val tasks = _state.value.project?.data?.tasks ?: return
        if (index !in tasks.indices) return
        val next = tasks.toMutableList().also { list ->
            list[index] = list[index].copy(done = !list[index].done)
        }
        mutate(tasksData(next))
    }

    fun deleteTask(index: Int) {
        val tasks = _state.value.project?.data?.tasks ?: return
        if (index !in tasks.indices) return
        val next = tasks.toMutableList().also { it.removeAt(index) }
        mutate(tasksData(next), successMessage = "Задача удалена")
    }

    fun editProject(name: String, description: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                val project = repository.patchProject(
                    id = projectId,
                    name = name.trim(),
                    description = description.trim(),
                )
                _state.update { it.copy(busy = false, project = project, message = "Проект сохранён") }
                onDone(true)
            } catch (t: Throwable) {
                _state.update { it.copy(busy = false, message = friendlyError(t)) }
                onDone(false)
            }
        }
    }

    fun deleteProject(onDone: () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                repository.deleteProject(projectId)
                onDone()
            } catch (t: Throwable) {
                _state.update { it.copy(busy = false, message = friendlyError(t)) }
            }
        }
    }

    // --------------------------------------------------------------- files

    fun uploadFromUris(uris: List<Uri>, message: String, version: String?, onDone: (Boolean) -> Unit) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, uploadProgress = null, message = null) }
            val skipped = mutableListOf<String>()
            val upload = repository.uploadSession(projectId)
            val usedPaths = mutableSetOf<String>()
            try {
                // Читаем и отправляем по одному файлу: память не растёт
                // пропорционально количеству выбранных файлов.
                uris.forEachIndexed { index, uri ->
                    val file = withContext(Dispatchers.IO) { prepareUpload(uri, skipped) } ?: return@forEachIndexed
                    upload.add(uniqueUploadPath(file, usedPaths))
                    _state.update { it.copy(uploadProgress = (index + 1) to uris.size) }
                }
                if (upload.uploadedCount == 0) {
                    upload.cancel()
                    _state.update {
                        it.copy(
                            busy = false,
                            uploadProgress = null,
                            message = skipped.firstOrNull() ?: "Не удалось прочитать выбранные файлы",
                        )
                    }
                    onDone(false)
                    return@launch
                }
                val result = upload.commit(
                    message = message.trim().ifEmpty { "Обновление файлов" },
                    version = version?.trim()?.takeIf { it.isNotEmpty() },
                )
                val tail = if (skipped.isNotEmpty()) " Пропущено: ${skipped.size}." else ""
                _state.update {
                    it.copy(
                        busy = false,
                        uploadProgress = null,
                        project = result.project,
                        message = "Коммит ${result.commit.id} создан.$tail",
                    )
                }
                onDone(true)
            } catch (t: Throwable) {
                upload.cancel()
                _state.update { it.copy(busy = false, uploadProgress = null, message = friendlyError(t)) }
                onDone(false)
            }
        }
    }

    fun deleteFile(path: String) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                val result = repository.uploadAndCommit(
                    projectId = projectId,
                    files = emptyList(),
                    message = "Удалён файл $path",
                    remove = listOf(path),
                )
                _state.update {
                    it.copy(busy = false, project = result.project, message = "Файл удалён коммитом ${result.commit.id}")
                }
            } catch (t: Throwable) {
                _state.update { it.copy(busy = false, message = friendlyError(t)) }
            }
        }
    }

    fun createBranch(name: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                val project = repository.createBranch(projectId, name.trim())
                _state.update { it.copy(busy = false, project = project, message = "Ветка «${name.trim()}» создана") }
                onDone(true)
            } catch (t: Throwable) {
                _state.update { it.copy(busy = false, message = friendlyError(t)) }
                onDone(false)
            }
        }
    }

    fun mergeBranch(source: String, onDone: (Boolean) -> Unit) {
        val target = _state.value.project?.let { currentBranchName(it) } ?: "main"
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                val result = repository.merge(projectId, source, target)
                _state.update {
                    it.copy(
                        busy = false,
                        project = result.project,
                        message = "Ветка «$source» слита в «$target»",
                    )
                }
                onDone(true)
            } catch (t: Throwable) {
                _state.update { it.copy(busy = false, message = friendlyError(t)) }
                onDone(false)
            }
        }
    }

    fun switchBranch(name: String) {
        val current = _state.value.project?.let { currentBranchName(it) }
        if (name == current) return
        mutate(
            data = buildJsonObject { put("currentBranch", name) },
            successMessage = "Текущая ветка: «$name»",
        )
    }

    fun rollbackTo(commitId: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                val result = repository.rollback(projectId, commitId)
                _state.update {
                    it.copy(busy = false, project = result.project, message = "Откат выполнен: ${result.commit.id}")
                }
                onDone(true)
            } catch (t: Throwable) {
                _state.update { it.copy(busy = false, message = friendlyError(t)) }
                onDone(false)
            }
        }
    }

    suspend fun downloadText(blobId: String): String = withContext(Dispatchers.IO) {
        repository.downloadBlob(projectId, blobId).toString(Charsets.UTF_8)
    }

    suspend fun downloadBytes(blobId: String): ByteArray = withContext(Dispatchers.IO) {
        repository.downloadBlob(projectId, blobId)
    }

    fun blobUrl(blobId: String): String = repository.blobUrl(projectId, blobId)

    // ---------------------------------------------------------- screenshots

    fun uploadScreenshot(uri: Uri, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val image = withContext(Dispatchers.IO) { readImage(uri) }
            if (image == null) {
                _state.update { it.copy(busy = false, message = "Не удалось прочитать изображение") }
                onDone(false)
                return@launch
            }
            try {
                val project = repository.uploadScreenshot(projectId, image.first, image.second, image.third)
                _state.update { it.copy(busy = false, project = project, message = "Скриншот добавлен") }
                onDone(true)
            } catch (t: Throwable) {
                _state.update { it.copy(busy = false, message = friendlyError(t)) }
                onDone(false)
            }
        }
    }

    fun deleteScreenshot(id: String) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                val project = repository.deleteScreenshot(projectId, id)
                _state.update { it.copy(busy = false, project = project, message = "Скриншот удалён") }
            } catch (t: Throwable) {
                _state.update { it.copy(busy = false, message = friendlyError(t)) }
            }
        }
    }

    // -------------------------------------------------------------- helpers

    private fun tasksData(tasks: List<Task>): JsonObject = buildJsonObject {
        put("tasks", buildJsonArray {
            tasks.forEach { task ->
                add(buildJsonObject {
                    put("title", task.title)
                    put("done", task.done)
                })
            }
        })
    }

    private fun mutate(data: JsonObject, successMessage: String? = null) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                val project = repository.patchProject(projectId, data = data)
                _state.update { it.copy(busy = false, project = project, message = successMessage) }
            } catch (t: Throwable) {
                _state.update { it.copy(busy = false, message = friendlyError(t)) }
            }
        }
    }

    private fun prepareUpload(uri: Uri, skipped: MutableList<String>): UploadFile? {
        return try {
            val resolver = getApplication<Application>().contentResolver
            val name = sanitizeName(displayName(resolver, uri) ?: "file_${System.currentTimeMillis()}")
            val mime = resolver.getType(uri) ?: mimeFromName(name)
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            if (bytes.isEmpty()) {
                skipped.add("Пропущен пустой файл: $name")
                return null
            }
            if (bytes.size > MAX_UPLOAD_BYTES) {
                skipped.add("Файл «$name» больше ${MAX_UPLOAD_BYTES / 1024 / 1024} МБ")
                return null
            }
            UploadFile(path = name, mime = mime, bytes = bytes)
        } catch (t: Throwable) {
            skipped.add("Не удалось прочитать файл: ${t.message ?: "ошибка"}")
            null
        }
    }

    private fun readImage(uri: Uri): Triple<String, String, ByteArray>? {
        return try {
            val resolver = getApplication<Application>().contentResolver
            val name = sanitizeName(displayName(resolver, uri) ?: "screenshot_${System.currentTimeMillis()}.png")
            val mime = resolver.getType(uri) ?: mimeFromName(name).ifBlank { "image/png" }
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) return null
            Triple(name, mime, bytes)
        } catch (_: Throwable) {
            null
        }
    }

    private fun displayName(resolver: ContentResolver, uri: Uri): String? {
        return try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        } catch (_: Throwable) {
            uri.lastPathSegment
        }
    }

    private fun sanitizeName(raw: String): String {
        var name = raw.replace('\\', '/').substringAfterLast('/').trim()
        name = name.replace(Regex("[\\u0000-\\u001f]"), "")
        if (name.isBlank() || name == "." || name == "..") name = "file"
        return name.take(180)
    }

    /**
     * В одной партии не может быть двух файлов с одинаковым путём:
     * второй молча перезаписал бы первый. Добавляем суффикс.
     */
    private fun uniqueUploadPath(file: UploadFile, used: MutableSet<String>): UploadFile {
        if (used.add(file.path)) return file
        val dot = file.path.lastIndexOf('.')
        val base = if (dot > 0) file.path.substring(0, dot) else file.path
        val ext = if (dot > 0) file.path.substring(dot) else ""
        var counter = 2
        while (true) {
            val candidate = "$base ($counter)$ext"
            if (used.add(candidate)) return file.copy(path = candidate)
            counter += 1
        }
    }

    private fun mimeFromName(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        "bmp" -> "image/bmp"
        "avif" -> "image/avif"
        "md", "markdown" -> "text/markdown"
        "txt", "log", "ini", "cfg", "conf", "properties" -> "text/plain"
        "json" -> "application/json"
        "xml" -> "application/xml"
        "html", "htm" -> "text/html"
        "css" -> "text/css"
        "js", "mjs" -> "text/javascript"
        "ts", "tsx", "jsx" -> "text/plain"
        "kt", "kts", "java", "py", "go", "rs", "c", "cpp", "h", "cs", "php", "rb", "swift" -> "text/plain"
        "yml", "yaml", "toml" -> "text/plain"
        "sh", "bat", "ps1" -> "text/plain"
        "sql", "csv" -> "text/plain"
        "pdf" -> "application/pdf"
        else -> "application/octet-stream"
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    private companion object {
        const val MAX_UPLOAD_BYTES = 100L * 1024L * 1024L
        const val MAX_IMAGE_BYTES = 25L * 1024L * 1024L
    }
}
