package com.transvibe.app.domain.runtime

/**
 * Small, job-scoped gate for UI/notification progress.
 * Only identical consecutive stage+percentage pairs are suppressed.
 * Stage transitions, progress corrections and terminal updates pass through.
 *
 * Call sequentially from one coroutine (it does not maintain global state).
 */
class DistinctProgressGate {
    private var previousStage: String? = null
    private var previousPercent: Int? = null

    fun shouldEmit(stage: String, percent: Int): Boolean {
        if (stage == previousStage && percent == previousPercent) return false
        previousStage = stage
        previousPercent = percent
        return true
    }
}
