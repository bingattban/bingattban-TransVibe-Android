package com.transvibe.app.data.persistence

import android.test.InstrumentationTestCase
import com.transvibe.app.domain.model.ProcessingJobState
import kotlinx.coroutines.runBlocking
import java.io.File

@Suppress("DEPRECATION")
class PersistenceInstrumentationTest : InstrumentationTestCase() {
    private lateinit var db: TransVibeDatabase
    private lateinit var dao: ProcessingDao

    override fun setUp() {
        super.setUp()
        db = TransVibeDatabase.createInMemoryForTests(instrumentation.context)
        dao = db.processingDao()
        File(instrumentation.context.filesDir, "sessions").deleteRecursively()
    }

    override fun tearDown() {
        db.close()
        File(instrumentation.context.filesDir, "sessions").deleteRecursively()
        super.tearDown()
    }

    fun testDeletingHistoryCleansOnlyPrivateSessionOutputs() {
        val testContext = instrumentation.context
        val root = File(testContext.filesDir, "sessions").apply { mkdirs() }
        val deletedJob = "delete-private-output"
        val retainedJob = "another-session"
        val privateDir = File(root, deletedJob).apply { mkdirs() }
        File(privateDir, "video.srt").writeText("internal")
        val sibling = File(root, retainedJob).apply { mkdirs() }
        val siblingFile = File(sibling, "video.srt").apply { writeText("keep") }
        val export = File(testContext.cacheDir, "user-export-${System.nanoTime()}.srt")
            .apply { writeText("exported") }
        try {
            dao.insertJob(job(deletedJob).copy(state = ProcessingJobState.COMPLETED.name,
                srtPath = export.absolutePath))
            dao.insertJob(job(retainedJob).copy(state = ProcessingJobState.COMPLETED.name))
            val history = RoomProcessingHistoryRepository(dao, LegacySessionMigrator(testContext, dao))
            assertTrue(history.deleteSession(deletedJob))
            assertNull(dao.getJob(deletedJob))
            assertFalse(privateDir.exists())
            assertTrue(siblingFile.exists())
            assertTrue(export.exists())
        } finally {
            export.delete()
        }
    }

    fun testRoomInsertionAndJobUpdate() {
        val job = job("job-1")
        dao.insertCurrentJob(job)
        assertEquals("job-1", dao.getCurrentJob()?.jobId)
        dao.updateCheckpoint("job-1", ProcessingJobState.RUNNING.name, "RECOGNIZING", 42f, null, 1234L)
        val updated = dao.getJob("job-1")!!
        assertEquals(ProcessingJobState.RUNNING.name, updated.state)
        assertEquals("RECOGNIZING", updated.stage)
        assertEquals(42f, updated.progress)
    }

    fun testDirtyTailReplacementRemovesStaleRows() {
        dao.insertJob(job("job-tail").copy(state = ProcessingJobState.RUNNING.name))
        dao.insertSegments(
            listOf(
                segment("job-tail", 0, 0L),
                segment("job-tail", 1, 1000L),
                segment("job-tail", 2, 2000L),
                segment("job-tail", 3, 3000L)
            )
        )
        dao.persistCompletedChunk(
            jobId = "job-tail",
            segments = listOf(
                segment("job-tail", 2, 2000L).copy(originalText = "merged", englishText = "merged"),
            ),
            progress = 50f,
            timestamp = 50L,
            detectedLanguage = "en",
            lastCompletedMediaTimestampMs = 3_500L,
            lastPersistedSegmentIndex = 2
        )
        val rows = dao.getSegments("job-tail")
        assertEquals(listOf(0, 1, 2), rows.map { it.segmentIndex })
        assertEquals("merged", rows.last().originalText)
    }

    fun testSegmentOrderingAndPagination() {
        dao.insertJob(job("job-pages"))
        dao.insertSegments(
            listOf(
                segment("job-pages", 3, 3000L),
                segment("job-pages", 1, 1000L),
                segment("job-pages", 2, 2000L),
                segment("job-pages", 0, 0L)
            )
        )
        assertEquals(listOf(0, 1, 2, 3), dao.getSegments("job-pages").map { it.segmentIndex })
        val first = dao.getSegmentBatchAfter("job-pages", -1, 2)
        val second = dao.getSegmentBatchAfter("job-pages", first.last().segmentIndex, 2)
        assertEquals(listOf(0, 1), first.map { it.segmentIndex })
        assertEquals(listOf(2, 3), second.map { it.segmentIndex })
    }

    fun testCorruptedLegacyDataIsRecordedWithoutCrashingMigration() {
        val dir = File(instrumentation.context.filesDir, "sessions").apply { mkdirs() }
        File(dir, "broken.json").writeText("{ definitely-not-json")
        LegacySessionMigrator(instrumentation.context, dao).migrateOnce()
        val receipt = dao.getLegacyMigration("broken.json")
        assertNotNull(receipt)
        assertEquals(LegacySessionMigrator.STATUS_MALFORMED, receipt?.status)
        assertNotNull(dao.getMigrationMarker(LegacySessionMigrator.MIGRATION_NAME))
    }

    fun testLegacyMigrationIsIdempotentAcrossProcessStyleRestart() {
        val dir = File(instrumentation.context.filesDir, "sessions").apply { mkdirs() }
        File(dir, "legacy.json").writeText(validLegacyJson("legacy-job"))
        LegacySessionMigrator(instrumentation.context, dao).migrateOnce()
        assertEquals(2, dao.countSegments("legacy-job"))

        // New migrator instance simulates a recreated process after a completed migration.
        LegacySessionMigrator(instrumentation.context, dao).migrateOnce()
        assertEquals(2, dao.countSegments("legacy-job"))
        assertEquals(LegacySessionMigrator.STATUS_SUCCESS, dao.getLegacyMigration("legacy.json")?.status)
    }


