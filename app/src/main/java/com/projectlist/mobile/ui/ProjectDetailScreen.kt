package com.projectlist.mobile.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.projectlist.mobile.AppContainer
import com.projectlist.mobile.data.Commit
import com.projectlist.mobile.data.FileEntry
import com.projectlist.mobile.data.Project
import com.projectlist.mobile.data.ProjectStatuses
import com.projectlist.mobile.data.Screenshot
import com.projectlist.mobile.data.friendlyError
import com.projectlist.mobile.security.rememberBiometricGuard
import com.projectlist.mobile.ui.components.AuthorizedImage
import com.projectlist.mobile.ui.components.EmptyState
import com.projectlist.mobile.ui.components.ErrorBanner
import com.projectlist.mobile.ui.components.KeyValueRow
import com.projectlist.mobile.ui.components.LoadingBox
import com.projectlist.mobile.ui.components.MarkdownText
import com.projectlist.mobile.ui.components.ProjectAvatar
import com.projectlist.mobile.ui.components.SectionCard
import com.projectlist.mobile.ui.components.StatTile
import com.projectlist.mobile.ui.components.StatusBadge
import com.projectlist.mobile.ui.components.currentBranchName
import com.projectlist.mobile.ui.components.fileIcon
import com.projectlist.mobile.ui.components.formatBytes
import com.projectlist.mobile.ui.components.formatDate
import com.projectlist.mobile.ui.components.headCommit
import com.projectlist.mobile.ui.components.headFiles
import com.projectlist.mobile.ui.components.isImageFile
import com.projectlist.mobile.ui.components.isTextFile
import com.projectlist.mobile.ui.components.projectProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectDetailScreen(
    container: AppContainer,
    projectId: String,
    onBack: () -> Unit,
) {
    val vm: ProjectDetailViewModel = viewModel(
        factory = AppViewModelFactory(container, projectId = projectId),
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val session by container.session.state.collectAsStateWithLifecycle()
    val project = state.project
    val token = session.token
    val offline = session.offlineActive

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var tab by rememberSaveable { mutableStateOf(0) }
    var showEdit by remember { mutableStateOf(false) }
    var showDeleteProject by remember { mutableStateOf(false) }
    var showNewBranch by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var pendingFiles by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var showCommit by remember { mutableStateOf(false) }
    var viewerEntry by remember { mutableStateOf<FileEntry?>(null) }
    var downloadEntry by remember { mutableStateOf<FileEntry?>(null) }
    var deleteFileEntry by remember { mutableStateOf<FileEntry?>(null) }
    var rollbackCommit by remember { mutableStateOf<Commit?>(null) }
    var mergeSource by remember { mutableStateOf<String?>(null) }
    var pendingScreenshot by remember { mutableStateOf<Screenshot?>(null) }
    var pendingTaskDelete by remember { mutableStateOf<Int?>(null) }
    val biometric = rememberBiometricGuard()

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            pendingFiles = uris
            showCommit = true
        }
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.uploadScreenshot(uri) { }
    }

    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val entry = downloadEntry
        downloadEntry = null
        if (uri != null && entry != null) {
            scope.launch {
                try {
                    val bytes = vm.downloadBytes(entry.blobId)
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    }
                    snackbar.showSnackbar("Файл сохранён: ${entry.name}")
                } catch (t: Throwable) {
                    snackbar.showSnackbar(friendlyError(t))
                }
            }
        }
    }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        vm.consumeMessage()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                title = {
                    Column {
                        Text(
                            project?.name?.ifBlank { "Проект" } ?: "Проект",
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (project != null) {
                            Text(
                                "${currentBranchName(project)} · ${ProjectStatuses.label(project.data.status)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = vm::refresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Обновить")
                    }
                    IconButton(onClick = { showEdit = true }, enabled = project != null) {
                        Icon(Icons.Filled.Edit, contentDescription = "Редактировать")
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Действия")
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Удалить проект") },
                                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    showDeleteProject = true
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            val tabs = listOf("Обзор", "Задачи", "Файлы", "История")
            TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
                tabs.forEachIndexed { index, label ->
                    Tab(
                        selected = tab == index,
                        onClick = { tab = index },
                        text = { Text(label, maxLines = 1) },
                    )
                }
            }

            if (state.busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            when {
                state.loading && project == null -> LoadingBox(
                    modifier = Modifier.weight(1f),
                    label = "Загружаем проект…",
                )

                project == null -> Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                ) {
                    if (state.error != null) {
                        ErrorBanner(state.error!!, onRetry = vm::refresh)
                    }
                    EmptyState(
                        icon = Icons.Filled.InsertDriveFile,
                        title = "Проект не открылся",
                        subtitle = state.error ?: "Проверьте подключение к серверу и повторите попытку.",
                    )
                }

                else -> Box(Modifier.weight(1f)) {
                    when (tab) {
                        0 -> OverviewTab(
                            project = project,
                            token = token,
                            allProjects = state.allProjects,
                            onSetStatus = vm::setStatus,
                            onNewBranch = { showNewBranch = true },
                            onMergeBranch = { mergeSource = it },
                            onSwitchBranch = vm::switchBranch,
                            onAddScreenshot = { pickImage.launch("image/*") },
                            onDeleteScreenshot = { pendingScreenshot = it },
                            blobUrlFor = vm::blobUrl,
                            allowUploads = !offline,
                        )

                        1 -> TasksTab(
                            project = project,
                            onAddTask = vm::addTask,
                            onToggleTask = vm::toggleTask,
                            onDeleteTask = { pendingTaskDelete = it },
                        )

                        2 -> FilesTab(
                            project = project,
                            busy = state.busy,
                            uploadProgress = state.uploadProgress,
                            onUpload = { pickFiles.launch(arrayOf("*/*")) },
                            onOpenFile = { viewerEntry = it },
                            onDownload = {
                                downloadEntry = it
                                saveFile.launch(it.name.ifBlank { "file" })
                            },
                            onDeleteFile = { deleteFileEntry = it },
                            allowUploads = !offline,
                        )

                        else -> HistoryTab(
                            project = project,
                            onRollback = { rollbackCommit = it },
                        )
                    }
                }
            }
        }
    }

    if (showEdit && project != null) {
        EditProjectDialog(
            initialName = project.name,
            initialDescription = project.description,
            busy = state.busy,
            onDismiss = { showEdit = false },
            onSave = { name, description ->
                vm.editProject(name, description) { ok -> if (ok) showEdit = false }
            },
        )
    }

    if (showNewBranch) {
        NewBranchDialog(
            busy = state.busy,
            onDismiss = { showNewBranch = false },
            onCreate = { name ->
                vm.createBranch(name) { ok -> if (ok) showNewBranch = false }
            },
        )
    }

    if (showCommit && pendingFiles.isNotEmpty()) {
        CommitDialog(
            fileCount = pendingFiles.size,
            busy = state.busy,
            onDismiss = {
                if (!state.busy) {
                    showCommit = false
                    pendingFiles = emptyList()
                }
            },
            onConfirm = { message, version ->
                val files = pendingFiles
                vm.uploadFromUris(files, message, version) { ok ->
                    if (ok) {
                        showCommit = false
                        pendingFiles = emptyList()
                        tab = 2
                    }
                }
            },
        )
    }

    if (showDeleteProject && project != null) {
        ConfirmDialog(
            title = "Удалить проект?",
            text = "Проект «${project.name}» и все его файлы будут удалены с сервера без возможности восстановления.",
            confirmLabel = "Удалить",
            destructive = true,
            onDismiss = { showDeleteProject = false },
            onConfirm = {
                showDeleteProject = false
                scope.launch {
                    if (
                        biometric.confirm(
                            title = "Удалить проект?",
                            subtitle = "Подтвердите отпечатком пальца",
                        )
                    ) {
                        vm.deleteProject { onBack() }
                    }
                }
            },
        )
    }

    deleteFileEntry?.let { entry ->
        ConfirmDialog(
            title = "Удалить файл?",
            text = "Файл «${entry.path}» будет удалён новым коммитом. Историю можно восстановить откатом.",
            confirmLabel = "Удалить",
            destructive = true,
            onDismiss = { deleteFileEntry = null },
            onConfirm = {
                deleteFileEntry = null
                scope.launch {
                    if (
                        biometric.confirm(
                            title = "Удалить файл?",
                            subtitle = "Подтвердите отпечатком пальца",
                        )
                    ) {
                        vm.deleteFile(entry.path)
                    }
                }
            },
        )
    }

    rollbackCommit?.let { commit ->
        ConfirmDialog(
            title = "Откатить проект?",
            text = "Файлы будут приведены к состоянию коммита ${commit.id.take(8)} «${commit.message}». " +
                "Откат создаёт новый коммит, история сохранится.",
            confirmLabel = "Откатить",
            onDismiss = { rollbackCommit = null },
            onConfirm = {
                rollbackCommit = null
                vm.rollbackTo(commit.id) { }
            },
        )
    }

    mergeSource?.let { source ->
        val target = project?.let { currentBranchName(it) } ?: "main"
        ConfirmDialog(
            title = "Слить ветку?",
            text = "Изменения ветки «$source» будут слиты в текущую ветку «$target». " +
                "Если возникнут конфликты, сервер откажет без изменений.",
            confirmLabel = "Слить",
            onDismiss = { mergeSource = null },
            onConfirm = {
                mergeSource = null
                vm.mergeBranch(source) { }
            },
        )
    }

    pendingScreenshot?.let { shot ->
        ConfirmDialog(
            title = "Удалить скриншот?",
            text = "Скриншот «${shot.name}» будет удалён из проекта.",
            confirmLabel = "Удалить",
            destructive = true,
            onDismiss = { pendingScreenshot = null },
            onConfirm = {
                pendingScreenshot = null
                scope.launch {
                    if (
                        biometric.confirm(
                            title = "Удалить скриншот?",
                            subtitle = "Подтвердите отпечатком пальца",
                        )
                    ) {
                        vm.deleteScreenshot(shot.id)
                    }
                }
            },
        )
    }

    pendingTaskDelete?.let { index ->
        val task = project?.data?.tasks?.getOrNull(index)
        ConfirmDialog(
            title = "Удалить задачу?",
            text = task?.title?.ifBlank { "Без названия" }
                ?.let { "Задача «$it» будет удалена из проекта." }
                ?: "Задача будет удалена из проекта.",
            confirmLabel = "Удалить",
            destructive = true,
            onDismiss = { pendingTaskDelete = null },
            onConfirm = {
                pendingTaskDelete = null
                scope.launch {
                    if (
                        biometric.confirm(
                            title = "Удалить задачу?",
                            subtitle = "Подтвердите отпечатком пальца",
                        )
                    ) {
                        vm.deleteTask(index)
                    }
                }
            },
        )
    }

    viewerEntry?.let { entry ->
        FileViewerDialog(
            entry = entry,
            token = token,
            imageUrl = vm.blobUrl(entry.blobId),
            loadText = { item -> vm.downloadText(item.blobId) },
            onDownload = {
                downloadEntry = entry
                saveFile.launch(entry.name.ifBlank { "file" })
            },
            onDismiss = { viewerEntry = null },
        )
    }
}

