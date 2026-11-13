package de.cmile.openexactfit.data

import android.content.Context
import androidx.core.content.edit
import de.cmile.openexactfit.ble.Protocol

/**
 * the few things the app remembers besides readings: names for the monitor's users
 * A and B, whether to scan on start, and whether the background service is wanted.
 */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** user key ("A"/"B") to the name given by the owner; absent when not named. */
    fun userNames(): Map<String, String> =
        Protocol.USERS.mapNotNull { u -> prefs.getString("user_name_$u", null)?.takeIf { it.isNotBlank() }?.let { u to it } }.toMap()

    fun setUserNames(names: Map<String, String>) = prefs.edit {
        for (u in Protocol.USERS) {
            val n = names[u]?.trim().orEmpty()
            if (n.isEmpty()) remove("user_name_$u") else putString("user_name_$u", n)
        }
    }

    var autoSync: Boolean
        get() = prefs.getBoolean("auto_sync", true)
        set(value) = prefs.edit { putBoolean("auto_sync", value) }

    /** the owner switched the foreground service on; it is restarted after a reboot while this is set. */
    var backgroundSync: Boolean
        get() = prefs.getBoolean("background_sync", false)
        set(value) = prefs.edit { putBoolean("background_sync", value) }

    /** the session log card on the main screen; off by default, logcat has it either way. */
    var showLog: Boolean
        get() = prefs.getBoolean("show_log", false)
        set(value) = prefs.edit { putBoolean("show_log", value) }

    /** show readings taken within minutes of each other as one line with their mean; display only. */
    var groupSessions: Boolean
        get() = prefs.getBoolean("group_sessions", true)
        set(value) = prefs.edit { putBoolean("group_sessions", value) }

    /** averages count each session once (mean of the session means) instead of each reading once. */
    var averageSessions: Boolean
        get() = prefs.getBoolean("average_sessions", true)
        set(value) = prefs.edit { putBoolean("average_sessions", value) }
}
