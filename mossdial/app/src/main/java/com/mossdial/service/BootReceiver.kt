package com.mossdial.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.mossdial.data.OnboardingState
import com.mossdial.data.ServerSettings
import com.mossdial.widget.ServerWidget

/**
 * Brings the server back after a reboot, but only when the user asked for it.
 *
 * The receiver is exported because `BOOT_COMPLETED` is a protected broadcast, and it is guarded
 * by the same permission that guards the broadcast itself, so nothing else can trigger it. The
 * decision is taken by [AutoStartPolicy]; when auto-start is off the receiver does nothing at all
 * apart from refreshing the widget.
 */
class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "MossdialBoot"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val settings = ServerSettings(context)
        val decision = AutoStartPolicy.evaluate(
            autoStartEnabled = settings.autoStart,
            onboardingComplete = OnboardingState(context).isComplete
        )
        Log.i(TAG, "Boot received: ${decision.outcome}")
        if (decision.shouldStart) {
            ServerControl.start(context, settings)
        } else {
            ServerWidget.refresh(context)
        }
    }
}
