package com.transvibe.app.integration

import android.app.Application
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.transvibe.app.data.media.VideoSelectionRepository
import com.transvibe.app.data.persistence.LegacySessionMigrator
import com.transvibe.app.data.persistence.RoomProcessingHistoryRepository
import com.transvibe.app.data.persistence.RoomProcessingRepository
import com.transvibe.app.data.persistence.TransVibeDatabase
import com.transvibe.app.data.player.PlayerRepository
import com.transvibe.app.data.processing.ProcessingServiceController
import com.transvibe.app.data.processing.ProcessingTaskController
import com.transvibe.app.domain.model.AppPreferences
import com.transvibe.app.domain.model.ProcessingJobState
import com.transvibe.app.domain.model.ProcessingStage
import com.transvibe.app.domain.model.SubtitleSegment
import com.transvibe.app.domain.repository.PlayerExportResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ProcessingWorkflowIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val app get() = context.applicationContext as Application
    private val isolatedStorageContext get() = InstrumentationRegistry.getInstrumentation().context
    private val testFiles = mutableListOf<File>()
    private lateinit var dbName: String
    private lateinit var db: TransVibeDatabase
    private lateinit var history: RoomProcessingHistoryRepository
    private lateinit var repository: RoomProcessingRepository
    private lateinit var player: PlayerRepository
    private lateinit var service: FakeServiceController

    @Before
    fun setUp() {
        dbName = "workflow-${UUID.randomUUID()}.db"
        openDatabase()
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
        context.deleteDatabase(dbName)
        testFiles.forEach { it.delete() }
        testFiles.clear()
    }

    @Test
    fun selectStartPersistCompleteOpenHistoryOpenPlayerAndExport() = runBlocking {
        val source = testFile("workflow-source.mp4").apply { writeBytes(ByteArray(32) { it.toByte() }) }
        val sourceUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", source)
        val selected = VideoSelectionRepository(context).select(sourceUri.toString())
        assertTrue(selected.displayName.isNotBlank())

        val controller = ProcessingTaskController(app, repository, service)
        assertTrue(controller.start(selected, "tiny", AppPreferences(videoSpeechLanguage = "en", subtitleTargetLanguage = "ar")).isSuccess)
        val job = requireNotNull(repository.getCurrent())
        assertEquals(ProcessingJobState.QUEUED, job.state)
        assertEquals(job.jobId, service.startedJobIds.single())

        repository.updateCheckpoint(job.jobId, ProcessingJobState.RUNNING, 0f, stage = ProcessingStage.RECOGNIZING)
        repository.persistCompletedChunk(
            jobId = job.jobId,
            segments = listOf(
                SubtitleSegment(1, 0, 1_800, "Hello", "Hello", "مرحباً"),
                SubtitleSegment(2, 2_000, 3_700, "World", "World", "العالم")
            ),
            firstSegmentIndex = 0,
            lastCompletedMediaTimestampMs = 3_700,
            progress = 65f,
            detectedLanguage = "en"
        )
        assertEquals(2, repository.segmentCount(job.jobId))

        repository.completePersistedSession(
            jobId = job.jobId,
            sourceUriPersistable = selected.persistableAccess,
            detectedLanguage = "en",
            srtPath = "",
            vttPath = ""
        )

        val summary = history.listSummaries().single { it.jobId == job.jobId }
        assertEquals(ProcessingJobState.COMPLETED, summary.state)
        val session = history.getSession(job.jobId)
        assertNotNull(session)
        assertTrue(session!!.segments.isEmpty()) // bodies stay in Room; player loads a bounded window.

        val window = player.loadSubtitleWindow(job.jobId, 2_500)
        assertEquals(listOf(1L, 2L), window.map { it.id })

        val exportFile = testFile("workflow-${job.jobId}.srt").apply { delete(); createNewFile() }
        val exportUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", exportFile)
        assertEquals(PlayerExportResult.Success, player.export(session, exportUri.toString(), webVtt = false))
        val exported = exportFile.readText()
        assertTrue(exported.contains("مرحباً"))
        assertTrue(exported.contains("العالم"))
        assertFalse(exported.contains("["))
    }

    @Test
    fun cancelPersistsTerminalState() = runBlocking {
        val source = testFile("cancel-source.mp4").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", source)
        val selected = VideoSelectionRepository(context).select(uri.toString())
        val controller = ProcessingTaskController(app, repository, service)
        assertTrue(controller.start(selected, "tiny", AppPreferences()).isSuccess)
        val job = requireNotNull(repository.getCurrent())
        repository.updateCheckpoint(job.jobId, ProcessingJobState.RUNNING, 10f, stage = ProcessingStage.PREPARING_AUDIO)

        controller.cancel()

        assertTrue(service.cancelled)
        assertEquals(ProcessingJobState.CANCELLED, repository.get(job.jobId)?.state)
    }

    @Test
    fun processRestartDetectsInterruptedJobAndResumeRequeuesIt() = runBlocking {
        val source = testFile("restart-source.mp4").apply { writeBytes(byteArrayOf(7, 8, 9)) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", source)
        val selected = VideoSelectionRepository(context).select(uri.toString())
        var controller = ProcessingTaskController(app, repository, service)
        assertTrue(controller.start(selected, "tiny", AppPreferences()).isSuccess)
        val job = requireNotNull(repository.getCurrent())
        repository.updateCheckpoint(job.jobId, ProcessingJobState.RUNNING, 30f, stage = ProcessingStage.RECOGNIZING)
        repository.persistCompletedChunk(
            job.jobId,
            listOf(SubtitleSegment(1, 0, 5_000, "checkpoint", "checkpoint", "نقطة حفظ")),
            0,
            5_000,
            30f,
            "en"
        )

        // Simulate process death: close the DB and reconstruct all persistence/repository objects.
        db.close()
        openDatabase()
        val interrupted = repository.reconcileRunningJobs(isServiceActive = { false })
        assertEquals(1, interrupted)
        assertEquals(ProcessingJobState.INTERRUPTED, repository.get(job.jobId)?.state)

        service = FakeServiceController(repository)
        controller = ProcessingTaskController(app, repository, service)
        assertTrue(controller.resume().isSuccess)
        assertEquals(ProcessingJobState.QUEUED, repository.get(job.jobId)?.state)
        assertEquals(job.jobId, service.startedJobIds.single())
        assertEquals(5_000L, repository.get(job.jobId)?.lastCompletedMediaTimestampMs)
    }

    private fun testFile(name: String): File =
        File(context.cacheDir, "transvibe-integration-${UUID.randomUUID()}-$name")
            .also { testFiles += it }



    private fun openDatabase() {
        db = TransVibeDatabase.createNamedForTests(context, dbName)
        val dao = db.processingDao()
        history = RoomProcessingHistoryRepository(dao, LegacySessionMigrator(isolatedStorageContext, dao))
        repository = RoomProcessingRepository(dao, history)
        player = PlayerRepository(context, repository)
        service = FakeServiceController(repository)
    }

    private class FakeServiceController(
        private val repository: RoomProcessingRepository
    ) : ProcessingServiceController {
        val startedJobIds = mutableListOf<String>()
        var cancelled = false

        override fun start(app: Application, jobId: String) {
            startedJobIds += jobId
        }

        override fun cancel(app: Application) {
            cancelled = true
            repository.getCurrent()?.let { current ->
                repository.finish(current.jobId, ProcessingJobState.CANCELLED, "cancelled-by-integration-test")
            }
        }
    }
}
