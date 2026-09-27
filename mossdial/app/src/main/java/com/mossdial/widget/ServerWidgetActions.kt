package com.mossdial.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mossdial.data.ServerSettings
import com.mossdial.service.ServerControl

/**
 * Handles the widget's own start and stop taps.
 *
 * This lives in a separate, unexported receiver so that [ServerWidget] can stay exported for the
 * system `APPWIDGET_UPDATE` broadcast while the action that starts a server is only reachable from
 * a `PendingIntent` created by this app. Nothing outside the app can ask for the server to be
 * started or stopped.
 */
class ServerWidgetActions : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ServerWidget.ACTION_START -> ServerControl.start(context)
            ServerWidget.ACTION_STOP -> {
                // An explicit stop also means the user does not want the server back on its own,
                // so auto-start is cleared here too.
                ServerSettings(context).autoStart = false
                ServerControl.stop(context)
            }
        }
        ServerWidget.refresh(context)
    }
}
