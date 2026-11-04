package com.projectlist.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.projectlist.mobile.security.BiometricAuth
import com.projectlist.mobile.security.findFragmentActivity
import com.projectlist.mobile.ui.components.ErrorBanner
import kotlinx.coroutines.launch

/**
 * Экран блокировки после холодного старта приложения. Пока пользователь не
 * подтвердил личность, содержимое приложения не отображается.
 */
@Composable
fun AppUnlockScreen(onUnlocked: () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }
    val available = remember(context) { activity != null && BiometricAuth.isAvailable(context) }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun authenticate() {
        val host = activity ?: return
        if (busy) return
        busy = true
        error = null
        val ok = BiometricAuth.confirm(
            activity = host,
            title = "Разблокировка ProjectList",
            subtitle = "Приложите палец к сканеру отпечатков",
        )
        busy = false
        if (ok) {
            onUnlocked()
        } else {
            error = "Отпечаток не подтверждён. Попробуйте ещё раз."
        }
    }

    LaunchedEffect(Unit) {
        if (available) authenticate()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .background(
                        MaterialTheme.colorScheme.primaryContainer,
                        RoundedCornerShape(26.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Fingerprint,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(46.dp),
                )
            }

            Spacer(Modifier.height(22.dp))
            Text(
                "Приложение заблокировано",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                if (available) {
                    "Чтобы открыть сохранённые проекты и ключ доступа, подтвердите личность отпечатком пальца."
                } else {
                    "Отпечаток пальца не настроен на этом устройстве. " +
                        "Можно открыть приложение без биометрии."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            if (error != null) {
                Spacer(Modifier.height(16.dp))
                ErrorBanner(error!!)
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    if (available) {
                        scope.launch { authenticate() }
                    } else {
                        onUnlocked()
                    }
                },
                enabled = !busy,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth(0.72f)
                    .height(52.dp),
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                } else {
                    Icon(
                        if (available) Icons.Filled.Fingerprint else Icons.Filled.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (available) "Разблокировать" else "Открыть приложение")
            }

            if (available && !busy) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { scope.launch { authenticate() } }) {
                    Text("Повторить")
                }
            }

            Spacer(Modifier.height(18.dp))
            Text(
                "Данные защищены хранилищем Android Keystore",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
