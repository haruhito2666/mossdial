package com.mossdial.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.mossdial.data.OnboardingState
import com.mossdial.data.ServerSettings
import com.mossdial.widget.ServerWidget

/**
 * Brings the server back without the user opening the app: after a reboot when auto-start is on,
 * and after an app update when the server was running when the update landed.
 *
 * The receiver is exported because `BOOT_COMPLETED` is a protected broadcast, and it is guarded by
 * the same permission that guards the broadcast itself, so nothing else can trigger it. Both
 * decisions are taken by [AutoStartPolicy]; when the answer is no, the receiver does nothing apart
 * from refreshing the widget.
 */
class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "MossdialBoot"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> restoreAfterBoot(context)
            Intent.ACTION_MY_PACKAGE_REPLACED -> restoreAfterUpdate(context)
            else -> return
        }
    }

    private fun restoreAfterBoot(context: Context) {
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

    private fun restoreAfterUpdate(context: Context) {
        val settings = ServerSettings(context)
        val decision = AutoStartPolicy.evaluateAfterUpdate(
            desiredRunning = settings.desiredRunning,
            onboardingComplete = OnboardingState(context).isComplete
        )
        Log.i(TAG, "Update received: ${decision.outcome}")
        if (decision.shouldStart) {
            ServerControl.start(context, settings)
        } else {
            ServerWidget.refresh(context)
        }
    }
}
