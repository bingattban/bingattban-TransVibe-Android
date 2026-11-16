package com.transvibe.app.service

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.transvibe.app.data.subtitle.SubtitleExporter
import com.transvibe.app.domain.subtitle.SubtitleFileNames
import com.transvibe.app.domain.repository.ProcessingRepository
import com.transvibe.app.domain.repository.SettingsRepository
import java.io.File
import kotlinx.coroutines.flow.first

internal data class AutomaticSubtitleDestinations(val srt: String, val vtt: String)

internal data class ExportedSubtitleFiles(
    val srt: File,
    val vtt: File
)

internal class SubtitleExportCoordinator(
    private val context: Context,
    private val processingRepository: ProcessingRepository,
    private val settingsRepository: SettingsRepository
) {
    fun sessionDirectory(jobId: String): File =
        File(context.filesDir, "sessions/$jobId").apply { mkdirs() }

    suspend fun exportSession(jobId: String, videoName: String, dir: File): ExportedSubtitleFiles {
        val srt = File(dir, SubtitleFileNames.fileName(videoName, false))
        val vtt = File(dir, SubtitleFileNames.fileName(videoName, true))
        SubtitleExporter.writeSrtBatches(srt, processingRepository.segmentBatches(jobId, EXPORT_BATCH_SEGMENTS))
        SubtitleExporter.writeVttBatches(vtt, processingRepository.segmentBatches(jobId, EXPORT_BATCH_SEGMENTS))
        return ExportedSubtitleFiles(srt = srt, vtt = vtt)
    }

    fun isSourcePersistable(uri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }

    suspend fun exportAutomaticCopies(jobId: String, videoName: String): AutomaticSubtitleDestinations {
        val prefs = settingsRepository.preferences.first()
        val srtName = SubtitleFileNames.fileName(videoName, false)
        val vttName = SubtitleFileNames.fileName(videoName, true)
        val batches = { processingRepository.segmentBatches(jobId, EXPORT_BATCH_SEGMENTS) }

        if (prefs.subtitleOutputTreeUri.isNotBlank()) {
            val tree = Uri.parse(prefs.subtitleOutputTreeUri)
            val parent = DocumentsContract.buildDocumentUriUsingTree(
                tree,
                DocumentsContract.getTreeDocumentId(tree)
            )
            val srtUri = DocumentsContract.createDocument(
                context.contentResolver,
                parent,
                "application/x-subrip",
                srtName
            ) ?: error("تعذر إنشاء ملف SRT في المجلد المختار")
            val vttUri = DocumentsContract.createDocument(
                context.contentResolver,
                parent,
                "text/vtt",
                vttName
            ) ?: error("تعذر إنشاء ملف VTT في المجلد المختار")
            SubtitleExporter.writeSrtBatches(context.contentResolver, srtUri, batches())
            SubtitleExporter.writeVttBatches(context.contentResolver, vttUri, batches())
            return AutomaticSubtitleDestinations(srtUri.toString(), vttUri.toString())
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            fun createDownload(name: String, mime: String): Uri {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(
                        MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/TransVibe"
                    )
                }
                return context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("تعذر إنشاء ملف في Downloads/TransVibe")
            }
            val srtUri = createDownload(srtName, "application/x-subrip")
            val vttUri = createDownload(vttName, "text/vtt")
            SubtitleExporter.writeSrtBatches(context.contentResolver, srtUri, batches())
            SubtitleExporter.writeVttBatches(context.contentResolver, vttUri, batches())
            return AutomaticSubtitleDestinations(srtUri.toString(), vttUri.toString())
        } else {
            // Android 8/9: use app-specific documents; no dangerous storage permission needed.
            val dir = File(
                context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir,
                "TransVibe"
            )
            check(dir.isDirectory || dir.mkdirs()) { "تعذر إنشاء مجلد تصدير الترجمة" }
            SubtitleExporter.writeSrtBatches(File(dir, srtName), batches())
            SubtitleExporter.writeVttBatches(File(dir, vttName), batches())
            return AutomaticSubtitleDestinations(File(dir, srtName).absolutePath, File(dir, vttName).absolutePath)
        }
    }

    private companion object {
        const val EXPORT_BATCH_SEGMENTS = 64
    }
}
