package com.transvibe.app.domain.runtime

/** Thermal state used by the processing pipeline. */
enum class ThermalState {
    NORMAL,
    SUSTAINED,
    THERMAL_SAFE
}

/**
 * Converts Android thermal signals into a small hysteretic state machine.
 * Escalation is immediate; recovery requires repeated cooler observations and a cooldown period.
 */
class ThermalStateController(
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val recoverySamples: Int = DEFAULT_RECOVERY_SAMPLES,
    private val recoveryCooldownMs: Long = DEFAULT_RECOVERY_COOLDOWN_MS
) {
    init {
        require(recoverySamples >= 1)
        require(recoveryCooldownMs >= 0L)
    }

    private var state = ThermalState.NORMAL
    private var coolerSamples = 0
    private var lastTransitionMs = 0L

    fun update(androidThermalStatus: Int, powerSaveMode: Boolean = false): ThermalState {
        val observed = when {
            androidThermalStatus >= THERMAL_SAFE_THRESHOLD -> ThermalState.THERMAL_SAFE
            powerSaveMode || androidThermalStatus >= THERMAL_SUSTAINED_THRESHOLD -> ThermalState.SUSTAINED
            else -> ThermalState.NORMAL
        }
        val now = nowMs()

        if (observed.ordinal > state.ordinal) {
            state = observed
            coolerSamples = 0
            lastTransitionMs = now
            return state
        }

        if (observed.ordinal == state.ordinal) {
            coolerSamples = 0
            return state
        }

        coolerSamples++
        val cooldownElapsed = now - lastTransitionMs >= recoveryCooldownMs
        if (coolerSamples >= recoverySamples && cooldownElapsed) {
            state = when (state) {
                ThermalState.THERMAL_SAFE -> ThermalState.SUSTAINED
                ThermalState.SUSTAINED -> ThermalState.NORMAL
                ThermalState.NORMAL -> ThermalState.NORMAL
            }
            coolerSamples = 0
            lastTransitionMs = now
        }
        return state
    }

    fun current(): ThermalState = state

    private companion object {
        const val DEFAULT_RECOVERY_SAMPLES = 3
        const val DEFAULT_RECOVERY_COOLDOWN_MS = 30_000L
        const val THERMAL_SUSTAINED_THRESHOLD = 2 // PowerManager.THERMAL_STATUS_MODERATE
        const val THERMAL_SAFE_THRESHOLD = 4 // PowerManager.THERMAL_STATUS_SEVERE
    }
}
