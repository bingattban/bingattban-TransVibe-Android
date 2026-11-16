package com.transvibe.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import com.transvibe.app.presentation.navigation.TransVibeApp

class MainActivity : ComponentActivity() {
    private var lastLogSenderAttemptMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { TransVibeApp() }
    }

    override fun onPostResume() {
        super.onPostResume()
        if (!BuildConfig.DEBUG) return

        // Code On the Go's installer provider is disabled in the debug manifest to
        // avoid an early background-service crash. Start its service in foreground.
        Handler(Looper.getMainLooper()).postDelayed({
            if (isFinishing || isDestroyed ||
                !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            ) return@postDelayed

            val now = SystemClock.elapsedRealtime()
            if (now - lastLogSenderAttemptMs < 2_000L) return@postDelayed
            lastLogSenderAttemptMs = now

            // IMPORTANT: LogSenderService stops itself when the Intent action is null.
            // This is the exact ACTION_START_SERVICE in Code On the Go's LogSender.
            val intent = Intent("ide.logsender.service.start").setClassName(
                packageName,
                "com.itsaky.androidide.logsender.LogSenderService"
            )
            try {
                val service = startForegroundService(intent)
                Log.i(
                    "TransVibeDebug",
                    if (service != null) "LogSender start requested (Code On the Go)"
                    else "LogSender not injected into this debug APK"
                )
            } catch (error: Exception) {
                // Diagnostics must never crash the translation application.
                Log.w("TransVibeDebug", "LogSender start failed", error)
            }
        }, 350L)
    }
}
