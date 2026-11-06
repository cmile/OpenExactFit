package de.cmile.openexactfit.data

import android.content.Context
import androidx.core.content.edit
import de.cmile.openexactfit.ble.Protocol

/** the few things the app remembers besides readings: names for the monitor's users A and B, and whether to scan on start. */
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
}
