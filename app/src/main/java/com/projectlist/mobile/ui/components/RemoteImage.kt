package com.projectlist.mobile.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.projectlist.mobile.PLApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val OFFLINE_PREFIX = "offline://"

/**
 * Изображение с сервера ProjectList: блоб отдаётся только с заголовком авторизации,
 * поэтому передаём токен сессии в Coil. Ссылки `offline://` читаются напрямую
 * из MinIO по SMB через репозиторий.
 */
@Composable
fun AuthorizedImage(
    url: String,
    token: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    if (url.startsWith(OFFLINE_PREFIX)) {
        OfflineBlobImage(
            url = url,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale,
        )
        return
    }

    val context = LocalContext.current
    val model = remember(url, token) {
        ImageRequest.Builder(context)
            .data(url)
            .apply {
                if (!token.isNullOrBlank()) {
                    addHeader("X-Session-Token", token)
                }
            }
            .crossfade(true)
            .build()
    }
    AsyncImage(
        model = model,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
    )
}

@Composable
private fun OfflineBlobImage(
    url: String,
    contentDescription: String?,
    modifier: Modifier,
    contentScale: ContentScale,
) {
    val context = LocalContext.current
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(url) { mutableStateOf(false) }

    LaunchedEffect(url) {
        val app = context.applicationContext as? PLApp
        val rest = url.removePrefix(OFFLINE_PREFIX)
        val slash = rest.indexOf('/')
        if (app == null || slash <= 0 || slash == rest.lastIndex) {
            failed = true
            return@LaunchedEffect
        }
        val projectId = rest.substring(0, slash)
        val blobId = rest.substring(slash + 1)
        val decoded = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = app.container.repository.downloadBlob(projectId, blobId)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        }
        bitmap = decoded
        failed = decoded == null
    }

    val image = bitmap
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale,
        )
    } else {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (!failed) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}
