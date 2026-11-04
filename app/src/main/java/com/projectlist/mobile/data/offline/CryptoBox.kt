package com.projectlist.mobile.data.offline

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Шифрование ProjectList.
 *
 * Запись проекта: JSON {"iv": base64(12), "authTag": base64(16), "data": base64(ciphertext)}.
 * Блоб: iv(12) | authTag(16) | ciphertext.
 *
 * Java возвращает из Cipher.doFinal(ciphertext || tag), поэтому тег отделяется вручную.
 */
object CryptoBox {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128
    private const val IV_SIZE = 12
    private const val TAG_SIZE = 16

    private val random = SecureRandom()
    private val json = Json { ignoreUnknownKeys = true }

    /** Ключ из настроек: base64 (32 байта) или hex (64 символа). */
    fun parseKey(input: String): ByteArray {
        val value = input.trim()
            .removePrefix("ENCRYPTION_KEY=")
            .trim()
            .trim('"', '\'')
        require(value.isNotEmpty()) { "Ключ шифрования не задан" }

        val bytes = when {
            value.length == 64 && value.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' } ->
                runCatching { value.hexToBytes() }.getOrNull()

            else -> runCatching { Base64.getDecoder().decode(value) }.getOrNull()
                ?: runCatching { Base64.getUrlDecoder().decode(value) }.getOrNull()
        }

        requireNotNull(bytes) { "Ключ должен быть в base64 или hex" }
        require(bytes.size == 32) {
            "Ключ должен быть длиной 32 байта (сейчас ${bytes.size}). " +
                "Скопируйте значение ENCRYPTION_KEY из настроек ProjectList."
        }
        return bytes
    }

    fun keyFingerprint(key: ByteArray): String = sha256Hex(key).take(16)

    fun encryptRecord(plaintext: String, key: ByteArray): String {
        val iv = ByteArray(IV_SIZE).also(random::nextBytes)
        val packed = cipher(Cipher.ENCRYPT_MODE, key, iv).doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val ciphertext = packed.copyOfRange(0, packed.size - TAG_SIZE)
        val tag = packed.copyOfRange(packed.size - TAG_SIZE, packed.size)

        val payload = buildJsonObject {
            put("iv", Base64.getEncoder().encodeToString(iv))
            put("authTag", Base64.getEncoder().encodeToString(tag))
            put("data", Base64.getEncoder().encodeToString(ciphertext))
        }
        return Json.encodeToString(JsonObject.serializer(), payload)
    }

    fun decryptRecord(payload: String, key: ByteArray): String {
        val obj = json.parseToJsonElement(payload) as? JsonObject
            ?: throw IllegalArgumentException("Файл проекта повреждён: ожидался JSON-объект")
        val iv = decodeBase64(obj["iv"])
        val tag = decodeBase64(obj["authTag"])
        val data = decodeBase64(obj["data"])
        require(iv.size == IV_SIZE) { "Файл проекта повреждён: неверный IV" }
        require(tag.size == TAG_SIZE) { "Файл проекта повреждён: неверный тег" }
        val plain = decryptPacked(data, tag, key, iv)
        return plain.toString(Charsets.UTF_8)
    }

    fun encryptBlob(plaintext: ByteArray, key: ByteArray): ByteArray {
        val iv = ByteArray(IV_SIZE).also(random::nextBytes)
        val packed = cipher(Cipher.ENCRYPT_MODE, key, iv).doFinal(plaintext)
        val ciphertext = packed.copyOfRange(0, packed.size - TAG_SIZE)
        val tag = packed.copyOfRange(packed.size - TAG_SIZE, packed.size)
        return iv + tag + ciphertext
    }

    fun decryptBlob(packed: ByteArray, key: ByteArray): ByteArray {
        require(packed.size >= IV_SIZE + TAG_SIZE) { "Блоб повреждён: слишком короткий" }
        val iv = packed.copyOfRange(0, IV_SIZE)
        val tag = packed.copyOfRange(IV_SIZE, IV_SIZE + TAG_SIZE)
        val data = packed.copyOfRange(IV_SIZE + TAG_SIZE, packed.size)
        return decryptPacked(data, tag, key, iv)
    }

    private fun decryptPacked(data: ByteArray, tag: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = cipher(Cipher.DECRYPT_MODE, key, iv)
        cipher.update(data)
        return cipher.doFinal(tag)
    }

    private fun cipher(mode: Int, key: ByteArray, iv: ByteArray): Cipher {
        require(key.size == 32) { "Ключ AES-256 должен быть 32 байта" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        return cipher
    }

    private fun decodeBase64(element: kotlinx.serialization.json.JsonElement?): ByteArray {
        val raw = element?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Файл проекта повреждён: нет поля шифрования")
        return Base64.getDecoder().decode(raw)
    }
}
