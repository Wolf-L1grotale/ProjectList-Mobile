package com.projectlist.mobile.data

import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Подключение нового устройства к доверенному АРМ ProjectList.
 *
 * Телефон не знает ключ шифрования (DEK) и не может прочитать его из MinIO.
 * Он генерирует одноразовый 32-байтовый ключ, отправляет запрос на компьютер
 * в локальной сети, пользователь подтверждает его мастер-ключом FIDO2/WebAuthn,
 * после чего компьютер возвращает DEK, зашифрованный этим одноразовым ключом.
 * Расшифрованный DEK сохраняется в Android Keystore и дальше используется для
 * прямой работы с данными MinIO по SMB — без запущенного ProjectList.
 */
class PairingClient {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    data class Session(
        val serverUrl: String,
        val deviceId: String,
        val name: String,
        val ephemKey: ByteArray,
        val requestId: String,
        val expiresAt: Long,
    )

    /** Отправляет запрос на подключение. Ключ DEK ещё не получен. */
    suspend fun request(serverUrl: String, deviceId: String, name: String): Session =
        withContext(Dispatchers.IO) {
            val ephem = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val payload = buildJsonObject {
                put("deviceId", deviceId)
                put("name", name)
                put("ephemKey", encode(ephem))
            }
            val response = post(serverUrl, "/api/system/pair/request", payload)
            if (!response.ok) {
                throw response.toError(forRequest = true)
            }
            val obj = runCatching { json.parseToJsonElement(response.text).jsonObject }
                .getOrElse { throw ApiException("Компьютер вернул неожиданный ответ", response.status, "pair-bad-response") }
            val requestId = obj["requestId"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (requestId.isEmpty()) {
                throw ApiException("Компьютер не вернул идентификатор запроса", response.status, "pair-no-id")
            }
            val expiresAt = obj["expiresAt"]?.jsonPrimitive?.contentOrNull
                ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
                ?: (System.currentTimeMillis() + DEFAULT_TTL_MS)
            Session(serverUrl, deviceId, name, ephem, requestId, expiresAt)
        }

    /**
     * Ждёт, пока на компьютере запрос подтвердят мастер-ключом FIDO, и забирает
     * обёрнутый DEK. Пока одобрения нет, сервер отвечает 404 — это нормально.
     */
    suspend fun awaitApproval(
        session: Session,
        onTick: suspend (secondsLeft: Long) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        while (true) {
            val left = ((session.expiresAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
            if (left <= 0) {
                throw ApiException(
                    "Время ожидания истекло. Запросите подключение заново.",
                    410,
                    "pair-expired",
                )
            }

            val response = post(
                session.serverUrl,
                "/api/system/pair/claim",
                buildJsonObject { put("requestId", session.requestId) },
            )
            if (response.ok) {
                val wrapped = runCatching {
                    json.parseToJsonElement(response.text).jsonObject["wrapped"]?.jsonObject
                }.getOrNull() ?: throw ApiException(
                    "Компьютер не передал ключ шифрования",
                    response.status,
                    "pair-no-wrapped",
                )
                return@withContext decryptWrapped(wrapped, session.ephemKey)
            }

            when (response.status) {
                404 -> Unit // запрос ещё не одобрен
                410 -> throw ApiException(
                    "Время ожидания истекло. Запросите подключение заново.",
                    410,
                    "pair-expired",
                )
                else -> throw response.toError(forRequest = false)
            }

            onTick(((session.expiresAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0))
            delay(POLL_INTERVAL_MS)
        }
        throw ApiException("Не удалось получить ключ", 0, "pair-failed")
    }

    private fun decryptWrapped(wrapped: JsonObject, ephem: ByteArray): String {
        val iv = decodeField(wrapped, "iv")
        val tag = decodeField(wrapped, "authTag")
        val data = decodeField(wrapped, "data")
        if (iv.size != 12 || tag.size != 16) {
            throw ApiException("Компьютер передал повреждённый ключ", 0, "pair-bad-wrapped")
        }
        val plaintext = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(ephem, "AES"),
                GCMParameterSpec(128, iv),
            )
            cipher.doFinal(data + tag)
        } catch (t: Throwable) {
            throw ApiException(
                "Не удалось расшифровать ключ. Запросите подключение заново.",
                0,
                "pair-decrypt-failed",
            )
        }
        val encoded = plaintext.toString(Charsets.UTF_8).trim()
        val raw = runCatching { Base64.getDecoder().decode(encoded) }.getOrElse {
            throw ApiException("Компьютер передал ключ в неизвестном формате", 0, "pair-bad-key")
        }
        if (raw.size != 32) {
            throw ApiException(
                "Компьютер передал ключ неверной длины (${raw.size} байт)",
                0,
                "pair-bad-key",
            )
        }
        return encode(raw)
    }

    private fun decodeField(obj: JsonObject, name: String): ByteArray {
        val value = obj[name]?.jsonPrimitive?.contentOrNull
            ?: throw ApiException("В ответе компьютера нет поля «$name»", 0, "pair-bad-wrapped")
        return runCatching { Base64.getDecoder().decode(value) }.getOrElse {
            throw ApiException("В ответе компьютера повреждено поле «$name»", 0, "pair-bad-wrapped")
        }
    }

    private fun post(serverUrl: String, path: String, body: JsonObject): RawResponse {
        val target = serverUrl.trimEnd('/') + path
        val request = Request.Builder()
            .url(target)
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .header("Accept", "application/json")
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                RawResponse(response.code, response.body?.string().orEmpty())
            }
        } catch (t: Throwable) {
            val address = serverUrl.trimEnd('/')
            val hint = when (t) {
                is java.net.SocketTimeoutException ->
                    "Компьютер $address не ответил вовремя."
                is java.net.UnknownHostException ->
                    "Компьютер с адресом $address не найден."
                is java.net.ConnectException ->
                    "Не удалось подключиться к $address. " +
                        "Проверьте, что ProjectList запущен, порт открыт, " +
                        "а Windows Firewall разрешает входящие подключения."
                is javax.net.ssl.SSLException ->
                    "Ошибка защищённого соединения с $address. " +
                        "Проверьте, использует ли ProjectList HTTPS, " +
                        "и выберите правильный протокол в настройках адреса."
                else ->
                    "Не удалось связаться с $address: ${t.message ?: t.javaClass.simpleName}"
            }
            throw ApiException(
                hint,
                0,
                "pair-network",
            )
        }
    }

