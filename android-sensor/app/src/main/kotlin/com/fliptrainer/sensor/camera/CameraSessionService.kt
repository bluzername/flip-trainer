package com.fliptrainer.sensor.camera

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import com.fliptrainer.sensor.R

/**
 * Keeps camera capture and pose inference alive while a set is running.
 *
 * Started from a visible activity, never from the background: Android 12+ forbids starting a
 * `camera`-typed foreground service from the background, and the plan's §2.4 decision is to stay
 * an ordinary app rather than provision Device Owner. Manual arming is the accepted cost.
 *
 * The service does not own the camera pipeline — [CameraController] does. Its only jobs are to
 * hold the foreground-service lifetime and to show the notification, so that the process is not
 * killed mid-set. Business logic here would be unreachable from a unit test (ADR-0001).
 */
class CameraSessionService : LifecycleService() {
    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        super.onStartCommand(intent, flags, startId)
        ensureChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0,
        )
        // START_NOT_STICKY: if the process dies mid-set, the correct behaviour is to recover the
        // session as interrupted on next launch (SessionRecovery), not to silently restart a
        // camera with no athlete in front of it.
        return START_NOT_STICKY
    }

    private fun ensureChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.camera_service_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.camera_service_text)
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(): Notification =
        NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.camera_service_title))
            .setContentText(getString(R.string.camera_service_text))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setSilent(true)
            .build()

    companion object {
        private const val CHANNEL_ID = "flip-trainer-capture"
        private const val NOTIFICATION_ID = 1001

        /** Must be called from a visible activity. See the note above about background starts. */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, CameraSessionService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CameraSessionService::class.java))
        }
    }
}
