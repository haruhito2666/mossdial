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
        SKIP_ONBOARDING_INCOMPLETE
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
}
