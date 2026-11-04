package com.projectlist.mobile.security

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Единая точка для подтверждения действий отпечатком пальца.
 *
 * Если биометрия на устройстве не настроена, [confirm] возвращает true:
 * вызывающий экран уже показал обычный диалог подтверждения, и блокировать
 * работу на устройствах без сканера нельзя.
 */
object BiometricAuth {

    private const val AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_WEAK

    fun isAvailable(context: Context): Boolean = runCatching {
        BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }.getOrDefault(false)

    suspend fun confirm(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        negativeButton: String = "Отмена",
    ): Boolean = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { continuation ->
            val executor = ContextCompat.getMainExecutor(activity)
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult,
                ) {
                    if (continuation.isActive) continuation.resume(true)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (continuation.isActive) continuation.resume(false)
                }

                // Неверный отпечаток: системный диалог остаётся открытым,
                // пользователь может приложить палец ещё раз.
                override fun onAuthenticationFailed() = Unit
            }

            val prompt = BiometricPrompt(activity, executor, callback)
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setNegativeButtonText(negativeButton)
                .setAllowedAuthenticators(AUTHENTICATORS)
                .setConfirmationRequired(false)
                .build()

            continuation.invokeOnCancellation { runCatching { prompt.cancelAuthentication() } }
            try {
                prompt.authenticate(info)
            } catch (t: Throwable) {
                // Промпт может не открыться, например если Activity уже
                // остановлена. Не роняем приложение: считаем, что
                // подтверждение не получено.
                if (continuation.isActive) continuation.resume(false)
            }
        }
    }
}

/** Возвращает Activity, к которой можно привязать BiometricPrompt. */
fun Context.findFragmentActivity(): FragmentActivity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is FragmentActivity) return current
        current = current.baseContext
    }
    return current as? FragmentActivity
}

/**
 * Обёртка для экранов: подтверждает удаление отпечатком, если он доступен.
 */
class BiometricGuard(
    private val context: Context,
    private val activity: FragmentActivity?,
) {
    val available: Boolean get() = activity != null && BiometricAuth.isAvailable(context)

    suspend fun confirm(title: String, subtitle: String): Boolean {
        val host = activity ?: return true
        if (!BiometricAuth.isAvailable(context)) return true
        return try {
            BiometricAuth.confirm(host, title, subtitle)
        } catch (t: Throwable) {
            false
        }
    }
}

@Composable
fun rememberBiometricGuard(): BiometricGuard {
    val context = LocalContext.current
    return remember(context) {
        BiometricGuard(context.applicationContext, context.findFragmentActivity())
    }
}
