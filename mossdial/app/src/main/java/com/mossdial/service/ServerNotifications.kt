package com.mossdial.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import com.mossdial.MainActivity
import com.mossdial.R

/**
 * The foreground-service notification: its channel, its text and its stop action.
 *
 * The channel is created before the service ever calls `startForeground`, which Android requires,
 * and the notification carries a stop action so the server can be ended without opening the app.
 * Every string shown here is a fixed sentence built from the server status; no configuration
 * value and no credential is ever rendered into the notification.
 */
object ServerNotifications {
    const val CHANNEL_ID = "server"
    const val NOTIFICATION_ID = 1001

    private const val FAILURE_NOTIFICATION_ID = 1002
    private const val REQUEST_OPEN = 1
    private const val REQUEST_STOP = 2

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.server_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.server_channel_description)
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun text(context: Context, status: ServerStatus): String = when (status.phase) {
        ServerPhase.Stopped -> context.getString(R.string.server_notification_stopped)
        ServerPhase.Starting -> context.getString(R.string.server_notification_starting)
        ServerPhase.Running -> context.getString(
            R.string.server_notification_running,
            status.port,
            if (status.allowLan) context.getString(R.string.binding_lan) else
                context.getString(R.string.binding_loopback)
        )
        ServerPhase.Failed -> status.detail.ifEmpty { context.getString(R.string.server_notification_failed) }
    }

    fun build(context: Context, status: ServerStatus): Notification {
        ensureChannel(context)
        return Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text(context, status))
            .setSmallIcon(R.drawable.ic_mossdial)
            .setContentIntent(openIntent(context))
            .addAction(stopAction(context))
            .setOngoing(status.isActive)
            .setShowWhen(false)
            .build()
    }

    /** Updates the standing notification, creating the channel first when needed. */
    fun post(context: Context, status: ServerStatus) {
        ensureChannel(context)
        runCatching {
            context.getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, build(context, status))
        }
    }

    /**
     * Reports why a start attempt failed.
     *
     * The failure is a normal, dismissible notification rather than a foreground one: the service
     * has nothing left to run, but the reason should still reach the user instead of disappearing
     * with the service. It uses its own id so tearing the foreground down cannot remove it.
     */
    fun postFailure(context: Context, status: ServerStatus) {
        ensureChannel(context)
        val detail = status.detail.ifEmpty { context.getString(R.string.server_notification_failed) }
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.server_notification_failed_title))
            .setContentText(detail)
            .setSmallIcon(R.drawable.ic_mossdial)
            .setContentIntent(openIntent(context))
            .setAutoCancel(true)
            .build()
        runCatching {
            context.getSystemService(NotificationManager::class.java)
                .notify(FAILURE_NOTIFICATION_ID, notification)
        }
    }

    fun clearFailure(context: Context) {
        runCatching {
            context.getSystemService(NotificationManager::class.java).cancel(FAILURE_NOTIFICATION_ID)
        }
    }

    private fun openIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_OPEN,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun stopIntent(context: Context): PendingIntent = PendingIntent.getForegroundService(
        context,
        REQUEST_STOP,
        Intent(context, ServerService::class.java).setAction(ServerService.ACTION_STOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun stopAction(context: Context): Notification.Action =
        Notification.Action.Builder(
            Icon.createWithResource(context, R.drawable.ic_mossdial),
            context.getString(R.string.server_action_stop),
            stopIntent(context)
        ).build()
}
