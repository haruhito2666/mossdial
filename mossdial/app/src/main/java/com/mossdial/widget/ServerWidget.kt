package com.mossdial.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.mossdial.MainActivity
import com.mossdial.R
import com.mossdial.data.ServerSettings
import com.mossdial.service.ServerPhase
import com.mossdial.service.ServerState
import com.mossdial.service.ServerStatus
import com.mossdial.tunnel.TunnelManager
import com.mossdial.tunnel.TunnelPhase

/**
 * A home-screen widget that reports the server state and can start or stop it.
 *
 * The widget is a `RemoteViews` view of this process, so it holds no state of its own: every update
 * renders the snapshot published by [ServerState] and the current tunnel phase. Its button posts to
 * the unexported [ServerWidgetActions] receiver, which starts the server the same way an in-app tap
 * does.
 */
class ServerWidget : AppWidgetProvider() {
    companion object {
        const val ACTION_START = "com.mossdial.widget.START"
        const val ACTION_STOP = "com.mossdial.widget.STOP"

        private const val REQUEST_OPEN = 10
        private const val REQUEST_START = 11
        private const val REQUEST_STOP = 12

        /** Re-renders every placed widget. Safe to call when none are placed. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, ServerWidget::class.java))
            if (ids.isEmpty()) return
            val status = ServerState.snapshot()
            val settings = ServerSettings(context)
            ids.forEach { manager.updateAppWidget(it, render(context, status, settings)) }
        }

        internal fun render(
            context: Context,
            status: ServerStatus,
            settings: ServerSettings
        ): RemoteViews {
            val running = status.phase == ServerPhase.Running
            val views = RemoteViews(context.packageName, R.layout.widget_server)
            views.setTextViewText(R.id.widget_status, statusLine(context, status, settings))
            views.setTextViewText(R.id.widget_detail, detailLine(context, settings))
            views.setOnClickPendingIntent(R.id.widget_root, openIntent(context))
            views.setOnClickPendingIntent(R.id.widget_status, openIntent(context))
            views.setOnClickPendingIntent(R.id.widget_toggle, actionIntent(context, running))
            views.setTextViewText(R.id.widget_toggle, toggleLabel(context, running))
            return views
        }

        private fun statusLine(
            context: Context,
            status: ServerStatus,
            settings: ServerSettings
        ): String = when (status.phase) {
            ServerPhase.Running -> context.getString(
                R.string.widget_status_online,
                status.port.takeIf { it > 0 } ?: settings.port
            )
            ServerPhase.Starting -> context.getString(R.string.widget_status_starting)
            ServerPhase.Failed -> context.getString(R.string.widget_status_failed)
            ServerPhase.Stopped -> context.getString(R.string.widget_status_offline)
        }

        private fun detailLine(context: Context, settings: ServerSettings): String {
            val binding = context.getString(
                if (settings.allowLan) R.string.binding_lan else R.string.binding_loopback
            )
            val scheme = context.getString(
                if (settings.enableSsl) R.string.scheme_https else R.string.scheme_http
            )
            val tunnel = when (TunnelManager.snapshot().phase) {
                TunnelPhase.Running -> context.getString(R.string.widget_tunnel_connected)
                TunnelPhase.Starting -> context.getString(R.string.widget_tunnel_starting)
                else -> ""
            }
            val local = "$binding · $scheme"
            return if (tunnel.isEmpty()) local else "$local · $tunnel"
        }

        private fun toggleLabel(context: Context, running: Boolean): String =
            context.getString(if (running) R.string.server_action_stop else R.string.server_action_start)

        private fun openIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            REQUEST_OPEN,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        private fun actionIntent(context: Context, running: Boolean): PendingIntent {
            val action = if (running) ACTION_STOP else ACTION_START
            val intent = Intent(context, ServerWidgetActions::class.java).setAction(action)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            return if (running) {
                PendingIntent.getBroadcast(context, REQUEST_STOP, intent, flags)
            } else {
                PendingIntent.getBroadcast(context, REQUEST_START, intent, flags)
            }
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val status = ServerState.snapshot()
        val settings = ServerSettings(context)
        appWidgetIds.forEach { manager.updateAppWidget(it, render(context, status, settings)) }
    }

    override fun onEnabled(context: Context) {
        refresh(context)
    }
}
