package com.transvibe.app.nativebridge.whisper

import com.transvibe.app.data.model.ModelStore
import com.transvibe.app.domain.model.WhisperModel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger

/**
 * Owns the single resident Whisper context used by heavy inference.
 * Heavy Whisper concurrency defaults to exactly one lease. A matching resident model is reused;
 * a different requested model deterministically releases the old context before loading the new one.
 */
class WhisperRuntimeManager(private val store: ModelStore) : Closeable {
    private val inferencePermit = Semaphore(1)
    private val stateMutex = Mutex()
    private val activeLeases = AtomicInteger(0)
    @Volatile private var loadedModelId: String? = null
    @Volatile private var loadedHandle: Long = 0L
    @Volatile private var loadedContextInvalidated: Boolean = false

    fun isInstalled(model: WhisperModel): Boolean = store.file(model).isFile && store.file(model).canRead()

    suspend fun acquire(model: WhisperModel): Lease {
        inferencePermit.acquire()
        try {
            val handle = stateMutex.withLock {
                if (loadedHandle != 0L && (loadedModelId != model.id || loadedContextInvalidated)) {
                    releaseLoadedLocked()
                }
                if (loadedHandle == 0L) {
                    val created = WhisperNative.createContext(store.file(model).absolutePath)
                    check(created != 0L) { "Whisper context creation returned zero" }
                    loadedHandle = created
                    loadedModelId = model.id
                    loadedContextInvalidated = false
                }
                loadedHandle
            }
            activeLeases.incrementAndGet()
            return Lease(model.id, handle)
        } catch (t: Throwable) {
            inferencePermit.release()
            throw t
        }
    }

    fun cancelActive() {
        val handle = loadedHandle
        if (handle != 0L && activeLeases.get() > 0) {
            loadedContextInvalidated = true
            runCatching { WhisperNative.cancel(handle) }
        }
    }

    /** Waits for the current heavy inference lease, then deterministically releases the resident context. */
    suspend fun releaseWhenIdle() {
        inferencePermit.acquire()
        try {
            stateMutex.withLock { releaseLoadedLocked() }
        } finally {
            inferencePermit.release()
        }
    }

    override fun close() {
        check(activeLeases.get() == 0) { "Cannot close Whisper runtime while inference lease is active" }
        val handle = loadedHandle
        loadedHandle = 0L
        loadedModelId = null
        loadedContextInvalidated = false
        if (handle != 0L) runCatching { WhisperNative.releaseContext(handle) }
    }

    private fun releaseLoadedLocked() {
        val handle = loadedHandle
        loadedHandle = 0L
        loadedModelId = null
        loadedContextInvalidated = false
        if (handle != 0L) WhisperNative.releaseContext(handle)
    }

    inner class Lease internal constructor(
        val modelId: String,
        val handle: Long
    ) : Closeable {
        private var closed = false
        fun cancel() {
            if (!closed) {
                loadedContextInvalidated = true
                runCatching { WhisperNative.cancel(handle) }
            }
        }
        override fun close() {
            if (closed) return
            closed = true
            activeLeases.decrementAndGet()
            inferencePermit.release()
        }
    }
}
