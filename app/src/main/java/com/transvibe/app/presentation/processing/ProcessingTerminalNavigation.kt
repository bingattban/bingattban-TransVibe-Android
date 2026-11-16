package com.transvibe.app.presentation.processing

import com.transvibe.app.domain.model.ProcessingState

/** Terminal UI routing only; the durable processing state remains owned by Room. */
internal enum class ProcessingTerminalRoute { HOME, RESULTS }

internal fun processingTerminalRoute(state: ProcessingState): ProcessingTerminalRoute? = when (state) {
    ProcessingState.Cancelled -> ProcessingTerminalRoute.HOME
    is ProcessingState.Completed -> ProcessingTerminalRoute.RESULTS
    else -> null
}
