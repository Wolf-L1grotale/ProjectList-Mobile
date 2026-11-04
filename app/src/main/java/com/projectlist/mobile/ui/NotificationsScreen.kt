package com.projectlist.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.projectlist.mobile.data.NotificationItem
import com.projectlist.mobile.ui.components.EmptyState
import com.projectlist.mobile.ui.components.ErrorBanner
import com.projectlist.mobile.ui.components.LoadingBox
import com.projectlist.mobile.ui.components.formatRelative

@Composable
fun NotificationsScreen(
    state: NotificationsUiState,
    onMarkRead: (NotificationItem) -> Unit,
    onDelete: (NotificationItem) -> Unit,
    onMarkAllRead: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "summary") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (state.unread > 0) "Непрочитанных: ${state.unread}" else "Все уведомления прочитаны",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                if (state.unread > 0) {
                    Text(
                        "Прочитать все",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable(onClick = onMarkAllRead)
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                }
            }
        }

        if (state.error != null) {
            item(key = "error") { ErrorBanner(state.error, onRetry = onRefresh) }
        }

        when {
            state.loading && state.items.isEmpty() -> item(key = "loading") {
                LoadingBox(label = "Загружаем уведомления…")
            }

            state.items.isEmpty() -> item(key = "empty") {
                EmptyState(
                    icon = Icons.Filled.Notifications,
                    title = "Уведомлений нет",
                    subtitle = "Здесь появятся события сервера: загрузки, коммиты и ошибки.",
                )
            }

            else -> items(state.items, key = { it.id }) { item ->
                NotificationCard(
                    item = item,
                    onClick = { onMarkRead(item) },
                    onDelete = { onDelete(item) },
                )
            }
        }
    }
}

@Composable
private fun NotificationCard(
    item: NotificationItem,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val levelColor = levelColor(item.level)
    val container = if (item.read) {
        MaterialTheme.colorScheme.surface
    } else {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = container),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 5.dp)
                    .size(10.dp)
                    .background(if (item.read) MaterialTheme.colorScheme.outline else levelColor, CircleShape),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = categoryLabel(item.category),
                        style = MaterialTheme.typography.labelSmall,
                        color = levelColor,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (item.count > 1) {
                        Text(
                            "  ×${item.count}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        formatRelative(item.updatedAt.ifBlank { item.createdAt }),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    item.message,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (item.read) FontWeight.Normal else FontWeight.Medium,
                    modifier = Modifier.padding(top = 4.dp, end = 4.dp),
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Удалить уведомление",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (!item.read) {
            Row(
                modifier = Modifier.padding(start = 36.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Done,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "Отметить прочитанным",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun levelColor(level: String): Color = when (level) {
    "error" -> MaterialTheme.colorScheme.error
    "warn" -> Color(0xFFF59E0B)
    "debug" -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> MaterialTheme.colorScheme.primary
}

private fun categoryLabel(category: String): String = when (category) {
    "system" -> "Система"
    "minio" -> "Хранилище"
    "files" -> "Файлы"
    "projects" -> "Проекты"
    "screenshots" -> "Скриншоты"
    "auth" -> "Доступ"
    else -> category.ifBlank { "Событие" }
}
