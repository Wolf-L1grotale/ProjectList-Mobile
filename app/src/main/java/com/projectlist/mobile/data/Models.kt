package com.projectlist.mobile.data

import kotlinx.serialization.Serializable

@Serializable
data class Project(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val data: ProjectData = ProjectData(),
    val createdAt: String = "",
    val updatedAt: String? = null,
)

@Serializable
data class ProjectData(
    val status: String? = null,
    val progress: Int? = null,
    val tasks: List<Task> = emptyList(),
    val commits: List<Commit> = emptyList(),
    val branches: List<Branch> = emptyList(),
    val currentBranch: String? = null,
    val head: String? = null,
    val version: String? = null,
    val versions: Map<String, VersionMeta> = emptyMap(),
    val releases: List<Release> = emptyList(),
    val dependencies: List<Dependency> = emptyList(),
    val screenshots: List<Screenshot> = emptyList(),
)

@Serializable
data class Task(
    val title: String = "",
    val done: Boolean = false,
)

@Serializable
data class FileEntry(
    val path: String = "",
    val name: String = "",
    val blobId: String = "",
    val size: Long = 0,
    val sha: String = "",
    val mime: String = "",
)

@Serializable
data class Changes(
    val added: List<String> = emptyList(),
    val modified: List<String> = emptyList(),
    val removed: List<String> = emptyList(),
)

@Serializable
data class CommitStats(
    val files: Int = 0,
    val bytes: Long = 0,
)

@Serializable
data class Commit(
    val id: String = "",
    val version: String = "",
    val message: String = "",
    val date: String = "",
    val parent: String? = null,
    val mergeOf: String? = null,
    val branch: String = "",
    val files: List<FileEntry> = emptyList(),
    val changes: Changes = Changes(),
    val stats: CommitStats = CommitStats(),
)

@Serializable
data class Branch(
    val name: String = "",
    val head: String? = null,
    val createdAt: String = "",
    val mergedInto: String? = null,
    val mergedHead: String? = null,
)

@Serializable
data class VersionMeta(
    val name: String = "",
    val description: String = "",
    val createdAt: String = "",
)

@Serializable
data class Release(
    val id: String = "",
    val version: String = "",
    val name: String = "",
    val description: String = "",
    val fromVersion: String = "",
    val createdAt: String = "",
)

@Serializable
data class Dependency(
    val projectId: String = "",
    val pinnedVersion: String? = null,
    val note: String = "",
    val createdAt: String = "",
)

@Serializable
data class Screenshot(
    val id: String = "",
    val name: String = "",
    val blobId: String = "",
    val size: Long = 0,
    val mime: String = "",
    val addedAt: String = "",
)

@Serializable
data class NotificationItem(
    val id: String = "",
    val level: String = "info",
    val category: String = "system",
    val message: String = "",
    val createdAt: String = "",
    val updatedAt: String = "",
    val count: Int = 1,
    val read: Boolean = false,
    val readAt: String? = null,
    val source: String = "",
)

@Serializable
data class NotificationsResponse(
    val ok: Boolean = false,
    val notifications: List<NotificationItem> = emptyList(),
    val unread: Int = 0,
    val total: Int = 0,
)

@Serializable
data class ProjectsResponse(
    val items: List<Project> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val limit: Int = 50,
    val pages: Int = 1,
)

@Serializable
data class UnlockResponse(
    val ok: Boolean = false,
    val token: String = "",
    val admin: Boolean = false,
    val name: String? = null,
)

@Serializable
data class SessionInfo(
    val ok: Boolean = false,
    val admin: Boolean = false,
    val createdAt: String = "",
)

@Serializable
data class VersionInfo(
    val ok: Boolean = false,
    val name: String = "",
    val version: String = "",
    val maxUploadMb: Int = 100,
    val maxBatchFiles: Int = 500,
    val maxBatchRawMb: Int = 10,
    val uptime: Double = 0.0,
)

@Serializable
data class HealthInfo(
    val ok: Boolean = false,
    val minio: String = "",
    val serverTime: String = "",
)

@Serializable
data class KeyStatus(
    val available: Boolean = false,
    val locked: Boolean = false,
    val deviceId: String? = null,
    val fingerprint: String? = null,
)

@Serializable
data class LogEntry(
    val t: String = "",
    val level: String = "info",
    val category: String = "",
    val message: String = "",
)

@Serializable
data class LogsResponse(
    val entries: List<LogEntry> = emptyList(),
    val total: Int = 0,
)

@Serializable
data class CommitResponse(
    val commit: Commit = Commit(),
    val project: Project = Project(),
    val branch: String = "",
)

@Serializable
data class ProjectResponse(
    val project: Project = Project(),
)

@Serializable
data class ScreenshotResponse(
    val screenshot: Screenshot = Screenshot(),
    val project: Project = Project(),
)

/** Файл, подготовленный к загрузке на сервер. */
data class UploadFile(
    val path: String,
    val mime: String,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UploadFile) return false
        return path == other.path && mime == other.mime && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = path.hashCode()
        result = 31 * result + mime.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}

object ProjectStatuses {
    const val PLANNING = "planning"
    const val IN_PROGRESS = "in-progress"
    const val ON_TRACK = "on-track"
    const val COMPLETE = "complete"

    val all = listOf(PLANNING, IN_PROGRESS, ON_TRACK, COMPLETE)

    fun label(key: String?): String = when (key) {
        PLANNING -> "Планирование"
        IN_PROGRESS -> "В работе"
        ON_TRACK -> "По плану"
        COMPLETE -> "Завершён"
        else -> "В работе"
    }

    fun normalized(key: String?): String = if (key in all) key!! else IN_PROGRESS
}
