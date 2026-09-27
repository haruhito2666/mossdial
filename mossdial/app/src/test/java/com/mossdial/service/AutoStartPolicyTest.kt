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
}
