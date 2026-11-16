package com.transvibe.app.data.download

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import com.transvibe.app.domain.model.DownloadProgress
import com.transvibe.app.domain.model.WhisperModel
import com.transvibe.app.domain.model.WhisperModelType

@RunWith(AndroidJUnit4::class)
class ModelTransferOutcomeStoreInstrumentationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var model: WhisperModel

    @Before
    fun setUp() {
        instrumentation.targetContext.getSharedPreferences("model_transfer_records_v2", 0).edit().clear().commit()
        model = WhisperModel(
            id = "restart-model",
            displayName = "Restart model",
            fileName = "restart.bin",
            downloadUrl = "https://example.invalid/restart.bin",
            fileSizeBytes = 10_000L,
            sha256 = "00".repeat(32),
            type = WhisperModelType.MULTILINGUAL,
            estimatedRamBytes = 32L * 1024 * 1024,
            descriptionAr = "test"
        )
    }

    @Test
    fun testUnfinishedTransferSurvivesProcessStyleRecreation() {
        val first = ModelTransferOutcomeStore(instrumentation.targetContext)
        first.persist(
            ModelTransferRecord(
                modelId = model.id,
                operation = ModelTransferOperation.DOWNLOAD,
                state = DownloadProgress.Status.DOWNLOADING,
                downloadedBytes = 4_096L,
                expectedBytes = model.fileSizeBytes,
                retryCount = 2,
                temporaryFile = "/tmp/restart.bin.part",
                updatedTimestamp = 1234L,
                errorCode = null
            )
        )

        val recreated = ModelTransferOutcomeStore(instrumentation.targetContext)
        val record = recreated.read(model)!!
        assertEquals(4_096L, record.downloadedBytes)
        assertEquals(2, record.retryCount)
        assertEquals("/tmp/restart.bin.part", record.temporaryFile)
        assertEquals(DownloadProgress.Status.DOWNLOADING, record.state)

        val restored = recreated.restoredProgress(model)!!
        assertEquals(DownloadProgress.Status.PAUSED, restored.status)
        assertEquals(4_096L, restored.downloadedBytes)
        assertEquals(40, restored.percent)
    }

    @Test
    fun testPartialAndRetryingStatesRestoreAsPaused() {
        val store = ModelTransferOutcomeStore(instrumentation.targetContext)
        store.persist(
            ModelTransferRecord(
                model.id,
                ModelTransferOperation.DOWNLOAD,
                DownloadProgress.Status.RETRYING,
                2_500L,
                model.fileSizeBytes,
                3,
                "/tmp/retry.part",
                2_000L,
                "network_timeout"
            )
        )
        assertEquals(
            DownloadProgress.Status.PAUSED,
            ModelTransferOutcomeStore(instrumentation.targetContext).restoredProgress(model)?.status
        )
    }
}
