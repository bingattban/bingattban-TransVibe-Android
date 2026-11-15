package com.transvibe.app.presentation.notification

internal object NotificationPolicy {
    fun canPostCompletionNotification(apiLevel: Int, permissionGranted: Boolean): Boolean =
        apiLevel < 33 || permissionGranted
}
