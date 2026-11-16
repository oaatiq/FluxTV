package com.fluxtv.app.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.fluxtv.app.FluxTvApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Android's two messages about FluxTV's updates (see the manifest):
 *
 * - [SessionInstall.ACTION_STATUS]: how an installation through Android's installer went, or that
 *   Android wants to ask about it first.
 * - MY_PACKAGE_REPLACED: the first moment of the new version. Its arrival also starts FluxTV's
 *   process, which a TV in standby needs to hear the wake; and the update is recorded and FluxTV
 *   brought back on screen if it was there when the update began.
 */
class UpdateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? FluxTvApplication ?: return
        val updates = app.container.updateManager
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    Intent.ACTION_MY_PACKAGE_REPLACED -> updates.afterPackageReplaced()
                    SessionInstall.ACTION_STATUS -> updates.onInstallStatus(intent)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
