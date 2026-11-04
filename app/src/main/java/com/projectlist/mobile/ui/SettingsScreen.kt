package com.projectlist.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import com.projectlist.mobile.data.SessionState
import com.projectlist.mobile.security.rememberBiometricGuard
import com.projectlist.mobile.ui.components.ErrorBanner
import com.projectlist.mobile.ui.components.KeyValueRow
import com.projectlist.mobile.ui.components.SectionCard
import com.projectlist.mobile.ui.components.formatDate
import com.projectlist.mobile.ui.components.formatUptime
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    session: SessionState,
    offlineFingerprint: String?,
    onRefresh: () -> Unit,
    onLoadLogs: () -> Unit,
    onLogout: () -> Unit,
    onForgetDevice: () -> Unit,
    onToggleOffline: (Boolean) -> Unit,
    onOpenOfflineSetup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmLogout by remember { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }
    var showLogs by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val biometric = rememberBiometricGuard()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 100.dp)),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.error != null) {
            ErrorBanner(state.error, onRetry = onRefresh)
        }

        SectionCard(
            title = "Подключение",
            subtitle = if (session.admin) "Полные права администратора" else "Обычный доступ",
            action = {
                Icon(
                    Icons.Filled.Dns,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            },
        ) {
            KeyValueRow(
                "Сервер",
                session.serverUrl.ifBlank { session.pairedServer.ifBlank { "—" } },
            )
            KeyValueRow(
                "Пользователь",
                session.displayName ?: session.pairedName.takeIf { it.isNotBlank() }
                    ?: if (session.admin) "Администратор" else "Гость",
            )
            KeyValueRow("Ключ доступа", if (session.apiKey.isNullOrBlank()) "—" else "••••••••")
            KeyValueRow(
                "Сессия",
                when {
                    session.loggedIn -> "Активна"
                    session.paired -> "Автономная"
                    else -> "Нет"
                },
            )
        }

        if (session.paired) {
            SectionCard(
                title = "Сопряжённое устройство",
                subtitle = "Ключ получен с доверенного АРМ и хранится в Android Keystore",
                action = {
                    Icon(
                        Icons.Filled.Shield,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(20.dp),
                    )
                },
            ) {
                KeyValueRow("Компьютер", session.pairedServer.ifBlank { "—" })
                KeyValueRow("Имя устройства", session.pairedName.ifBlank { "—" })
                KeyValueRow(
                    "Ключ шифрования",
                    offlineFingerprint?.let { "…$it" } ?: "Сохранён",
                )
                KeyValueRow(
                    "Подключено",
                    if (session.pairedAt > 0) {
                        formatDate(Instant.ofEpochMilli(session.pairedAt).toString(), withTime = true)
                    } else {
                        "—"
                    },
                )
                Spacer(Modifier.padding(top = 6.dp))
                Text(
                    "Компьютер нужен только для нового сопряжения. Повседневная работа " +
                        "идёт напрямую с MinIO по SMB.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.padding(top = 8.dp))
                OutlinedButton(
                    onClick = { confirmForget = true },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Забыть устройство")
                }
            }
        }

        SectionCard(
            title = "Автономный доступ",
            subtitle = when {
                session.offlineActive -> "Работает: данные читаются с SMB напрямую"
                session.offline.enabled -> "Включён, используется без сервера"
                else -> "Выключен"
            },
            action = {
                Icon(
                    Icons.Filled.CloudOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(20.dp),
                )
            },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Прямой доступ к MinIO",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        "SMB вместо сервера ProjectList",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = session.offline.enabled,
                    onCheckedChange = onToggleOffline,
                )
            }
            Spacer(Modifier.padding(top = 6.dp))
            KeyValueRow(
                "SMB",
                if (session.offline.configured) session.offline.describe() else "Не настроено",
            )
            KeyValueRow("Bucket", session.offline.bucket.ifBlank { "—" })
            KeyValueRow(
                "Ключ шифрования",
                offlineFingerprint?.let { "…$it" }
                    ?: if (session.offline.encryptionKey.isBlank()) "Не задан" else "Не проверен",
            )
            Spacer(Modifier.padding(top = 6.dp))
            Text(
                "Запись возможна только при остановленных ProjectList и MinIO. " +
                    "Загрузка новых файлов и скриншотов в автономном режиме недоступна.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.padding(top = 8.dp))
            OutlinedButton(
                onClick = onOpenOfflineSetup,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Настроить SMB и ключ")
            }
        }

        SectionCard(
            title = "Сервер ProjectList",
            subtitle = state.version?.let { "${it.name} v${it.version}" },
            action = {
                TextButton(onClick = onRefresh) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Обновить")
                }
            },
        ) {
            val health = state.health
            val online = session.offlineActive || health?.minio == "connected"
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Storage,
                    contentDescription = null,
                    tint = if (online) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (session.offlineActive) {
                        "Хранилище доступно по SMB"
                    } else if (health == null) {
                        "Состояние неизвестно"
                    } else if (online) {
                        "Хранилище подключено"
                    } else {
                        "Хранилище недоступно"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.padding(top = 6.dp))
            KeyValueRow("Время сервера", formatDate(health?.serverTime, withTime = true))
            KeyValueRow("Версия", state.version?.version?.takeIf { it.isNotBlank() } ?: "—")
            KeyValueRow("Аптайм", state.version?.let { formatUptime(it.uptime) } ?: "—")
            KeyValueRow("Макс. файл", state.version?.let { "${it.maxUploadMb} МБ" } ?: "—")
            KeyValueRow("Файлов в батче", state.version?.let { it.maxBatchFiles.toString() } ?: "—")
        }

        SectionCard(
            title = "Ключ шифрования",
            action = {
                Icon(
                    Icons.Filled.Key,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(20.dp),
                )
            },
        ) {
            val key = state.keyStatus
            KeyValueRow("Доступен", if (key?.available == true) "Да" else "Нет")
            KeyValueRow("Заблокирован", if (key?.locked == true) "Да" else "Нет")
            KeyValueRow("Устройство", key?.deviceId ?: "—")
            KeyValueRow("Отпечаток", key?.fingerprint?.let { "…$it" } ?: "—")
        }

        SectionCard(
            title = "Журнал событий",
            subtitle = "Последние записи сервера",
            action = {
                TextButton(onClick = {
                    showLogs = !showLogs
                    if (showLogs && state.logs.isEmpty()) onLoadLogs()
                }) {
                    Text(if (showLogs) "Скрыть" else "Показать")
                }
            },
        ) {
            if (!showLogs) {
                Text(
                    "Журнал помогает понять, что происходит на сервере: ошибки хранилища, загрузки файлов, авторизации.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                if (state.logsLoading) {
                    Text("Загружаем журнал…", style = MaterialTheme.typography.bodySmall)
                } else if (state.logsError != null) {
                    Text(
                        state.logsError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (state.logs.isEmpty()) {
                    Text(
                        "Записей нет.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.logs.take(60).forEach { entry ->
                            Row(Modifier.fillMaxWidth()) {
                                Text(
                                    entry.level.uppercase(),
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp),
                                    color = when (entry.level) {
                                        "error" -> MaterialTheme.colorScheme.error
                                        "warn" -> MaterialTheme.colorScheme.secondary
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.width(48.dp),
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        entry.message,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Text(
                                        "${formatDate(entry.t, withTime = true)} · ${entry.category}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    if (state.logs.size > 60) {
                        Spacer(Modifier.padding(top = 4.dp))
                        Text(
                            "Показаны последние 60 из ${state.logs.size}.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        SectionCard(title = "О приложении") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.BugReport,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "ProjectList Mobile 1.0.0",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.padding(top = 6.dp))
            Text(
                "Клиент для системы учёта проектов ProjectList. Работает с вашим сервером в локальной сети или напрямую с MinIO по SMB.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OutlinedButton(
            onClick = { confirmLogout = true },
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Выйти из аккаунта")
        }

        Button(
            onClick = onRefresh,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Обновить данные")
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Выйти?") },
            text = {
                Text(
                    "Сессия будет завершена, а автономный режим выключен. " +
                        "Сопряжение с компьютером и настройки SMB сохранятся — войти снова можно без запроса.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    onLogout()
                }) {
                    Text("Выйти", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmLogout = false }) { Text("Отмена") }
            },
            shape = RoundedCornerShape(20.dp),
        )
    }

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Забыть устройство?") },
            text = {
                Text(
                    "С телефона будет удалён ключ шифрования, полученный от компьютера. " +
                        "Для дальнейшей работы потребуется новое сопряжение и подтверждение мастер-ключом FIDO.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmForget = false
                    scope.launch {
                        if (
                            biometric.confirm(
                                title = "Забыть устройство?",
                                subtitle = "Подтвердите отпечатком пальца",
                            )
                        ) {
                            onForgetDevice()
                        }
                    }
                }) {
                    Text("Забыть", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmForget = false }) { Text("Отмена") }
            },
            shape = RoundedCornerShape(20.dp),
        )
    }
}
