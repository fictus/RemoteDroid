package com.example.remotedroid

import android.content.Context
import android.provider.Settings
import java.security.MessageDigest

/**
 * The login PIN. By default it is derived from this device's Android ID, so it is the same every
 * time (and after reinstalling the app). The user can override it; the override is stored until reset.
 */
object Pin {
    private const val PREFS = "remotedroid"
    private const val KEY = "pin_override"
    private val VALID = Regex("^\\d{4,8}$")

    fun isValid(pin: String) = VALID.matches(pin)

    /** Stable 6-digit PIN from the device ID. */
    fun derived(context: Context): String {
        val id = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        val h = MessageDigest.getInstance("SHA-256").digest("remotedroid-pin:$id".toByteArray())
        val n = ((h[0].toLong() and 0xff) shl 24) or ((h[1].toLong() and 0xff) shl 16) or
            ((h[2].toLong() and 0xff) shl 8) or (h[3].toLong() and 0xff)
        return "%06d".format(n % 1_000_000)
    }

    fun get(context: Context): String {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        return if (saved != null && isValid(saved)) saved else derived(context)
    }

    fun set(context: Context, pin: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, pin).apply()
    }

    fun reset(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
