package com.mossdial.service

/**
 * Decides whether a boot event is allowed to bring the server back on its own.
 *
 * The rule is deliberately conservative: nothing starts unless the user turned auto-start on in
 * the settings screen and has been through onboarding, so consent for both is required. Kept free
 * of Android types so every branch is covered by JVM tests.
 */
object AutoStartPolicy {
    enum class Outcome {
        RESTORE,
        SKIP_NOT_ENABLED,
        SKIP_ONBOARDING_INCOMPLETE,
        SKIP_NOT_RUNNING
    }

    data class Decision(val outcome: Outcome, val reason: String) {
        val shouldStart: Boolean
            get() = outcome == Outcome.RESTORE
    }

    fun evaluate(autoStartEnabled: Boolean, onboardingComplete: Boolean): Decision = when {
        !onboardingComplete -> Decision(
            Outcome.SKIP_ONBOARDING_INCOMPLETE,
            "The introduction has not been completed yet"
        )
        !autoStartEnabled -> Decision(Outcome.SKIP_NOT_ENABLED, "Auto-start is turned off")
        else -> Decision(Outcome.RESTORE, "Restoring the server after a restart")
    }

    /**
     * The rule for an app update.
     *
     * A package replacement stops the service, and a sticky restart does not cross it, so the
     * update is handled like a restart of its own. The consent here is the immediate one — the
     * server was running when the update landed — rather than the reboot switch, which exists for
     * a device that has been powered off.
     */
    fun evaluateAfterUpdate(desiredRunning: Boolean, onboardingComplete: Boolean): Decision = when {
        !onboardingComplete -> Decision(
            Outcome.SKIP_ONBOARDING_INCOMPLETE,
            "The introduction has not been completed yet"
        )
        !desiredRunning -> Decision(
            Outcome.SKIP_NOT_RUNNING,
            "The server was not running before the update"
        )
        else -> Decision(Outcome.RESTORE, "Restoring the server after the update")
    }
}
