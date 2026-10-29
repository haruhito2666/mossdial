package com.mossdial.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoStartPolicyTest {
    @Test
    fun startsNothingUntilTheUserTurnsAutoStartOn() {
        val decision = AutoStartPolicy.evaluate(autoStartEnabled = false, onboardingComplete = true)

        assertEquals(AutoStartPolicy.Outcome.SKIP_NOT_ENABLED, decision.outcome)
        assertFalse(decision.shouldStart)
        assertEquals("Auto-start is turned off", decision.reason)
    }

    @Test
    fun neverStartsBeforeTheIntroductionHasBeenRead() {
        val decision = AutoStartPolicy.evaluate(autoStartEnabled = true, onboardingComplete = false)

        assertEquals(AutoStartPolicy.Outcome.SKIP_ONBOARDING_INCOMPLETE, decision.outcome)
        assertFalse(decision.shouldStart)
    }

    @Test
    fun restoresTheServerWhenBothConsentsArePresent() {
        val decision = AutoStartPolicy.evaluate(autoStartEnabled = true, onboardingComplete = true)

        assertEquals(AutoStartPolicy.Outcome.RESTORE, decision.outcome)
        assertTrue(decision.shouldStart)
    }

    @Test
    fun everyReasonIsAFixedSentenceThatNamesNoConfiguration() {
        val decisions = listOf(true, false).flatMap { autoStart ->
            listOf(true, false).map { onboarding ->
                AutoStartPolicy.evaluate(autoStart, onboarding)
            }
        }

        assertTrue(decisions.all { it.reason.isNotBlank() })
        assertTrue(decisions.none { it.reason.contains(Regex("[0-9]")) })
        assertEquals(
            decisions.map { it.outcome }.distinct().size,
            decisions.map { it.reason }.distinct().size
        )
    }

    @Test
    fun afterAnUpdateOnlyARunningServerComesBack() {
        val stopped = AutoStartPolicy.evaluateAfterUpdate(
            desiredRunning = false,
            onboardingComplete = true
        )
        assertEquals(AutoStartPolicy.Outcome.SKIP_NOT_RUNNING, stopped.outcome)
        assertFalse(stopped.shouldStart)

        val running = AutoStartPolicy.evaluateAfterUpdate(
            desiredRunning = true,
            onboardingComplete = true
        )
        assertEquals(AutoStartPolicy.Outcome.RESTORE, running.outcome)
        assertTrue(running.shouldStart)
    }

    @Test
    fun anUpdateNeverStartsBeforeOnboarding() {
        val decision = AutoStartPolicy.evaluateAfterUpdate(
            desiredRunning = true,
            onboardingComplete = false
        )

        assertEquals(AutoStartPolicy.Outcome.SKIP_ONBOARDING_INCOMPLETE, decision.outcome)
        assertFalse(decision.shouldStart)
    }

    @Test
    fun afterAnUpdateAutoStartIsNotTheConsentThatMatters() {
        // A server can be running with the reboot switch off; the update must still restore it,
        // because the two settings answer different questions. This asserts the policy signature
        // takes the immediate intent rather than borrowing the boot rule.
        assertTrue(
            AutoStartPolicy.evaluateAfterUpdate(desiredRunning = true, onboardingComplete = true)
                .shouldStart
        )
        assertFalse(
            AutoStartPolicy.evaluate(autoStartEnabled = false, onboardingComplete = true)
                .shouldStart
        )
    }
}
