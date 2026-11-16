package com.transvibe.app.data.player

import android.content.Context
import android.net.Uri
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.transvibe.app.domain.subtitle.SubtitleFileNames
import java.io.File
import com.transvibe.app.data.error.ProcessingErrorMapper
import com.transvibe.app.data.subtitle.SubtitleExporter
import com.transvibe.app.domain.model.ProcessingSession
import com.transvibe.app.domain.model.SourceAccessState
import com.transvibe.app.domain.model.SubtitleSegment
import com.transvibe.app.domain.repository.PlayerGateway
import com.transvibe.app.domain.repository.PlayerExportResult
import com.transvibe.app.domain.repository.ProcessingRepository
import kotlinx.coroutines.CancellationException

class PlayerRepository(
    private val context: Context,
    private val processingRepository: ProcessingRepository
) : PlayerGateway {
    override fun checkSourceAccess(session: ProcessingSession): SourceAccessState {
        val accessible = try {
            context.contentResolver.openAssetFileDescriptor(Uri.parse(session.videoUri), "r")?.use { true } ?: false
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            false
        }
        return SourceAccessState(session.id, accessible)
    }

    override fun loadSubtitleWindow(sessionId: String, positionMs: Long): List<SubtitleSegment> {
        val from = (positionMs - 30_000L).coerceAtLeast(0L)
        val to = positionMs + 60_000L
        return processingRepository.loadSegmentsWindow(sessionId, from, to, limit = 100)
    }

    override fun updateSegment(sessionId: String, segment: SubtitleSegment): Boolean =
        processingRepository.updateSegmentText(
            jobId = sessionId,
            segmentId = segment.id,
            originalText = segment.originalText,
            translatedText = segment.translatedText
        )

    override fun exportToPreferredDestination(
        session: ProcessingSession,
        treeUri: String,
        webVtt: Boolean
    ): PlayerExportResult = try {
        val extension = if (webVtt) "vtt" else "srt"
        val mime = if (webVtt) "text/vtt" else "application/x-subrip"
        val safeName = SubtitleFileNames.fileName(session.videoName, webVtt)
        val batches = processingRepository.segmentBatches(session.id, batchSize = 64)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && treeUri.isBlank()) {
            // API 26–28: app-specific external storage does not require broad storage access.
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir, "TransVibe")
            check(dir.exists() || dir.mkdirs()) { "Cannot create subtitle output directory" }
            val output = File(dir, safeName)
            if (webVtt) SubtitleExporter.writeVttBatches(output, batches)
            else SubtitleExporter.writeSrtBatches(output, batches)
        } else {
            val outputUri = if (treeUri.isNotBlank()) {
                val tree = Uri.parse(treeUri)
                val granted = context.contentResolver.persistedUriPermissions.any {
                    it.uri == tree && it.isWritePermission
                }
                check(granted) { "The chosen output folder is no longer accessible. Choose it again in Settings." }
                val parent = DocumentsContract.buildDocumentUriUsingTree(
                    tree, DocumentsContract.getTreeDocumentId(tree)
                )
                DocumentsContract.createDocument(context.contentResolver, parent, mime, safeName)
                    ?: error("Could not create subtitle file in the selected folder")
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // MediaStore.Downloads is available only from Android 10 (API 29).
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/TransVibe")
                }
                context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("Could not create subtitle file in Downloads/TransVibe")
            } else {
                error("MediaStore Downloads is unavailable before Android 10")
            }
            if (webVtt) SubtitleExporter.writeVttBatches(context.contentResolver, outputUri, batches)
            else SubtitleExporter.writeSrtBatches(context.contentResolver, outputUri, batches)
        }
        PlayerExportResult.Success
    } catch (ce: CancellationException) {
        throw ce
    } catch (t: Throwable) {
        PlayerExportResult.Failure(ProcessingErrorMapper.fromThrowable(t))
    }

    override fun export(session: ProcessingSession, destinationUri: String, webVtt: Boolean): PlayerExportResult = try {
        val destination = Uri.parse(destinationUri)
        val batches = processingRepository.segmentBatches(session.id, batchSize = 64)
        if (webVtt) SubtitleExporter.writeVttBatches(context.contentResolver, destination, batches)
        else SubtitleExporter.writeSrtBatches(context.contentResolver, destination, batches)
        PlayerExportResult.Success
    } catch (ce: CancellationException) {
        throw ce
    } catch (t: Throwable) {
        PlayerExportResult.Failure(ProcessingErrorMapper.fromThrowable(t))
    }
}
