package com.transvibe.app.performance

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.transvibe.app.data.persistence.LegacySessionMigrator
import com.transvibe.app.data.persistence.ProcessingJobEntity
import com.transvibe.app.data.persistence.RoomProcessingHistoryRepository
import com.transvibe.app.data.persistence.RoomProcessingRepository
import com.transvibe.app.data.persistence.TransVibeDatabase
import com.transvibe.app.domain.model.ProcessingJobState
import com.transvibe.app.domain.model.ProcessingStage
import com.transvibe.app.domain.model.SubtitleSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BoundedSubtitleMemoryInstrumentationTest {
    @Test
    fun largeSubtitleHistoryIsReadInBoundedBatchesAndWindows() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = TransVibeDatabase.createInMemoryForTests(context)
        try {
            val dao = db.processingDao()
            val history = RoomProcessingHistoryRepository(dao, LegacySessionMigrator(InstrumentationRegistry.getInstrumentation().context, dao))
            val repository = RoomProcessingRepository(dao, history)
            val jobId = "memory-bounded"
            dao.insertJob(
                ProcessingJobEntity(
                    jobId = jobId,
                    sourceUri = "content://test/source",
                    displayName = "long.mp4",
                    selectedModelId = "tiny",
                    sourceLanguage = "en",
                    targetLanguage = "ar",
                    processingMode = "FAST",
                    state = ProcessingJobState.RUNNING.name,
                    stage = ProcessingStage.RECOGNIZING.name,
                    progress = 1f,
                    checkpointTimestamp = 1L,
                    errorCode = null,
                    createdTimestamp = 1L,
                    updatedTimestamp = 1L
                )
            )
            var nextId = 0L
            var index = 0
            repeat(100) {
                val batch = List(64) {
                    val id = nextId++
                    SubtitleSegment(id, id * 1_000L, id * 1_000L + 900L, "o$id", "e$id", "t$id")
                }
                repository.persistCompletedChunk(jobId, batch, index, batch.last().endMs, 50f, "en")
                index += batch.size
            }

            var seen = 0
            var maxBatch = 0
            repository.segmentBatches(jobId, 64).forEach { batch ->
                seen += batch.size
                maxBatch = maxOf(maxBatch, batch.size)
            }
            assertEquals(6_400, seen)
            assertTrue(maxBatch <= 64)
            assertTrue(repository.loadSegmentsWindow(jobId, 2_000_000L, 2_060_000L, 80).size <= 80)
        } finally {
            db.close()
        }
    }
}