    fun testResumeTrimsOnlySegmentsAfterOverlapPoint() {
        dao.insertJob(job("job-resume").copy(state = ProcessingJobState.RUNNING.name))
        dao.insertSegments(
            listOf(
                segment("job-resume", 0, 0L),
                segment("job-resume", 1, 1000L),
                segment("job-resume", 2, 2000L),
                segment("job-resume", 3, 3000L)
            )
        )
        val tail = dao.trimForResume("job-resume", 2500L, 10L)
        assertEquals(listOf(0, 1), dao.getSegments("job-resume").map { it.segmentIndex })
        assertEquals(1, tail.first)
        assertEquals(1L, tail.second)
    }

    fun testCancelAndFailureAreTerminalAndIdempotent() {
        dao.insertJob(job("job-cancel").copy(state = ProcessingJobState.RUNNING.name))
        assertEquals(1, dao.finishJob("job-cancel", ProcessingJobState.CANCELLED.name, "cancelled", 20L))
        assertEquals(0, dao.finishJob("job-cancel", ProcessingJobState.FAILED.name, "unknown", 21L))
        assertEquals(ProcessingJobState.CANCELLED.name, dao.getJob("job-cancel")?.state)

        dao.insertJob(job("job-fail").copy(state = ProcessingJobState.RUNNING.name))
        assertEquals(1, dao.finishJob("job-fail", ProcessingJobState.FAILED.name, "translation_failure", 22L))
        assertEquals(ProcessingJobState.FAILED.name, dao.getJob("job-fail")?.state)
    }

    /** Cancellation is terminal even if the running worker posts a delayed checkpoint. */
    fun testLateWorkerCheckpointCannotResurrectCancelledJob() {
        dao.insertJob(job("cancel-race").copy(state = ProcessingJobState.RUNNING.name))
        assertEquals(1, dao.finishJob("cancel-race", ProcessingJobState.CANCELLED.name, "cancelled", 40L))
        assertEquals(0, dao.updateCheckpoint(
            "cancel-race", ProcessingJobState.RUNNING.name, "RECOGNIZING", 85f, null, 41L
        ))
        val cancelled = dao.getJob("cancel-race")!!
        assertEquals(ProcessingJobState.CANCELLED.name, cancelled.state)
        assertEquals("cancelled", cancelled.errorCode)
        assertEquals(0, dao.finishJob("cancel-race", ProcessingJobState.FAILED.name, "other", 42L))
    }

    fun testEarlyQueuedCancellationCannotBeRevertedByWorkerStartup() {
        dao.insertJob(job("early-cancel"))
        assertEquals(1, dao.finishJob("early-cancel", ProcessingJobState.CANCELLED.name, "cancelled", 30L))
        assertEquals(0, dao.updateCheckpoint(
            "early-cancel", ProcessingJobState.RUNNING.name, "INSPECTING_MEDIA", 1f, null, 31L
        ))
        assertEquals(ProcessingJobState.CANCELLED.name, dao.getJob("early-cancel")?.state)
    }

    fun testInterruptedJobCanBeRequeuedForResume() {
        dao.insertJob(job("job-interrupted").copy(state = ProcessingJobState.INTERRUPTED.name, lastCompletedMediaTimestampMs = 5_000L))
        assertEquals(1, dao.requeueInterrupted("job-interrupted", 30L))
        val restored = dao.getJob("job-interrupted")!!
        assertEquals(ProcessingJobState.QUEUED.name, restored.state)
        assertEquals(5_000L, restored.lastCompletedMediaTimestampMs)
    }

    private fun job(id: String) = ProcessingJobEntity(
        jobId = id,
        sourceUri = "content://test/$id",
        displayName = "$id.mp4",
        selectedModelId = "tiny",
        sourceLanguage = "en",
        targetLanguage = "ar",
        processingMode = "FAST",
        state = ProcessingJobState.QUEUED.name,
        progress = 0f,
        checkpointTimestamp = 1L,
        errorCode = null,
        createdTimestamp = 1L,
        updatedTimestamp = 1L
    )

    private fun segment(jobId: String, index: Int, start: Long) = SubtitleSegmentEntity(
        segmentId = index.toLong(),
        jobId = jobId,
        segmentIndex = index,
        startTimeMs = start,
        endTimeMs = start + 900,
        originalText = "segment-$index",
        englishText = "segment-$index",
        targetTranslatedText = "مقطع-$index"
    )

    private fun validLegacyJson(id: String) = """
        {
          "id":"$id",
          "videoUri":"content://legacy/video",
          "videoName":"legacy.mp4",
          "modelId":"tiny",
          "detectedLanguage":"en",
          "createdAtEpochMs":123456789,
          "srtPath":"/tmp/legacy.srt",
          "vttPath":"/tmp/legacy.vtt",
          "sourceUriPersistable":false,
          "outcome":"COMPLETED",
          "segments":[
            {"id":1,"startMs":0,"endMs":1000,"originalText":"one","englishText":"one","arabicText":"واحد"},
            {"id":2,"startMs":1100,"endMs":2000,"originalText":"two","englishText":"two","arabicText":"اثنان"}
          ]
        }
    """.trimIndent()
}
