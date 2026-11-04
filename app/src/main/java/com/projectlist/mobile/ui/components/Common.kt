package com.projectlist.mobile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.projectlist.mobile.data.Commit
import com.projectlist.mobile.data.Project
import com.projectlist.mobile.data.ProjectStatuses
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// ---------------------------------------------------------------- helpers

private val RU = Locale.forLanguageTag("ru")

fun initialOf(name: String?): String {
    val clean = name?.trim().orEmpty()
    return if (clean.isEmpty()) "?" else clean.take(1).uppercase(RU)
}

private val AvatarPalette = listOf(
    Color(0xFF6366F1),
    Color(0xFF0EA5E9),
    Color(0xFF10B981),
    Color(0xFFF59E0B),
    Color(0xFFEC4899),
    Color(0xFF8B5CF6),
    Color(0xFF14B8A6),
    Color(0xFFF97316),
)

fun avatarBrush(name: String): Brush {
    val index = abs(name.hashCode()) % AvatarPalette.size
    val first = AvatarPalette[index]
    val second = AvatarPalette[(index + 3) % AvatarPalette.size]
    return Brush.linearGradient(listOf(first, second))
}

fun projectProgress(project: Project): Int {
    val tasks = project.data.tasks
    if (tasks.isNotEmpty()) {
        return ((tasks.count { it.done } * 100.0) / tasks.size).roundToInt().coerceIn(0, 100)
    }
    return (project.data.progress ?: 0).coerceIn(0, 100)
}

fun currentBranchName(project: Project): String =
    project.data.currentBranch?.takeIf { it.isNotBlank() }
        ?: project.data.branches.firstOrNull()?.name
        ?: "main"

fun branchHead(project: Project, branch: String = currentBranchName(project)): Commit? {
    val head = project.data.branches.find { it.name == branch }?.head
    return project.data.commits.find { it.id == head }
}

fun headCommit(project: Project): Commit? =
    branchHead(project) ?: project.data.commits.firstOrNull()

fun headFiles(project: Project) = headCommit(project)?.files.orEmpty()

fun formatDate(iso: String?, withTime: Boolean = false): String {
    if (iso.isNullOrBlank()) return "—"
    return try {
        val instant = Instant.parse(iso)
        val pattern = if (withTime) "d MMM, HH:mm" else "d MMM yyyy"
        DateTimeFormatter.ofPattern(pattern, RU)
            .withZone(ZoneId.systemDefault())
            .format(instant)
    } catch (_: Exception) {
        iso.take(10)
    }
}

fun formatRelative(iso: String?): String {
    if (iso.isNullOrBlank()) return ""
    return try {
        val instant = Instant.parse(iso)
        val diff = Duration.between(instant, Instant.now()).toMillis()
        when {
            diff < 60_000 -> "только что"
            diff < 3_600_000 -> "${diff / 60_000} мин назад"
            diff < 86_400_000 -> "${diff / 3_600_000} ч назад"
            diff < 7 * 86_400_000 -> "${diff / 86_400_000} дн назад"
            else -> formatDate(iso)
        }
    } catch (_: Exception) {
        ""
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes Б"
    val units = listOf("КБ", "МБ", "ГБ", "ТБ")
    var value = bytes.toDouble()
    var index = -1
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    val pattern = if (value >= 10) "%.0f %s" else "%.1f %s"
    return String.format(Locale.US, pattern, value, units[index])
}

fun formatUptime(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    val days = total / 86_400
    val hours = (total % 86_400) / 3_600
    val minutes = (total % 3_600) / 60
    return when {
        days > 0 -> "$days д $hours ч"
        hours > 0 -> "$hours ч $minutes мин"
        minutes > 0 -> "$minutes мин"
        else -> "$total с"
    }
}

private val TextExtensions = setOf(
    "txt", "md", "markdown", "log", "json", "xml", "html", "htm", "css", "js", "mjs",
    "ts", "tsx", "jsx", "kt", "kts", "java", "py", "go", "rs", "c", "cpp", "h", "cs",
    "php", "rb", "swift", "yml", "yaml", "toml", "ini", "cfg", "conf", "properties",
    "sh", "bat", "ps1", "sql", "csv", "gradle", "gitignore", "env", "lock",
)

fun isTextFile(mime: String, name: String): Boolean {
    val m = mime.lowercase()
    if (m.startsWith("text/")) return true
    if (m in setOf("application/json", "application/xml", "application/javascript", "application/x-yaml")) {
        return true
    }
    val ext = name.substringAfterLast('.', "").lowercase()
    return ext in TextExtensions
}

fun isImageFile(mime: String, name: String): Boolean {
    if (mime.lowercase().startsWith("image/")) return true
    return name.substringAfterLast('.', "").lowercase() in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "avif")
}

fun fileIcon(name: String, mime: String): ImageVector = when {
    isImageFile(mime, name) -> Icons.Filled.Image
    mime.lowercase() == "application/pdf" || name.endsWith(".pdf", true) -> Icons.Filled.PictureAsPdf
    isTextFile(mime, name) -> Icons.Filled.Description
    name.substringAfterLast('.', "").lowercase() in setOf("sh", "bat", "ps1", "exe", "dll", "so") -> Icons.Filled.Terminal
    else -> Icons.Filled.InsertDriveFile
}

// --------------------------------------------------------------- components

@Composable
fun ProjectAvatar(name: String, size: Int = 44, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size.dp)
            .background(avatarBrush(name), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initialOf(name),
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
fun StatusBadge(status: String?, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val key = ProjectStatuses.normalized(status)
    val container = when (key) {
        ProjectStatuses.PLANNING -> colors.secondaryContainer
        ProjectStatuses.IN_PROGRESS -> colors.primaryContainer
        ProjectStatuses.ON_TRACK -> colors.tertiaryContainer
        else -> colors.surfaceVariant
    }
    val content = when (key) {
        ProjectStatuses.PLANNING -> colors.onSecondaryContainer
        ProjectStatuses.IN_PROGRESS -> colors.onPrimaryContainer
        ProjectStatuses.ON_TRACK -> colors.onTertiaryContainer
        else -> colors.onSurfaceVariant
    }
    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(50),
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Box(Modifier.size(6.dp).background(content.copy(alpha = 0.75f), CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(
                text = ProjectStatuses.label(status),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
        }
    }
}

@Composable
fun SectionCard(
    title: String? = null,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (title != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        if (!subtitle.isNullOrBlank()) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    action?.invoke()
                }
                Spacer(Modifier.height(12.dp))
            }
            content()
        }
    }
}

@Composable
fun StatTile(
    icon: ImageVector,
    value: String,
    label: String,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = tint.copy(alpha = 0.10f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.height(8.dp))
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(64.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(30.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}

@Composable
fun ErrorBanner(message: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.CloudOff, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            if (onRetry != null) {
                Spacer(Modifier.width(8.dp))
                Button(onClick = onRetry, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                    Text("Повторить", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier, label: String = "Загрузка…") {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
        Spacer(Modifier.height(12.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun KeyValueRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1.2f),
        )
    }
}
