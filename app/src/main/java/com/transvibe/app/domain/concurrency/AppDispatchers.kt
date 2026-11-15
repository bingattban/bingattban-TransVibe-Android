package com.transvibe.app.domain.concurrency

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Dispatcher intent is centralized here so threading decisions remain auditable.
 * Heavy native inference is serialized independently from general CPU orchestration.
 */
object AppDispatchers {
    val main: CoroutineDispatcher = Dispatchers.Main.immediate
    val io: CoroutineDispatcher = Dispatchers.IO
    val computation: CoroutineDispatcher = Dispatchers.Default
    val nativeInference: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)
}
