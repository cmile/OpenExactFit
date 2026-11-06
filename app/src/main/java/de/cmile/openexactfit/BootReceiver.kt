package de.cmile.openexactfit

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import de.cmile.openexactfit.data.Settings

/** brings the background sync back after a reboot, if the owner left it switched on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Settings(context).backgroundSync || missingPermissions(context).isNotEmpty()) return
        try {
            SyncService.start(context)
        } catch (e: Exception) {
            Log.w(SyncLog.TAG, "could not restart background sync after boot: $e")
        }
    }
}
