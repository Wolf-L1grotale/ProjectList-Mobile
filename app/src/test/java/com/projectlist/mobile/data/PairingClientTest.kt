package com.projectlist.mobile.data

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Проверяет протокол сопряжения: сервер оборачивает DEK одноразовым ключом
 * устройства через AES-256-GCM, телефон должен его расшифровать и сохранить.
 */
class PairingClientTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decryptsWrappedDataKeyFromTrustedPc() = runBlocking {
        val dek = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val dekBase64 = Base64.getEncoder().encodeToString(dek)

        var ephemKey: ByteArray? = null
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/api/system/pair/request") -> {
                        val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
                        ephemKey = Base64.getDecoder().decode(
                            body["ephemKey"]?.jsonPrimitive?.contentOrNull
                                ?: error("нет ephemKey"),
                        )
                        val expires = java.time.Instant.now().plusSeconds(120).toString()
                        MockResponse().jsonResponse(
                            """{"ok":true,"requestId":"req-1","expiresAt":"$expires"}""",
                        )
                    }

                    path.startsWith("/api/system/pair/claim") -> {
                        val key = ephemKey ?: error("сначала должен быть запрос")
                        MockResponse().jsonResponse(
                            """{"ok":true,"wrapped":${wrap(dekBase64, key)}}""",
                        )
                    }

                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()

        try {
            val url = server.url("/").toString().trimEnd('/')
            val client = PairingClient()
            val request = client.request(url, "device-1", "Тестовый телефон")
            val received = client.awaitApproval(request) { }

            assertEquals(dekBase64, received)
            assertEquals(32, Base64.getDecoder().decode(received).size)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun waitsUntilRequestIsApproved() = runBlocking {
        val dek = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val dekBase64 = Base64.getEncoder().encodeToString(dek)
        var ephemKey: ByteArray? = null
        var claims = 0

        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/api/system/pair/request") -> {
                        val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
                        ephemKey = Base64.getDecoder().decode(
                            body["ephemKey"]?.jsonPrimitive?.contentOrNull
                                ?: error("нет ephemKey"),
                        )
                        val expires = java.time.Instant.now().plusSeconds(120).toString()
                        MockResponse().jsonResponse(
                            """{"ok":true,"requestId":"req-2","expiresAt":"$expires"}""",
                        )
                    }

                    path.startsWith("/api/system/pair/claim") -> {
                        claims += 1
                        if (claims == 1) {
                            MockResponse().setResponseCode(404)
                                .jsonResponse("""{"error":"not-approved"}""")
                        } else {
                            val key = ephemKey ?: error("сначала должен быть запрос")
                            MockResponse().jsonResponse(
                                """{"ok":true,"wrapped":${wrap(dekBase64, key)}}""",
                            )
                        }
                    }

                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()

        try {
            val url = server.url("/").toString().trimEnd('/')
            val client = PairingClient()
            val request = client.request(url, "device-2", "Телефон 2")
            val updates = mutableListOf<Long>()
            val received = client.awaitApproval(request) { updates += it }

            assertEquals(dekBase64, received)
            assertEquals(2, claims)
            assertTrue(updates.isNotEmpty())
        } finally {
            server.shutdown()
        }
    }

    private fun wrap(plaintext: String, key: ByteArray): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val packed = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val data = packed.copyOfRange(0, packed.size - 16)
        val tag = packed.copyOfRange(packed.size - 16, packed.size)
        fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
        return """{"iv":"${b64(iv)}","authTag":"${b64(tag)}","data":"${b64(data)}"}"""
    }

    private fun MockResponse.jsonResponse(body: String): MockResponse =
        addHeader("Content-Type", "application/json; charset=utf-8").setBody(body)
}
