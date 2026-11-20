package ru.yandexpraktikum.blechat

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dagger.hilt.android.HiltAndroidApp

private const val CHANNEL_ID = "channel_id"

@HiltAndroidApp
class BleChat: Application() {

    override fun onCreate() {
        super.onCreate()
        setUpNotificationsChannel()
    }

    private fun setUpNotificationsChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "BLE Chat Notifications",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.createNotificationChannel(channel)
    }
}