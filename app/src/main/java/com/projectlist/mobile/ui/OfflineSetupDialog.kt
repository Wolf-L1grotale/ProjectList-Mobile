package com.projectlist.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.projectlist.mobile.AppContainer
import com.projectlist.mobile.data.friendlyError
import com.projectlist.mobile.data.offline.OfflineConfig
import kotlinx.coroutines.launch

/**
 * Настройка прямого доступа к данным MinIO по SMB. Нужна, чтобы телефон
 * работал с проектами, когда ProjectList и MinIO на компьютере остановлены.
 */
@Composable
fun OfflineSetupDialog(
    container: AppContainer,
    onDismiss: () -> Unit,
    onSaved: (OfflineConfig) -> Unit = {},
) {
    val sessionState = container.session.current
    val initial = sessionState.offline
    val paired = sessionState.paired
    var host by remember { mutableStateOf(initial.host) }
    var port by remember { mutableStateOf(initial.port.toString()) }
    var share by remember { mutableStateOf(initial.share) }
    var basePath by remember { mutableStateOf(initial.basePath) }
    var domain by remember { mutableStateOf(initial.domain) }
    var user by remember { mutableStateOf(initial.user) }
    var password by remember { mutableStateOf(initial.password) }
    var bucket by remember { mutableStateOf(initial.bucket) }
    var encryptionKey by remember { mutableStateOf(initial.encryptionKey) }
    var passwordVisible by remember { mutableStateOf(false) }
    var keyVisible by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    val scope = rememberCoroutineScope()

    fun build(): OfflineConfig = initial.copy(
        enabled = true,
        host = host.trim().trim('\\', '/'),
        port = port.trim().toIntOrNull()?.coerceIn(1, 65535) ?: 445,
        share = share.trim().trim('\\', '/'),
        basePath = basePath.trim().trim('/', '\\'),
        domain = domain.trim(),
        user = user.trim(),
        password = password,
        bucket = bucket.trim().trim('/'),
        encryptionKey = encryptionKey.trim(),
    )

    val draft = build()

    AlertDialog(
        onDismissRequest = { if (!testing) onDismiss() },
        title = {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.CloudOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(10.dp))
                Text("Автономный доступ")
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Телефон будет читать и записывать данные MinIO напрямую по SMB. " +
                        "Сервер ProjectList для этого не нужен.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                ) {
                    Icon(
                        Icons.Filled.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Перед изменениями остановите ProjectList и MinIO на компьютере, " +
                            "иначе их кэш может перезаписать правки.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                OfflineField(
                    value = host,
                    onValueChange = { host = it; result = null },
                    label = "Сервер SMB",
                    placeholder = "10.5.1.50",
                    keyboardType = KeyboardType.Uri,
                )
                OfflineField(
                    value = share,
                    onValueChange = { share = it; result = null },
                    label = "Общий ресурс",
                    placeholder = "Project",
                )
                OfflineField(
                    value = basePath,
                    onValueChange = { basePath = it; result = null },
                    label = "Папка с данными MinIO",
                    placeholder = "MinIO",
                )
                OfflineField(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit); result = null },
                    label = "Порт",
                    placeholder = "445",
                    keyboardType = KeyboardType.Number,
                )
                OfflineField(
                    value = domain,
                    onValueChange = { domain = it; result = null },
                    label = "Домен (необязательно)",
                    placeholder = "WORKGROUP",
                )
                OfflineField(
                    value = user,
                    onValueChange = { user = it; result = null },
                    label = "Пользователь SMB",
                )
                OfflineField(
                    value = password,
                    onValueChange = { password = it; result = null },
                    label = "Пароль SMB",
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (passwordVisible) "Скрыть пароль" else "Показать пароль",
                            )
                        }
                    },
                )
                OfflineField(
                    value = bucket,
                    onValueChange = { bucket = it; result = null },
                    label = "Bucket MinIO",
                    placeholder = "secure-bucket",
                )
                if (paired) {
                    Text(
                        "Ключ шифрования уже получен при сопряжении и хранится в Android Keystore. " +
                            "Менять его не нужно.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                OutlinedTextField(
                    value = encryptionKey,
                    onValueChange = { encryptionKey = it; result = null },
                    label = { Text("Ключ шифрования ProjectList") },
                    placeholder = { Text("base64 или hex") },
                    leadingIcon = { Icon(Icons.Filled.Key, contentDescription = null) },
                    trailingIcon = {
                        IconButton(onClick = { keyVisible = !keyVisible }) {
                            Icon(
                                if (keyVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (keyVisible) "Скрыть ключ" else "Показать ключ",
                            )
                        }
                    },
                    singleLine = true,
                    visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )

                result?.let { (ok, message) ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (ok) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                    )
                }

                OutlinedButton(
                    onClick = {
                        if (testing) return@OutlinedButton
                        testing = true
                        result = null
                        scope.launch {
                            try {
                                val info = container.offline.testConnection(build())
                                result = true to info
                            } catch (t: Throwable) {
                                result = false to friendlyError(t)
                            } finally {
                                testing = false
                            }
                        }
                    },
                    enabled = !testing && draft.configured,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (testing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (testing) "Проверяем подключение…" else "Проверить подключение")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val config = build()
                    container.session.saveOffline(config)
                    onSaved(config)
                    onDismiss()
                },
                enabled = !testing && draft.configured,
            ) {
                Text("Сохранить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !testing) { Text("Отмена") }
        },
        shape = RoundedCornerShape(22.dp),
    )
}

@Composable
private fun OfflineField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { text -> @Composable { Text(text) } },
        singleLine = true,
        visualTransformation = visualTransformation,
        trailingIcon = trailingIcon,
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            imeAction = ImeAction.Next,
        ),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.fillMaxWidth(),
    )
}
