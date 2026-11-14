package io.github.tellyteam.telly.data.update

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Puts telly's tile back on the Fire TV home screen after telly is updated.
 *
 * On Fire OS the Appstore draws the tiles of sideloaded apps, and when one is updated in place it
 * keeps the app's entry but loses the picture, so telly's tile turns into a broken-image placeholder
 * until something else is installed. Telling the Appstore that telly was added, which a fresh install
 * would have done, makes it draw the tile again. It keeps telly's place in the row.
 *
 * Runs on MY_PACKAGE_REPLACED, so the tile is mended without anyone opening telly. Elsewhere it does nothing.
 */
class FireTvTileRepair : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED || !Build.MANUFACTURER.equals("Amazon", ignoreCase = true)) return
        val app = context.applicationContext
        val pending = goAsync()
        // After the Appstore has handled the update itself, or it would undo this.
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                app.sendBroadcast(
                    Intent("com.amazon.tv.action.PACKAGE_ADDED", Uri.parse("package:${app.packageName}"))
                        .setComponent(ComponentName(APPSTORE, "com.amazon.mas.client.apps.AppManagerBroadcastReceiver")),
                )
            } catch (_: RuntimeException) {
                // No Appstore, or one that works differently: the tile is no worse than before.
            } finally {
                pending.finish()
            }
        }, DELAY_MS)
    }

    private companion object {
        const val APPSTORE = "com.amazon.venezia"
        const val DELAY_MS = 5_000L
    }
}
