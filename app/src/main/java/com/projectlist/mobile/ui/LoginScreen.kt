package com.projectlist.mobile.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.projectlist.mobile.AppContainer
import com.projectlist.mobile.data.ServerAddress
import com.projectlist.mobile.data.friendlyError
import com.projectlist.mobile.ui.components.ErrorBanner
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(container: AppContainer, onLoggedIn: () -> Unit) {
    val initialServer = container.session.current.serverUrl
    val initialPairServer = container.session.current.pairedServer.ifBlank { initialServer }
    var server by rememberSaveable { mutableStateOf(initialServer) }
    var key by rememberSaveable { mutableStateOf("") }
    var keyVisible by rememberSaveable { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showOffline by rememberSaveable { mutableStateOf(false) }
    var showManual by rememberSaveable { mutableStateOf(false) }
    var showPairForm by rememberSaveable { mutableStateOf(false) }
    var pairServer by rememberSaveable { mutableStateOf(initialPairServer) }
    var pairName by rememberSaveable { mutableStateOf(defaultDeviceName()) }
    var pairScheme by rememberSaveable { mutableStateOf(initialScheme(initialPairServer)) }
    var pairPort by rememberSaveable { mutableStateOf(initialPort(initialPairServer)) }
    var pairAdvanced by rememberSaveable { mutableStateOf(false) }
    var scheme by rememberSaveable { mutableStateOf(initialScheme(initialServer)) }
    var port by rememberSaveable { mutableStateOf(initialPort(initialServer)) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var pairing by remember { mutableStateOf(false) }
    var pairStatus by remember { mutableStateOf("") }
    var pairError by remember { mutableStateOf<String?>(null) }
    var pairJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val session by container.session.state.collectAsStateWithLifecycle()
    val fingerprint = remember(session.offline.encryptionKey) {
        runCatching { container.offline.fingerprint(session.offline) }.getOrNull()
    }

    fun enterOffline() {
        val config = container.session.current.offline
        if (!config.configured) {
            showOffline = true
            return
        }
        if (!config.enabled) container.session.setOfflineEnabled(true)
        // Убираем серверную сессию, чтобы Repository переключился на SMB.
        if (container.session.current.token != null) container.session.logout()
        onLoggedIn()
    }

    fun submitKey() {
        if (loading) return
        val address = server.trim()
        val accessKey = key.trim()
        val portNumber = port.toIntOrNull()
        when {
            address.isEmpty() -> {
                error = "Укажите IP-адрес или URL сервера"
                return
            }

            portNumber == null || portNumber !in 1..65535 -> {
                error = "Порт должен быть числом от 1 до 65535"
                return
            }

            accessKey.isEmpty() -> {
                error = "Введите ключ доступа"
                return
            }
        }
        val normalized = runCatching { ServerAddress.withOptions(address, scheme, portNumber!!) }
            .getOrElse {
                error = it.message ?: "Некорректный адрес сервера"
                return
            }
        loading = true
        error = null
        scope.launch {
            try {
                container.repository.connect(
                    server = normalized.origin,
                    key = accessKey,
                )
                onLoggedIn()
            } catch (t: Throwable) {
                error = friendlyError(t)
            } finally {
                loading = false
            }
        }
    }

    fun startPairing() {
        if (pairing) return
        if (pairServer.trim().isEmpty()) {
            pairError = "Укажите IP-адрес или URL компьютера с запущенным ProjectList"
            return
        }
        val portNumber = pairPort.toIntOrNull()
        if (portNumber == null || portNumber !in 1..65535) {
            pairError = "Порт должен быть числом от 1 до 65535"
            return
        }
        val normalized = runCatching {
            ServerAddress.withOptions(pairServer, pairScheme, portNumber!!)
        }.getOrElse {
            pairError = it.message ?: "Некорректный адрес компьютера"
            return
        }
        pairing = true
        pairError = null
        pairStatus = "Отправляем запрос на ${normalized.origin}…"
        pairJob = scope.launch {
            try {
                container.repository.pairWithTrustedPc(
                    server = normalized.origin,
                    deviceName = pairName,
                    onProgress = { pairStatus = it },
                )
                pairing = false
                showPairForm = false
                // Сразу предлагаем настроить доступ к данным: ключ уже получен.
                showOffline = true
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                pairing = false
                pairError = friendlyError(t)
            } finally {
                pairJob = null
            }
        }
    }

    fun cancelPairing() {
        pairJob?.cancel()
        pairJob = null
        pairing = false
        pairStatus = ""
    }

    val colors = MaterialTheme.colorScheme
    val pairFormVisible = !session.paired || showPairForm

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        colors.primaryContainer.copy(alpha = 0.85f),
                        colors.background,
                        colors.background,
                    ),
                ),
            )
            .systemBarsPadding()
            .imePadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))

            Box(
                modifier = Modifier
                    .size(76.dp)
                    .background(
                        Brush.linearGradient(listOf(Color(0xFF6366F1), Color(0xFF0EA5E9))),
                        RoundedCornerShape(22.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Dns,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(38.dp),
                )
            }

            Spacer(Modifier.height(18.dp))
            Text("ProjectList", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Мобильный клиент системы учёта проектов",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )

            Spacer(Modifier.height(28.dp))

            if (session.paired && !showPairForm) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = colors.tertiaryContainer.copy(alpha = 0.45f),
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.Shield,
                                contentDescription = null,
                                tint = colors.tertiary,
                                modifier = Modifier.size(26.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(
                                    "Устройство подключено",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    "Можно работать без компьютера",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant,
                                )
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Text(
                            "Ключ шифрования получен с компьютера ${session.pairedServer.ifBlank { "" }} " +
                                "и сохранён в защищённом хранилище Android (Keystore).",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                        if (!fingerprint.isNullOrBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Отпечаток ключа: …$fingerprint",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        if (session.offline.configured) {
                            Button(
                                onClick = { enterOffline() },
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp),
                            ) {
                                Icon(Icons.Filled.CloudOff, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Войти без компьютера")
                            }
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { showOffline = true },
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Настройки доступа к данным")
                            }
                        } else {
                            Button(
                                onClick = { showOffline = true },
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp),
                            ) {
                                Icon(Icons.Filled.CloudOff, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Настроить доступ к данным")
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Укажите логин и пароль SMB от компьютера, где лежат данные MinIO.",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        TextButton(
                            onClick = {
                                pairError = null
                                showPairForm = true
                            },
                            modifier = Modifier.align(Alignment.Start),
                        ) {
                            Text("Сопрячь заново")
                        }
                    }
                }
            }

            if (pairFormVisible) {
                if (session.paired) {
                    Spacer(Modifier.height(12.dp))
                }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text(
                            if (session.paired) "Новое сопряжение" else "Подключить устройство",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Укажите компьютер, на котором уже работает ProjectList с мастер-ключом FIDO. " +
                                "Запрос нужно подтвердить один раз — после этого телефон работает сам.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Нужен IP компьютера с ProjectList, а не адрес SMB-хранилища MinIO.",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.primary,
                        )
                        Spacer(Modifier.height(14.dp))

                        OutlinedTextField(
                            value = pairServer,
                            onValueChange = { text ->
                                pairServer = text
                                pairError = null
                                // Если пользователь вставил полный URL, подхватываем
                                // явно указанные протокол и порт.
                                runCatching {
                                    ServerAddress.parse(
                                        text,
                                        pairScheme,
                                        pairPort.toIntOrNull() ?: ServerAddress.DEFAULT_PORT,
                                    )
                                }.getOrNull()?.let { parsed ->
                                    pairScheme = parsed.scheme
                                    pairPort = parsed.port.toString()
                                }
                            },
                            label = { Text("IP-адрес или URL компьютера") },
                            placeholder = { Text("10.5.1.147") },
                            leadingIcon = { Icon(Icons.Filled.Dns, contentDescription = null) },
                            supportingText = {
                                Text("Достаточно IP — http:// и порт 3000 подставятся автоматически")
                            },
                            singleLine = true,
                            enabled = !pairing,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Next,
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth(),
                        )

                        AddressOptions(
                            address = pairServer,
                            scheme = pairScheme,
                            port = pairPort,
                            expanded = pairAdvanced,
                            enabled = !pairing,
                            onExpandedChange = { pairAdvanced = it },
                            onSchemeChange = { pairScheme = it },
                            onPortChange = { pairPort = it },
                        )

                        Spacer(Modifier.height(12.dp))

                        OutlinedTextField(
                            value = pairName,
                            onValueChange = { pairName = it },
                            label = { Text("Имя устройства") },
                            leadingIcon = { Icon(Icons.Filled.Shield, contentDescription = null) },
                            singleLine = true,
                            enabled = !pairing,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth(),
                        )

                        if (pairError != null) {
                            Spacer(Modifier.height(12.dp))
                            ErrorBanner(pairError!!)
                        }

                        if (pairing) {
                            Spacer(Modifier.height(16.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    pairStatus.ifBlank { "Ожидаем подтверждения…" },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "На компьютере откройте ProjectList → Настройки → Подключение устройств " +
                                    "и подтвердите запрос мастер-ключом FIDO. Окно доступно 2 минуты.",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                            TextButton(onClick = { cancelPairing() }) {
                                Text("Отменить ожидание")
                            }
                        } else {
                            Spacer(Modifier.height(18.dp))
                            Button(
                                onClick = { startPairing() },
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp),
                            ) {
                                Icon(Icons.Filled.Shield, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(if (session.paired) "Запросить новое сопряжение" else "Запросить подключение")
                            }
                        }

                        if (session.paired) {
                            TextButton(
                                onClick = {
                                    if (pairing) cancelPairing()
                                    showPairForm = false
                                },
                                modifier = Modifier.align(Alignment.Start),
                            ) {
                                Text("Отмена")
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            TextButton(onClick = { showManual = !showManual }) {
                Icon(Icons.Filled.Key, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Вход по ключу доступа")
            }

            if (showManual) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = colors.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    ) {
                        Column(Modifier.padding(18.dp)) {
                            Text(
                                "Вход по ключу доступа",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Этот способ требует запущенного ProjectList на компьютере. " +
                                    "Для автономной работы используйте подключение устройства.",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(14.dp))

                            OutlinedTextField(
                                value = server,
                                onValueChange = { text ->
                                    server = text
                                    error = null
                                    runCatching {
                                        ServerAddress.parse(
                                            text,
                                            scheme,
                                            port.toIntOrNull() ?: ServerAddress.DEFAULT_PORT,
                                        )
                                    }.getOrNull()?.let { parsed ->
                                        scheme = parsed.scheme
                                        port = parsed.port.toString()
                                    }
                                },
                                label = { Text("IP-адрес или URL сервера") },
                                placeholder = { Text("10.5.1.147") },
                                leadingIcon = { Icon(Icons.Filled.Dns, contentDescription = null) },
                                supportingText = {
                                    Text("Достаточно IP — http:// и порт 3000 подставятся автоматически")
                                },
                                singleLine = true,
                                enabled = !loading,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Uri,
                                    imeAction = ImeAction.Next,
                                ),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth(),
                            )

                            AddressOptions(
                                address = server,
                                scheme = scheme,
                                port = port,
                                expanded = advanced,
                                enabled = !loading,
                                onExpandedChange = { advanced = it },
                                onSchemeChange = { scheme = it },
                                onPortChange = { port = it },
                            )

                            Spacer(Modifier.height(12.dp))

                            OutlinedTextField(
                                value = key,
                                onValueChange = {
                                    key = it
                                    error = null
                                },
                                label = { Text("Ключ доступа") },
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
                                enabled = !loading,
                                visualTransformation = if (keyVisible) {
                                    VisualTransformation.None
                                } else {
                                    PasswordVisualTransformation()
                                },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Password,
                                    imeAction = ImeAction.Done,
                                ),
                                keyboardActions = KeyboardActions(onDone = { submitKey() }),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth(),
                            )

                            if (error != null) {
                                Spacer(Modifier.height(12.dp))
                                ErrorBanner(error!!)
                            }

                            Spacer(Modifier.height(16.dp))

                            Button(
                                onClick = { submitKey() },
                                enabled = !loading,
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = colors.primary),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp),
                            ) {
                                if (loading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                        color = colors.onPrimary,
                                    )
                                    Spacer(Modifier.width(10.dp))
                                }
                                Text(if (loading) "Подключение…" else "Подключиться")
                            }
                        }
                    }
                }

            if (!session.paired) {
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = { showOffline = true },
                    enabled = !pairing,
                ) {
                    Icon(Icons.Filled.CloudOff, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (session.offline.configured) {
                            "Настроить SMB и ключ вручную"
                        } else {
                            "Настроить автономный доступ вручную"
                        },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surface.copy(alpha = 0.65f), RoundedCornerShape(18.dp))
                    .padding(16.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Как это работает",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        "1. Запрос уходит на компьютер в вашей локальной сети.\n" +
                            "2. На компьютере его подтверждают мастер-ключом FIDO.\n" +
                            "3. Телефон получает ключ шифрования и сохраняет его в Android Keystore.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                    Text(
                        "После этого ProjectList и MinIO на компьютере запускать не нужно: " +
                            "телефон читает и изменяет данные напрямую по SMB. " +
                            "Перед изменениями остановите MinIO и ProjectList, чтобы их кэш не перезаписал правки.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Shield,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "Ключ хранится только на этом устройстве",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showOffline) {
        OfflineSetupDialog(
            container = container,
            onDismiss = { showOffline = false },
            onSaved = {
                error = null
                pairError = null
                enterOffline()
            },
        )
    }
}

