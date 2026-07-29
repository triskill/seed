package com.seed.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.seed.app.runtime.RuntimeService

/** Process-level setup shared by every Seed activity and service. */
class SeedApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            RuntimeService.CHANNEL_ID,
            getString(R.string.runtime_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.runtime_notification_channel_description)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