    private data class RawResponse(val status: Int, val text: String) {
        val ok: Boolean get() = status in 200..299
    }

    private fun RawResponse.toError(forRequest: Boolean): ApiException {
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
        val code = obj?.get("error")?.jsonPrimitive?.contentOrNull
        val message = obj?.get("message")?.jsonPrimitive?.contentOrNull
        val human = when {
            !message.isNullOrBlank() -> message
            code == "lan-only" -> "Подключение новых устройств разрешено только в локальной сети."
            code == "subnet-mismatch" ->
                "Телефон и компьютер должны быть в одной подсети."
            code == "invalid-request" -> "Компьютер отклонил параметры запроса."
            code == "not-approved" -> "Запрос ещё не подтверждён на компьютере."
            code == "expired" -> "Время ожидания истекло. Запросите подключение заново."
            code == "key-unavailable" ->
                "На компьютере недоступен ключ шифрования — подтвердить подключение нельзя."
            forRequest && status == 404 ->
                "На компьютере не найден обработчик подключения устройств. Обновите ProjectList."
            status == 403 -> "Компьютер запретил подключение из этой сети."
            else -> "Компьютер отклонил запрос ($status)"
        }
        return ApiException(human, status, code)
    }

    private companion object {
        const val POLL_INTERVAL_MS = 1_500L
        const val DEFAULT_TTL_MS = 2 * 60 * 1000L
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaTypeOrNull()

        fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    }
}
