package com.mossdial.service

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.mossdial.data.ServerSettings

/**
 * The one place that turns a user intention into a service request.
 *
 * The app screen, the home-screen widget and the boot receiver all go through here, so the
 * settings that get applied are always the persisted ones and every start is a foreground-service
 * start. Starting from the background is refused in some situations; when that happens the
 * failure is published so the screen, the notification and the widget all agree instead of the
 * service silently never appearing.
 */
object ServerControl {
    private const val TAG = "MossdialServer"

    fun start(context: Context, settings: ServerSettings = ServerSettings(context)): Boolean {
        val intent = Intent(context, ServerService::class.java)
            .setAction(ServerService.ACTION_START)
            .putExtra(ServerService.EXTRA_PORT, settings.port)
            .putExtra(ServerService.EXTRA_ALLOW_LAN, settings.allowLan)
            .putExtra(ServerService.EXTRA_ENABLE_SSL, settings.enableSsl)
        return submit(context, intent, "The system refused to start the server")
    }

    fun stop(context: Context): Boolean =
        submit(
            context,
            Intent(context, ServerService::class.java).setAction(ServerService.ACTION_STOP),
            "The system refused to stop the server"
        )

    private fun submit(context: Context, intent: Intent, message: String): Boolean = try {
        ContextCompat.startForegroundService(context, intent)
        true
    } catch (error: RuntimeException) {
        Log.w(TAG, "Server request rejected: ${error.javaClass.simpleName}")
        ServerState.publish(ServerStatus.failed(message))
        false
    }
}