// ----------------------------------------------------------------- overview

@Composable
private fun OverviewTab(
    project: Project,
    token: String?,
    allProjects: List<Project>,
    onSetStatus: (String) -> Unit,
    onNewBranch: () -> Unit,
    onMergeBranch: (String) -> Unit,
    onSwitchBranch: (String) -> Unit,
    onAddScreenshot: () -> Unit,
    onDeleteScreenshot: (Screenshot) -> Unit,
    blobUrlFor: (String) -> String,
    allowUploads: Boolean,
) {
    val progress = projectProgress(project)
    val files = headFiles(project)
    val head = headCommit(project)
    val branches = project.data.branches
    val screenshots = project.data.screenshots
    val current = currentBranchName(project)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp)),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProjectAvatar(project.name, 56)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        project.name.ifBlank { "Без названия" },
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        project.description.ifBlank { "Без описания" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusBadge(project.data.status)
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ProjectStatuses.all.forEach { status ->
                    FilterChip(
                        selected = ProjectStatuses.normalized(project.data.status) == status,
                        onClick = { onSetStatus(status) },
                        label = { Text(ProjectStatuses.label(status), style = MaterialTheme.typography.labelMedium) },
                        leadingIcon = if (ProjectStatuses.normalized(project.data.status) == status) {
                            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else {
                            null
                        },
                    )
                }
            }
        }

        SectionCard(title = "Прогресс", subtitle = "$progress%") {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(50)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ProgressFact("Задачи", "${project.data.tasks.count { it.done }} / ${project.data.tasks.size}")
                ProgressFact("Коммиты", project.data.commits.size.toString())
                ProgressFact("Файлы", files.size.toString())
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(
                icon = Icons.Filled.Description,
                value = files.size.toString(),
                label = "Файлов в HEAD",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                icon = Icons.Filled.History,
                value = project.data.commits.size.toString(),
                label = "Коммитов",
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(
                icon = Icons.Filled.AccountTree,
                value = branches.size.toString(),
                label = "Веток",
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                icon = Icons.Filled.Image,
                value = screenshots.size.toString(),
                label = "Скриншотов",
                tint = Color(0xFFEC4899),
                modifier = Modifier.weight(1f),
            )
        }

        SectionCard(
            title = "Ветки",
            subtitle = "Текущая: $current",
            action = {
                IconButton(onClick = onNewBranch) {
                    Icon(Icons.Filled.Add, contentDescription = "Новая ветка")
                }
            },
        ) {
            if (branches.isEmpty()) {
                Text(
                    "Ветки появятся после первого коммита.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                branches.forEach { branch ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(enabled = branch.name != current) { onSwitchBranch(branch.name) }
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.AccountTree,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = if (branch.name == current) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                branch.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (branch.name == current) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            Text(
                                when {
                                    !branch.mergedInto.isNullOrBlank() -> "влита в ${branch.mergedInto}"
                                    branch.head.isNullOrBlank() -> "пока пусто"
                                    else -> branch.head.take(8)
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (branch.name == current) {
                            Text(
                                "текущая",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        } else if (branch.mergedInto.isNullOrBlank() && !branch.head.isNullOrBlank()) {
                            TextButton(
                                onClick = { onMergeBranch(branch.name) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            ) {
                                Text("Слить", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            if (branches.size > 1) {
                Text(
                    "Нажмите на ветку, чтобы переключиться.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
            }
            OutlinedButton(
                onClick = onNewBranch,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Создать ветку")
            }
        }

        SectionCard(
            title = "Скриншоты",
            subtitle = if (screenshots.isEmpty()) "Нет изображений" else "${screenshots.size} шт.",
        ) {
            if (screenshots.isEmpty()) {
                Text(
                    "Добавьте скриншоты, чтобы видеть интерфейс проекта прямо с телефона.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(screenshots, key = { it.id }) { shot ->
                        Box(
                            modifier = Modifier
                                .size(width = 190.dp, height = 124.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                        ) {
                            AuthorizedImage(
                                url = blobUrlFor(shot.blobId),
                                token = token,
                                contentDescription = shot.name,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(4.dp)
                                    .size(28.dp)
                                    .background(Color.Black.copy(alpha = 0.45f), CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                IconButton(
                                    onClick = { onDeleteScreenshot(shot) },
                                    modifier = Modifier.size(28.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "Удалить скриншот",
                                        tint = Color.White,
                                        modifier = Modifier.size(15.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            if (allowUploads) {
                OutlinedButton(
                    onClick = onAddScreenshot,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Добавить скриншот")
                }
            } else {
                Text(
                    "Автономный режим: добавление скриншотов недоступно, удаление работает.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionCard(title = "Сведения") {
            KeyValueRow("Создан", formatDate(project.createdAt, withTime = true))
            KeyValueRow("Обновлён", formatDate(project.updatedAt, withTime = true))
            KeyValueRow("Текущая ветка", current)
            KeyValueRow("HEAD", head?.id?.take(10) ?: "—")
            KeyValueRow("Версия", project.data.version?.takeIf { it.isNotBlank() } ?: "—")
            if (project.data.dependencies.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Зависимости",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                project.data.dependencies.forEach { dep ->
                    val name = allProjects.find { it.id == dep.projectId }?.name ?: dep.projectId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.AccountTree,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            name,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        if (!dep.pinnedVersion.isNullOrBlank()) {
                            Text(
                                "v${dep.pinnedVersion}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }

        if (project.data.releases.isNotEmpty()) {
            SectionCard(title = "Релизы", subtitle = "${project.data.releases.size} шт.") {
                project.data.releases.forEach { release ->
                    Row(
                        modifier = Modifier.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            release.version.ifBlank { "v?" },
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                release.name.ifBlank { release.description.ifBlank { "Релиз" } },
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                formatDate(release.createdAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgressFact(label: String, value: String) {
    Column {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// -------------------------------------------------------------------- tasks

@Composable
private fun TasksTab(
    project: Project,
    onAddTask: (String) -> Unit,
    onToggleTask: (Int) -> Unit,
    onDeleteTask: (Int) -> Unit,
) {
    var newTask by remember { mutableStateOf("") }
    val tasks = project.data.tasks

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "add") {
            SectionCard(title = "Новая задача") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newTask,
                        onValueChange = { newTask = it },
                        placeholder = { Text("Что нужно сделать?") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (newTask.isNotBlank()) {
                                onAddTask(newTask)
                                newTask = ""
                            }
                        },
                        enabled = newTask.isNotBlank(),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }

        if (tasks.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    icon = Icons.Filled.Check,
                    title = "Задач нет",
                    subtitle = "Добавьте первую задачу — прогресс проекта посчитается автоматически.",
                )
            }
        } else {
            item(key = "count") {
                Text(
                    "Выполнено ${tasks.count { it.done }} из ${tasks.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
            items(tasks.size, key = { "task-$it" }) { index ->
                val task = tasks[index]
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggleTask(index) }
                            .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.Checkbox(
                            checked = task.done,
                            onCheckedChange = null,
                        )
                        Text(
                            text = task.title.ifBlank { "Без названия" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (task.done) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            textDecoration = if (task.done) {
                                androidx.compose.ui.text.style.TextDecoration.LineThrough
                            } else {
                                androidx.compose.ui.text.style.TextDecoration.None
                            },
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { onDeleteTask(index) }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "Удалить задачу",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------------- files

@Composable
private fun FilesTab(
    project: Project,
    busy: Boolean,
    uploadProgress: Pair<Int, Int>?,
    onUpload: () -> Unit,
    onOpenFile: (FileEntry) -> Unit,
    onDownload: (FileEntry) -> Unit,
    onDeleteFile: (FileEntry) -> Unit,
    allowUploads: Boolean,
) {
    val files = headFiles(project)
    val head = headCommit(project)

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Ветка ${currentBranchName(project)}",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        head?.let { "коммит ${it.id.take(8)} · ${formatDate(it.date, withTime = true)}" }
                            ?: "Коммитов ещё нет",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Button(
                    onClick = onUpload,
                    enabled = !busy && allowUploads,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Icon(Icons.Filled.Upload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (allowUploads) "Загрузить" else "Офлайн")
                }
            }

            if (!allowUploads) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Автономный режим: загрузка файлов недоступна. Удаление файлов и новые коммиты работают.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (uploadProgress != null && uploadProgress.second > 0) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { uploadProgress.first.toFloat() / uploadProgress.second.toFloat() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(50)),
                )
                Text(
                    "Загрузка ${uploadProgress.first} из ${uploadProgress.second}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
        }

        if (files.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.InsertDriveFile,
                title = "Файлов нет",
                subtitle = if (allowUploads) {
                    "Загрузите файлы с телефона — они попадут в новый коммит проекта."
                } else {
                    "В этой ветке ещё нет файлов. В автономном режиме загрузка недоступна."
                },
                action = if (allowUploads) {
                    {
                        Button(onClick = onUpload, shape = RoundedCornerShape(12.dp)) {
                            Text("Загрузить файлы")
                        }
                    }
                } else {
                    null
                },
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "total") {
                    Text(
                        "${files.size} файлов · ${formatBytes(files.sumOf { it.size })}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
                items(files, key = { it.path }) { file ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenFile(file) }
                                .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                fileIcon(file.name.ifBlank { file.path }, file.mime),
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    file.name.ifBlank { file.path.substringAfterLast('/') },
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${file.path} · ${formatBytes(file.size)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(onClick = { onDownload(file) }) {
                                Icon(
                                    Icons.Filled.Download,
                                    contentDescription = "Скачать",
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            IconButton(onClick = { onDeleteFile(file) }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "Удалить",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ history

@Composable
private fun HistoryTab(
    project: Project,
    onRollback: (Commit) -> Unit,
) {
    val commits = project.data.commits
    val head = headCommit(project)

    if (commits.isEmpty()) {
        EmptyState(
            icon = Icons.Filled.History,
            title = "История пуста",
            subtitle = "Каждая загрузка файлов создаёт коммит — здесь появится история изменений.",
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(commits, key = { it.id }) { commit ->
            SectionCard(
                title = commit.message.ifBlank { "Без сообщения" },
                subtitle = "${formatDate(commit.date, withTime = true)} · ${commit.branch.ifBlank { "—" }}",
                action = {
                    if (commit.version.isNotBlank()) {
                        Text(
                            "v${commit.version}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        commit.id.take(8),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (commit.id == head?.id) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "HEAD",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${commit.stats.files} файлов · ${formatBytes(commit.stats.bytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (commit.changes.added.isNotEmpty() ||
                    commit.changes.modified.isNotEmpty() ||
                    commit.changes.removed.isNotEmpty()
                ) {
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (commit.changes.added.isNotEmpty()) {
                            Text(
                                "+${commit.changes.added.size}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                        if (commit.changes.modified.isNotEmpty()) {
                            Text(
                                "~${commit.changes.modified.size}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                        if (commit.changes.removed.isNotEmpty()) {
                            Text(
                                "−${commit.changes.removed.size}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }

                if (!commit.mergeOf.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Слияние: ${commit.mergeOf}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (commit.id != head?.id) {
                    Spacer(Modifier.height(6.dp))
                    HorizontalDivider()
                    TextButton(onClick = { onRollback(commit) }) {
                        Icon(Icons.Filled.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Откатить к этому коммиту")
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------- dialogs

@Composable
private fun EditProjectDialog(
    initialName: String,
    initialDescription: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var description by remember { mutableStateOf(initialDescription) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Редактировать проект") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Название") },
                    singleLine = true,
                    enabled = !busy,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Описание") },
                    minLines = 3,
                    maxLines = 6,
                    enabled = !busy,
                    shape = RoundedCornerShape(12.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name, description) },
                enabled = !busy && name.isNotBlank(),
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("Сохранить")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Отмена") } },
        shape = RoundedCornerShape(20.dp),
    )
}

@Composable
private fun NewBranchDialog(
    busy: Boolean,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Новая ветка") },
        text = {
            Column {
                Text(
                    "Ветка создаётся от текущего состояния и сразу становится текущей.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Имя ветки") },
                    placeholder = { Text("feature/new-ui") },
                    singleLine = true,
                    enabled = !busy,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name) },
                enabled = !busy && name.isNotBlank(),
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("Создать")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Отмена") } },
        shape = RoundedCornerShape(20.dp),
    )
}

@Composable
private fun CommitDialog(
    fileCount: Int,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (message: String, version: String?) -> Unit,
) {
    var message by remember { mutableStateOf("Обновление файлов") }
    var version by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Коммит") },
        text = {
            Column {
                Text(
                    "Будет загружено файлов: $fileCount",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    label = { Text("Сообщение коммита") },
                    minLines = 2,
                    maxLines = 4,
                    enabled = !busy,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp),
                )
                OutlinedTextField(
                    value = version,
                    onValueChange = { version = it },
                    label = { Text("Версия (необязательно)") },
                    placeholder = { Text("1.2.0") },
                    singleLine = true,
                    enabled = !busy,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        message.trim().ifEmpty { "Обновление файлов" },
                        version.trim().takeIf { it.isNotEmpty() },
                    )
                },
                enabled = !busy,
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("Загрузить и закоммитить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Отмена") }
        },
        shape = RoundedCornerShape(20.dp),
    )
}

@Composable
private fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    destructive: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    confirmLabel,
                    color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
        shape = RoundedCornerShape(20.dp),
    )
}

@Composable
private fun FileViewerDialog(
    entry: FileEntry,
    token: String?,
    imageUrl: String,
    loadText: suspend (FileEntry) -> String,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isImage = isImageFile(entry.mime, entry.name)
    val isText = isTextFile(entry.mime, entry.name)
    val isMarkdown = entry.name.endsWith(".md", true) || entry.mime.equals("text/markdown", true)

    var text by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(entry.blobId) {
        if (!isText) return@LaunchedEffect
        loading = true
        error = null
        try {
            val result = loadText(entry)
            val limit = 400_000
            text = if (result.length > limit) {
                result.take(limit) + "\n\n… файл обрезан для просмотра"
            } else {
                result
            }
        } catch (t: Throwable) {
            error = friendlyError(t)
        } finally {
            loading = false
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.fillMaxSize().padding(top = 4.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Закрыть")
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            entry.name.ifBlank { entry.path },
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            entry.path,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = onDownload) {
                        Icon(Icons.Filled.Download, contentDescription = "Скачать")
                    }
                }
                HorizontalDivider()

                when {
                    isImage -> Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(MaterialTheme.colorScheme.surface),
                        contentAlignment = Alignment.Center,
                    ) {
                        AuthorizedImage(
                            url = imageUrl,
                            token = token,
                            contentDescription = entry.name,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                    }

                    isText -> when {
                        loading -> LoadingBox(modifier = Modifier.weight(1f), label = "Читаем файл…")

                        error != null -> Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(16.dp),
                        ) {
                            ErrorBanner(error!!)
                        }

                        text != null -> Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp),
                        ) {
                            if (isMarkdown) {
                                MarkdownText(text!!)
                            } else {
                                SelectionContainer {
                                    Text(
                                        text = text!!,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp,
                                            lineHeight = 18.sp,
                                        ),
                                    )
                                }
                            }
                        }

                        else -> Text("Нет данных", modifier = Modifier.padding(16.dp))
                    }

                    else -> Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            Icons.Filled.InsertDriveFile,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text("Предпросмотр недоступен", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Этот тип файла нельзя открыть в приложении. Скачайте его, чтобы посмотреть.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = onDownload, shape = RoundedCornerShape(12.dp)) {
                            Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Скачать файл")
                        }
                    }
                }
            }
        }
    }
}
