package com.projectlist.mobile.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.projectlist.mobile.data.offline.OfflineConfig
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

data class SessionState(
    val serverUrl: String = "",
    val token: String? = null,
    val apiKey: String? = null,
    val admin: Boolean = false,
    val displayName: String? = null,
    val offline: OfflineConfig = OfflineConfig(),
    /** Ключ шифрования ProjectList (DEK), полученный при сопряжении с АРМ. */
    val pairedKey: String? = null,
    val pairedServer: String = "",
    val pairedDeviceId: String = "",
    val pairedName: String = "",
    val pairedAt: Long = 0L,
) {
    val loggedIn: Boolean get() = !serverUrl.isBlank() && !token.isNullOrBlank()

    /** Настройки автономного режима заполнены. */
    val offlineReady: Boolean get() = offline.enabled && offline.configured

    /** Работаем напрямую с диском: сессии сервера нет, но автономный доступ настроен. */
    val offlineActive: Boolean get() = offlineReady && !loggedIn

    /** Устройство уже получило ключ от доверенного АРМ и работает без него. */
    val paired: Boolean get() = !pairedKey.isNullOrBlank()

    val hasAccess: Boolean get() = loggedIn || offlineActive
}

/**
 * Хранит адрес сервера, сессию и настройки автономного доступа. Секреты лежат
 * в EncryptedSharedPreferences, при недоступности шифрования используется обычное
 * хранилище приложения.
 */
class SessionStore(context: Context) {

    private val prefs: SharedPreferences = createPrefs(context.applicationContext)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val _state = MutableStateFlow(readState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    val current: SessionState get() = _state.value

    fun saveLogin(serverUrl: String, token: String, apiKey: String, admin: Boolean, displayName: String?) {
        prefs.edit()
            .putString(KEY_SERVER, serverUrl)
            .putString(KEY_TOKEN, token)
            .putString(KEY_API_KEY, apiKey)
            .putBoolean(KEY_ADMIN, admin)
            .putString(KEY_NAME, displayName)
            .apply()
        _state.value = _state.value.copy(
            serverUrl = serverUrl,
            token = token,
            apiKey = apiKey,
            admin = admin,
            displayName = displayName,
        )
    }

    fun updateToken(token: String, admin: Boolean) {
        val s = _state.value
        prefs.edit()
            .putString(KEY_TOKEN, token)
            .putBoolean(KEY_ADMIN, admin)
            .apply()
        _state.value = s.copy(token = token, admin = admin)
    }

    /** Сессия истекла и автоматически восстановить её не удалось. */
    fun onAuthLost() {
        val s = _state.value
        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_API_KEY)
            .putBoolean(KEY_ADMIN, false)
            .apply()
        _state.value = s.copy(token = null, apiKey = null, admin = false, displayName = null)
    }

