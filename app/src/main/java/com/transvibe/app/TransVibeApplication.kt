package com.transvibe.app

import android.app.Application
import com.transvibe.app.presentation.navigation.TransVibeViewModelFactory
import com.transvibe.app.service.SubtitleProcessingService

class TransVibeApplication : Application() {
    private lateinit var graph: AppGraph

    val viewModelFactory: TransVibeViewModelFactory
        get() = graph.viewModelFactory

    // Integration entry point for the explicit FFmpeg + sherpa-onnx offline flow.
    // The established video-processing UI/service route remains unchanged.
    internal val generateArabicSubtitlesUseCase
        get() = graph.generateArabicSubtitlesUseCase

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }

    override fun onTerminate() {
        if (::graph.isInitialized) graph.releaseVad()
        super.onTerminate()
    }

    internal fun inject(service: SubtitleProcessingService) {
        graph.inject(service)
    }
}