private fun defaultDeviceName(): String {
    val brand = Build.MANUFACTURER?.trim().orEmpty()
    val model = Build.MODEL?.trim().orEmpty()
    val raw = listOf(brand, model)
        .filter { it.isNotBlank() }
        .joinToString(" ")
        .ifBlank { "Android" }
    return raw.replaceFirstChar {
        if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
    }
}

private fun initialScheme(address: String): String =
    runCatching { ServerAddress.parse(address).scheme }
        .getOrDefault(ServerAddress.DEFAULT_SCHEME)

private fun initialPort(address: String): String =
    runCatching { ServerAddress.parse(address).port }
        .getOrDefault(ServerAddress.DEFAULT_PORT)
        .toString()

/**
 * Необязательные настройки адреса: протокол и порт. По умолчанию используются
 * http и 3000, поэтому в обычном сценарии пользователю достаточно ввести IP.
 */
@Composable
private fun AddressOptions(
    address: String,
    scheme: String,
    port: String,
    expanded: Boolean,
    enabled: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSchemeChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val portNumber = port.toIntOrNull()
    val portInvalid = port.isNotBlank() && (portNumber == null || portNumber !in 1..65535)
    val preview = remember(address, scheme, port) {
        if (address.isBlank()) {
            null
        } else {
            runCatching {
                ServerAddress.withOptions(
                    address,
                    scheme,
                    portNumber ?: ServerAddress.DEFAULT_PORT,
                ).origin
            }.getOrNull()
        }
    }

    if (!preview.isNullOrBlank()) {
        Text(
            "Итоговый адрес: $preview",
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Протокол и порт",
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        TextButton(
            onClick = { onExpandedChange(!expanded) },
            enabled = enabled,
        ) {
            Text(if (expanded) "Скрыть" else "Изменить")
        }
    }

    if (expanded) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = scheme == "http",
                onClick = { onSchemeChange("http") },
                label = { Text("HTTP") },
                enabled = enabled,
            )
            FilterChip(
                selected = scheme == "https",
                onClick = { onSchemeChange("https") },
                label = { Text("HTTPS") },
                enabled = enabled,
            )
            OutlinedTextField(
                value = port,
                onValueChange = { text ->
                    if (text.length <= 5 && text.all { it.isDigit() }) {
                        onPortChange(text)
                    }
                },
                label = { Text("Порт") },
                singleLine = true,
                isError = portInvalid,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.width(124.dp),
            )
        }
        if (portInvalid) {
            Text(
                "Порт должен быть числом от 1 до 65535",
                style = MaterialTheme.typography.labelSmall,
                color = colors.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