    fun logout() {
        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_API_KEY)
            .putBoolean(KEY_ADMIN, false)
            .putString(KEY_NAME, null)
            .apply()
        // Сопряжение с компьютером сохраняется: после выхода вход в автономном
        // режиме снова доступен, новый запрос на подключение не нужен.
        _state.value = _state.value.copy(
            token = null,
            apiKey = null,
            admin = false,
            displayName = null,
        )
    }

    /** Сохраняет настройки SMB/ключа. Они остаются и после выхода из серверной сессии. */
    fun saveOffline(config: OfflineConfig) {
        val pairedKey = _state.value.pairedKey
        val effective = if (config.encryptionKey.isBlank() && !pairedKey.isNullOrBlank()) {
            config.copy(encryptionKey = pairedKey)
        } else {
            config
        }
        prefs.edit()
            .putString(KEY_OFFLINE, json.encodeToString(OfflineConfig.serializer(), effective))
            .apply()
        _state.value = _state.value.copy(offline = effective)
    }

    fun setOfflineEnabled(enabled: Boolean) {
        saveOffline(_state.value.offline.copy(enabled = enabled))
    }

    // ------------------------------------------------------------- pairing

    /** Стабильный идентификатор установки приложения. */
    fun deviceId(): String {
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val created = UUID.randomUUID().toString().replace("-", "")
        prefs.edit().putString(KEY_DEVICE_ID, created).apply()
        return created
    }

    /**
     * Сохраняет ключ, полученный от доверенного АРМ. Ключ лежит в
     * EncryptedSharedPreferences (Android Keystore) — аналог DPAPI/TPM на ПК.
     */
    fun savePairing(
        serverUrl: String,
        deviceId: String,
        name: String,
        encryptionKey: String,
    ) {
        val previous = _state.value.offline
        val offline = previous.copy(encryptionKey = encryptionKey)
            .let { it.copy(enabled = previous.enabled || it.configured) }
        val pairedAt = System.currentTimeMillis()
        prefs.edit()
            .putString(KEY_PAIRED_KEY, encryptionKey)
            .putString(KEY_PAIRED_SERVER, serverUrl)
            .putString(KEY_PAIRED_DEVICE, deviceId)
            .putString(KEY_PAIRED_NAME, name)
            .putLong(KEY_PAIRED_AT, pairedAt)
            .putString(KEY_OFFLINE, json.encodeToString(OfflineConfig.serializer(), offline))
            .apply()
        _state.value = _state.value.copy(
            pairedKey = encryptionKey,
            pairedServer = serverUrl,
            pairedDeviceId = deviceId,
            pairedName = name,
            pairedAt = pairedAt,
            offline = offline,
        )
    }

    /** Забывает сопряжение и удаляет полученный ключ с устройства. */
    fun clearPairing() {
        val offline = _state.value.offline.copy(
            encryptionKey = "",
            enabled = false,
        )
        prefs.edit()
            .remove(KEY_PAIRED_KEY)
            .remove(KEY_PAIRED_SERVER)
            .remove(KEY_PAIRED_DEVICE)
            .remove(KEY_PAIRED_NAME)
            .remove(KEY_PAIRED_AT)
            .putString(KEY_OFFLINE, json.encodeToString(OfflineConfig.serializer(), offline))
            .apply()
        _state.value = _state.value.copy(
            pairedKey = null,
            pairedServer = "",
            pairedDeviceId = "",
            pairedName = "",
            pairedAt = 0L,
            offline = offline,
        )
    }

    private fun readState(): SessionState {
        val offline = readOffline()
        val pairedKey = prefs.getString(KEY_PAIRED_KEY, null)
        // Настройки SMB могли быть сохранены до появления сопряжения.
        val effectiveOffline = if (offline.encryptionKey.isBlank() && !pairedKey.isNullOrBlank()) {
            offline.copy(encryptionKey = pairedKey)
        } else {
            offline
        }
        return SessionState(
            serverUrl = prefs.getString(KEY_SERVER, "").orEmpty(),
            token = prefs.getString(KEY_TOKEN, null),
            apiKey = prefs.getString(KEY_API_KEY, null),
            admin = prefs.getBoolean(KEY_ADMIN, false),
            displayName = prefs.getString(KEY_NAME, null),
            offline = effectiveOffline,
            pairedKey = pairedKey,
            pairedServer = prefs.getString(KEY_PAIRED_SERVER, "").orEmpty(),
            pairedDeviceId = prefs.getString(KEY_PAIRED_DEVICE, "").orEmpty(),
            pairedName = prefs.getString(KEY_PAIRED_NAME, "").orEmpty(),
            pairedAt = prefs.getLong(KEY_PAIRED_AT, 0L),
        )
    }

    private fun readOffline(): OfflineConfig {
        val raw = prefs.getString(KEY_OFFLINE, null) ?: return OfflineConfig()
        return runCatching { json.decodeFromString(OfflineConfig.serializer(), raw) }
            .getOrElse { OfflineConfig() }
    }

    private companion object {
        const val KEY_SERVER = "server_url"
        const val KEY_TOKEN = "session_token"
        const val KEY_API_KEY = "api_key"
        const val KEY_ADMIN = "admin"
        const val KEY_NAME = "display_name"
        const val KEY_OFFLINE = "offline_config"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_PAIRED_KEY = "paired_key"
        const val KEY_PAIRED_SERVER = "paired_server"
        const val KEY_PAIRED_DEVICE = "paired_device_id"
        const val KEY_PAIRED_NAME = "paired_device_name"
        const val KEY_PAIRED_AT = "paired_at"

        fun createPrefs(context: Context): SharedPreferences = try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "pl_session_secure",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (_: Throwable) {
            context.getSharedPreferences("pl_session_fallback", Context.MODE_PRIVATE)
        }
    }
}
