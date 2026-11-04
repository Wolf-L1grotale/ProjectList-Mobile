package com.projectlist.mobile.data

import android.util.Base64
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

class ApiClient(private val session: SessionStore) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val reauthMutex = Mutex()

    // ---------------------------------------------------------------- auth

    suspend fun unlock(serverUrl: String, key: String): UnlockResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject { put("key", key) }
            .toString()
            .toRequestBody(JSON_MEDIA)
        val request = Request.Builder()
            .url(serverUrl.trimEnd('/') + "/api/auth/unlock")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw parseError(response.code, text)
            json.decodeFromString<UnlockResponse>(text)
        }
    }

    suspend fun sessionInfo(): SessionInfo = getJson("/api/auth/session")

    suspend fun logout() {
        runCatching { perform("/api/auth/logout", "POST", EMPTY_BODY).close() }
    }

    private suspend fun tryReauth(): Boolean = reauthMutex.withLock {
        val key = session.current.apiKey
        val server = session.current.serverUrl
        if (key.isNullOrBlank() || server.isBlank()) return@withLock false
        try {
            val result = unlock(server, key)
            if (result.token.isBlank()) return@withLock false
            session.updateToken(result.token, result.admin)
            true
        } catch (_: Throwable) {
            false
        }
    }

    // ------------------------------------------------------------ projects

    suspend fun projects(limit: Int = 200): List<Project> {
        val text = getText("/api/projects?paginate=1&limit=$limit&refresh=0")
        val element = json.parseToJsonElement(text)
        return if (element is JsonArray) {
            json.decodeFromJsonElement(element)
        } else {
            json.decodeFromJsonElement<ProjectsResponse>(element).items
        }
    }

    suspend fun project(id: String): Project = getJson("/api/projects/${encode(id)}")

    suspend fun createProject(name: String, description: String): String {
        val body = buildJsonObject {
            put("name", name)
            put("description", description)
        }
        val text = postText("/api/projects", body)
        val obj = json.parseToJsonElement(text).jsonObject
        return obj["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
    }

    suspend fun patchProject(id: String, body: JsonObject): Project {
        val text = sendText("/api/projects/${encode(id)}", "PATCH", body)
        return json.decodeFromString(text)
    }

    suspend fun deleteProject(id: String) {
        perform("/api/projects/${encode(id)}", "DELETE", null).close()
    }

    suspend fun uploadBatch(projectId: String, batch: String, files: List<UploadFile>) {
        val payload = buildJsonObject {
            put("files", buildJsonArray {
                files.forEach { file ->
                    add(buildJsonObject {
                        put("path", file.path)
                        put("mime", file.mime)
                        put("data", Base64.encodeToString(file.bytes, Base64.NO_WRAP))
                    })
                }
            })
        }
        perform(
            "/api/projects/${encode(projectId)}/files/batch?batch=${encode(batch)}",
            "POST",
            payload.toString().toRequestBody(JSON_MEDIA),
        ).close()
    }

    suspend fun uploadFile(projectId: String, batch: String, file: UploadFile) {
        // application/json нельзя отправлять сырым телом: express.json() съест поток.
        val media = if (file.mime == "application/json") {
            OCTET_MEDIA
        } else {
            file.mime.ifBlank { "application/octet-stream" }.toMediaTypeOrNull() ?: OCTET_MEDIA
        }
        val body = file.bytes.toRequestBody(media)
        perform(
            "/api/projects/${encode(projectId)}/files?path=${encode(file.path)}&batch=${encode(batch)}",
            "POST",
            body,
        ).close()
    }

    suspend fun cancelBatch(projectId: String, batch: String) {
        runCatching {
            perform(
                "/api/projects/${encode(projectId)}/files/batch/${encode(batch)}",
                "DELETE",
                null,
            ).close()
        }
    }

    suspend fun createCommit(
        projectId: String,
        batch: String?,
        message: String,
        branch: String? = null,
        version: String? = null,
        remove: List<String> = emptyList(),
    ): CommitResponse {
        val body = buildJsonObject {
            if (!batch.isNullOrBlank()) put("batch", batch)
            if (!branch.isNullOrBlank()) put("branch", branch)
            if (!version.isNullOrBlank()) put("version", version)
            if (remove.isNotEmpty()) {
                put("remove", buildJsonArray { remove.forEach { add(JsonPrimitive(it)) } })
            }
            put("message", message)
        }
        return json.decodeFromString(sendText("/api/projects/${encode(projectId)}/commits", "POST", body))
    }

    suspend fun createBranch(projectId: String, name: String, at: String? = null): ProjectResponse {
        val body = buildJsonObject {
            put("name", name)
            if (!at.isNullOrBlank()) put("at", at)
        }
        return json.decodeFromString(sendText("/api/projects/${encode(projectId)}/branches", "POST", body))
    }

    suspend fun merge(
        projectId: String,
        src: String,
        dst: String,
        message: String? = null,
    ): CommitResponse {
        val body = buildJsonObject {
            put("src", src)
            put("dst", dst)
            if (!message.isNullOrBlank()) put("message", message)
        }
        return json.decodeFromString(sendText("/api/projects/${encode(projectId)}/merge", "POST", body))
    }

    suspend fun rollback(projectId: String, commitId: String, message: String? = null): CommitResponse {
        val body = buildJsonObject {
            put("commit", commitId)
            if (!message.isNullOrBlank()) put("message", message)
        }
        return json.decodeFromString(sendText("/api/projects/${encode(projectId)}/rollback", "POST", body))
    }

    suspend fun blob(projectId: String, blobId: String, download: Boolean = false): ByteArray {
        val path = "/api/projects/${encode(projectId)}/blob/${encode(blobId)}" + if (download) "?download=1" else ""
        return perform(path, "GET", null).use { it.body?.bytes() ?: ByteArray(0) }
    }

    fun blobUrl(projectId: String, blobId: String): String =
        session.current.serverUrl.trimEnd('/') + "/api/projects/${encode(projectId)}/blob/${encode(blobId)}"

    suspend fun uploadScreenshot(
        projectId: String,
        name: String,
        mime: String,
        bytes: ByteArray,
    ): ScreenshotResponse {
        val media = (mime.ifBlank { "image/png" }).toMediaTypeOrNull()
        val body = bytes.toRequestBody(media)
        val text = perform(
            "/api/projects/${encode(projectId)}/screenshots?name=${encode(name)}",
            "POST",
            body,
        ).use { it.body?.string().orEmpty() }
        return json.decodeFromString(text)
    }

    suspend fun deleteScreenshot(projectId: String, screenshotId: String): ProjectResponse {
        val text = perform(
            "/api/projects/${encode(projectId)}/screenshots/${encode(screenshotId)}",
            "DELETE",
            null,
        ).use { it.body?.string().orEmpty() }
        return json.decodeFromString(text)
    }

    // ------------------------------------------------------- notifications

    suspend fun notifications(limit: Int = 100): NotificationsResponse =
        getJson("/api/system/notifications?limit=$limit")

    suspend fun markAllNotificationsRead() {
        perform("/api/system/notifications/read-all", "POST", EMPTY_BODY).close()
    }

    suspend fun markNotificationRead(id: String) {
        perform("/api/system/notifications/${encode(id)}/read", "POST", EMPTY_BODY).close()
    }

    suspend fun deleteNotification(id: String) {
        perform("/api/system/notifications/${encode(id)}", "DELETE", null).close()
    }

    suspend fun clearNotifications() {
        perform("/api/system/notifications", "DELETE", null).close()
    }

    // ------------------------------------------------------------- system

    suspend fun versionInfo(): VersionInfo = getJson("/api/system/version")

    suspend fun health(): HealthInfo = getJson("/api/system/health")

    suspend fun keyStatus(): KeyStatus = getJson("/api/system/key-status")

    suspend fun logs(limit: Int = 200): LogsResponse = getJson("/api/logs?limit=$limit")

    // ------------------------------------------------------------ helpers

    private suspend inline fun <reified T> getJson(path: String): T {
        val text = getText(path)
        return json.decodeFromString(text)
    }

    private suspend fun getText(path: String): String =
        perform(path, "GET", null).use { it.body?.string().orEmpty() }

    private suspend fun postText(path: String, body: JsonObject): String =
        sendText(path, "POST", body)

    private suspend fun sendText(path: String, method: String, body: JsonObject): String =
        perform(path, method, body.toString().toRequestBody(JSON_MEDIA))
            .use { it.body?.string().orEmpty() }

    private suspend fun perform(
        path: String,
        method: String,
        body: RequestBody?,
        allowReauth: Boolean = true,
    ): Response = withContext(Dispatchers.IO) {
        var response = client.newCall(buildRequest(path, method, body)).execute()

        if (response.code == 401 && allowReauth) {
            val key = session.current.apiKey
            if (!key.isNullOrBlank()) {
                response.close()
                if (tryReauth()) {
                    response = client.newCall(buildRequest(path, method, body)).execute()
                } else {
                    session.onAuthLost()
                    throw ApiException("Сессия истекла. Войдите снова.", 401, "Unauthorized")
                }
            }
        }

        if (!response.isSuccessful) {
            val status = response.code
            val text = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
            response.close()
            if (status == 401) session.onAuthLost()
            throw parseError(status, text)
        }

        response
    }

    private fun buildRequest(path: String, method: String, body: RequestBody?): Request {
        val state = session.current
        if (state.serverUrl.isBlank()) {
            throw ApiException("Адрес сервера не задан", 0, "no-server")
        }
        val url = if (path.startsWith("http://") || path.startsWith("https://")) {
            path
        } else {
            state.serverUrl.trimEnd('/') + path
        }

        val builder = Request.Builder().url(url)
        when (method) {
            "GET" -> builder.get()
            "DELETE" -> if (body == null) builder.delete() else builder.method("DELETE", body)
            else -> builder.method(method, body ?: EMPTY_BODY)
        }
        if (!state.token.isNullOrBlank()) {
            builder.header("X-Session-Token", state.token)
        } else if (!state.apiKey.isNullOrBlank()) {
            builder.header("X-API-Key", state.apiKey)
        }
        builder.header("Accept", "application/json, */*")
        return builder.build()
    }

    private fun parseError(status: Int, text: String): ApiException {
        var code: String? = null
        var message: String? = null
        var retry: Int? = null
        try {
            val obj = json.parseToJsonElement(text).jsonObject
            code = obj["error"]?.jsonPrimitive?.contentOrNull
            message = obj["message"]?.jsonPrimitive?.contentOrNull
            retry = obj["retryAfterSec"]?.jsonPrimitive?.intOrNull
        } catch (_: Throwable) {
            // Тело не JSON — используем статус.
        }
        val human = message?.takeIf { it.isNotBlank() } ?: when (code) {
            "bad-key" -> "Неверный ключ доступа"
            "bad-ticket" -> "Сессия недействительна"
            "Unauthorized" -> "Требуется авторизация"
            "TooManyAttempts" -> "Слишком много попыток. Подождите и повторите."
            "admin-ticket-required" -> "Требуется подтверждение мастер-ключа"
            "Not found" -> "Не найдено на сервере"
            "key-or-ticket-required" -> "Укажите ключ доступа"
            else -> if (status > 0) "Ошибка сервера ($status)" else "Ошибка"
        }
        val withRetry = if (retry != null && status == 429) {
            "$human Повторите через $retry с."
        } else {
            human
        }
        return ApiException(withRetry, status, code, retry)
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaTypeOrNull()
        val OCTET_MEDIA = "application/octet-stream".toMediaTypeOrNull()
        val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody(null, 0, 0)
    }
}
