package com.projectlist.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.PendingActions
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.projectlist.mobile.data.Project
import com.projectlist.mobile.ui.components.EmptyState
import com.projectlist.mobile.ui.components.ErrorBanner
import com.projectlist.mobile.ui.components.LoadingBox
import com.projectlist.mobile.ui.components.SectionCard
import com.projectlist.mobile.ui.components.StatTile
import com.projectlist.mobile.ui.components.projectProgress

@Composable
fun TasksScreen(
    state: ProjectsUiState,
    onOpenProject: (String) -> Unit,
    onToggleTask: (projectId: String, index: Int) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val groups = state.filtered.filter { it.data.tasks.isNotEmpty() }
    val total = state.totalTasks
    val done = state.doneTasks

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "stats") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile(
                    icon = Icons.Filled.Checklist,
                    value = total.toString(),
                    label = "Всего задач",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    icon = Icons.Filled.CheckCircle,
                    value = done.toString(),
                    label = "Выполнено",
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    icon = Icons.Filled.PendingActions,
                    value = (total - done).coerceAtLeast(0).toString(),
                    label = "Осталось",
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (state.error != null) {
            item(key = "error") { ErrorBanner(state.error, onRetry = onRefresh) }
        }

        when {
            state.loading && state.projects.isEmpty() -> item(key = "loading") {
                LoadingBox(label = "Загружаем задачи…")
            }

            groups.isEmpty() -> item(key = "empty") {
                EmptyState(
                    icon = Icons.Filled.Checklist,
                    title = "Задач пока нет",
                    subtitle = "Откройте проект и добавьте задачи на вкладке «Задачи».",
                )
            }

            else -> items(groups, key = { it.id }) { project ->
                ProjectTasksCard(
                    project = project,
                    onOpenProject = { onOpenProject(project.id) },
                    onToggleTask = { index -> onToggleTask(project.id, index) },
                )
            }
        }
    }
}

@Composable
private fun ProjectTasksCard(
    project: Project,
    onOpenProject: () -> Unit,
    onToggleTask: (Int) -> Unit,
) {
    val tasks = project.data.tasks
    val done = tasks.count { it.done }
    val progress = projectProgress(project)

    SectionCard(
        title = project.name.ifBlank { "Без названия" },
        subtitle = "$done из ${tasks.size} · $progress%",
        action = {
            Text(
                "Открыть",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable(onClick = onOpenProject)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            tasks.forEachIndexed { index, task ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleTask(index) }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = task.done,
                        onCheckedChange = null,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = task.title.ifBlank { "Без названия" },
                        style = MaterialTheme.typography.bodyMedium,
                        textDecoration = if (task.done) TextDecoration.LineThrough else TextDecoration.None,
                        color = if (task.done) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
