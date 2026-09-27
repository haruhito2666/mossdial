package com.mossdial.data

import android.content.Context

/**
 * The single flag that records whether the user has seen the introduction.
 *
 * Nothing here is secret, and the value is only ever a boolean, so plain preferences are enough.
 */
class OnboardingState(context: Context) {
    private val store = SharedPreferenceStore(
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    )

    var isComplete: Boolean
        get() = store.getBoolean(KEY_COMPLETE, false)
        set(value) = store.putBoolean(KEY_COMPLETE, value)

    fun complete() {
        isComplete = true
    }

    companion object {
        const val NAME = "app_state"
        const val KEY_COMPLETE = "onboarding_complete"
    }
}
