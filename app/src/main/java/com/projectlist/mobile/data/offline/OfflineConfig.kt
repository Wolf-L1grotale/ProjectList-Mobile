package com.projectlist.mobile.data.offline

import kotlinx.serialization.Serializable

/** Настройки автономного доступа к данным MinIO по SMB. */
@Serializable
data class OfflineConfig(
    val enabled: Boolean = false,
    val host: String = "10.5.1.50",
    val port: Int = 445,
    val share: String = "Project",
    val basePath: String = "MinIO",
    val domain: String = "",
    val user: String = "",
    val password: String = "",
    val bucket: String = "secure-bucket",
    val encryptionKey: String = "",
) {
    val configured: Boolean
        get() = host.isNotBlank() &&
            share.isNotBlank() &&
            user.isNotBlank() &&
            password.isNotBlank() &&
            bucket.isNotBlank() &&
            encryptionKey.isNotBlank()

    fun disk(): DiskFs = SmbDiskFs(this)

    fun describe(): String = buildString {
        append("\\\\").append(host).append('\\').append(share)
        val base = normalizeDir(basePath).replace('/', '\\')
        if (base.isNotEmpty()) append('\\').append(base)
    }
}
