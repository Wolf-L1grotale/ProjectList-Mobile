package com.projectlist.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.projectlist.mobile.AppContainer
import com.projectlist.mobile.data.Project
import com.projectlist.mobile.security.rememberBiometricGuard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class HomeTab(val label: String, val icon: ImageVector) {
    Projects("Проекты", Icons.Filled.Folder),
    Tasks("Задачи", Icons.Filled.Checklist),
    Notifications("Уведомления", Icons.Filled.Notifications),
    More("Ещё", Icons.Filled.MoreHoriz),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    container: AppContainer,
    onOpenProject: (String) -> Unit,
) {
    val projectsVm: ProjectsViewModel = viewModel(factory = AppViewModelFactory(container))
    val notificationsVm: NotificationsViewModel = viewModel(factory = AppViewModelFactory(container))
    val settingsVm: SettingsViewModel = viewModel(factory = AppViewModelFactory(container))

    val projectsState by projectsVm.state.collectAsStateWithLifecycle()
    val notificationsState by notificationsVm.state.collectAsStateWithLifecycle()
    val settingsState by settingsVm.state.collectAsStateWithLifecycle()
    val session by container.session.state.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableStateOf(HomeTab.Projects) }
    var showCreate by remember { mutableStateOf(false) }
    var showOffline by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Project?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val biometric = rememberBiometricGuard()

    LaunchedEffect(Unit) {
        notificationsVm.load()
        while (true) {
            delay(30_000)
            notificationsVm.loadIfStale()
        }
    }

    LaunchedEffect(tab) {
        if (tab == HomeTab.Notifications) notificationsVm.load()
    }

    LaunchedEffect(projectsState.message) {
        val message = projectsState.message ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        projectsVm.consumeMessage()
    }

    LaunchedEffect(notificationsState.message) {
        val message = notificationsState.message ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        notificationsVm.consumeMessage()
    }

    BackHandler(enabled = tab != HomeTab.Projects) {
        tab = HomeTab.Projects
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when (tab) {
                            HomeTab.Projects -> "Проекты"
                            HomeTab.Tasks -> "Задачи"
                            HomeTab.Notifications -> "Уведомления"
                            HomeTab.More -> "Ещё"
                        },
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                actions = {
                    if (session.offlineActive) {
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            shape = RoundedCornerShape(50),
                            modifier = Modifier.padding(end = 4.dp),
                        ) {
                            Text(
                                "Автономно",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    }
                    when (tab) {
                        HomeTab.Projects, HomeTab.Tasks -> IconButton(onClick = projectsVm::refresh) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Обновить")
                        }

                        HomeTab.Notifications -> {
                            IconButton(onClick = { notificationsVm.markAllRead() }) {
                                Icon(Icons.Filled.DoneAll, contentDescription = "Прочитать все")
                            }
                            IconButton(onClick = { notificationsVm.clearAll() }) {
                                Icon(Icons.Filled.DeleteSweep, contentDescription = "Очистить уведомления")
                            }
                        }

                        HomeTab.More -> Unit
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                HomeTab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = {
                            if (item == HomeTab.Notifications && notificationsState.unread > 0) {
                                BadgedBox(badge = {
                                    Badge {
                                        Text(
                                            if (notificationsState.unread > 99) "99+" else notificationsState.unread.toString(),
                                        )
                                    }
                                }) {
                                    Icon(item.icon, contentDescription = null)
                                }
                            } else {
                                Icon(item.icon, contentDescription = null)
                            }
                        },
                        label = { Text(item.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        },
        floatingActionButton = {
            if (tab == HomeTab.Projects) {
                FloatingActionButton(
                    onClick = { showCreate = true },
                    shape = RoundedCornerShape(18.dp),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Создать проект")
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val contentModifier = Modifier
            .fillMaxSize()
            .padding(top = padding.calculateTopPadding())

        when (tab) {
            HomeTab.Projects -> ProjectsScreen(
                state = projectsState,
                onQueryChange = projectsVm::setQuery,
                onRefresh = projectsVm::refresh,
                onOpenProject = onOpenProject,
                onDeleteProject = { pendingDelete = it },
                modifier = contentModifier,
            )

            HomeTab.Tasks -> TasksScreen(
                state = projectsState,
                onOpenProject = onOpenProject,
                onToggleTask = { projectId, index -> projectsVm.toggleTask(projectId, index) },
                onRefresh = projectsVm::refresh,
                modifier = contentModifier,
            )

            HomeTab.Notifications -> NotificationsScreen(
                state = notificationsState,
                onMarkRead = notificationsVm::markRead,
                onDelete = notificationsVm::delete,
                onMarkAllRead = notificationsVm::markAllRead,
                onRefresh = { notificationsVm.load(showSpinner = true) },
                modifier = contentModifier,
            )

            HomeTab.More -> SettingsScreen(
                state = settingsState,
                session = session,
                offlineFingerprint = remember(session.offline) {
                    container.offline.fingerprint(session.offline)
                },
                onRefresh = {
                    settingsVm.load()
                    projectsVm.refresh()
                    notificationsVm.load(showSpinner = true)
                },
                onLoadLogs = settingsVm::loadLogs,
                onLogout = settingsVm::logout,
                onForgetDevice = settingsVm::forgetDevice,
                onToggleOffline = { enabled ->
                    if (enabled && !session.offline.configured) {
                        showOffline = true
                    } else {
                        container.session.setOfflineEnabled(enabled)
                    }
                },
                onOpenOfflineSetup = { showOffline = true },
                modifier = contentModifier,
            )
        }
    }

    if (showCreate) {
        CreateProjectDialog(
            busy = projectsState.busy,
            onDismiss = { showCreate = false },
            onCreate = { name, description ->
                projectsVm.createProject(name, description) { ok ->
                    if (ok) showCreate = false
                }
            },
        )
    }

    pendingDelete?.let { project ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Удалить проект?") },
            text = {
                Text(
                    "Проект «${project.name}», его файлы и история будут удалены из хранилища без возможности восстановления.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = project
                    pendingDelete = null
                    scope.launch {
                        if (
                            biometric.confirm(
                                title = "Удалить проект?",
                                subtitle = "Подтвердите отпечатком пальца",
                            )
                        ) {
                            projectsVm.deleteProject(target)
                        }
                    }
                }) {
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Отмена") }
            },
            shape = RoundedCornerShape(20.dp),
        )
    }

    if (showOffline) {
        OfflineSetupDialog(
            container = container,
            onDismiss = { showOffline = false },
        )
    }
}

@Composable
private fun CreateProjectDialog(
    busy: Boolean,
    onDismiss: () -> Unit,
    onCreate: (String, String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Новый проект") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Название") },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Next),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Описание (необязательно)") },
                    minLines = 2,
                    maxLines = 4,
                    enabled = !busy,
                    shape = RoundedCornerShape(14.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name, description) },
                enabled = !busy && name.isNotBlank(),
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp), strokeWidth = 2.dp)
                }
                Text("Создать")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Отмена") }
        },
        shape = RoundedCornerShape(20.dp),
    )
}
