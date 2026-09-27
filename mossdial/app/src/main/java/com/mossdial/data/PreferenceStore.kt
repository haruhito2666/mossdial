package com.mossdial.data

import android.content.SharedPreferences

/**
 * The narrow slice of [SharedPreferences] this app reads and writes.
 *
 * Receivers, services and widgets are instantiated by the platform outside the Compose tree, so
 * the store is an interface and the platform type stays behind a thin adapter. Settings rules are
 * then covered by JVM tests without an Android runtime or a device.
 */
interface PreferenceStore {
    fun getBoolean(key: String, fallback: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)

    fun getInt(key: String, fallback: Int): Int
    fun putInt(key: String, value: Int)

    fun getString(key: String, fallback: String): String
    fun putString(key: String, value: String)
}

class SharedPreferenceStore(private val preferences: SharedPreferences) : PreferenceStore {
    override fun getBoolean(key: String, fallback: Boolean): Boolean =
        preferences.getBoolean(key, fallback)

    override fun putBoolean(key: String, value: Boolean) {
        preferences.edit().putBoolean(key, value).apply()
    }

    override fun getInt(key: String, fallback: Int): Int = preferences.getInt(key, fallback)

    override fun putInt(key: String, value: Int) {
        preferences.edit().putInt(key, value).apply()
    }

    override fun getString(key: String, fallback: String): String =
        preferences.getString(key, fallback).orEmpty()

    override fun putString(key: String, value: String) {
        preferences.edit().putString(key, value).apply()
    }
}
