package com.transvibe.app.data.media

import android.content.Context
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.transvibe.app.domain.model.SelectedVideo
import com.transvibe.app.domain.repository.VideoSelectionGateway

class VideoSelectionRepository(private val context: Context) : VideoSelectionGateway {
    override fun select(uriString: String): SelectedVideo {
        val uri = Uri.parse(uriString)
        require(uri.scheme == ContentResolver.SCHEME_CONTENT) { "Only content:// media URIs are accepted" }
        val resolver = context.contentResolver
        val persistable = runCatching {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        }.getOrDefault(false)
        val metadata = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                val name = c.getString(0)?.takeIf { it.isNotBlank() } ?: "فيديو"
                val size = if (!c.isNull(1)) c.getLong(1).takeIf { it >= 0L } else null
                name to size
            }
        }.getOrNull()
        return SelectedVideo(
            uriString = uri.toString(),
            displayName = metadata?.first ?: "فيديو",
            persistableAccess = persistable,
            sizeBytes = metadata?.second
        )
    }
}
