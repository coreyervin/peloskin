package app.peloskin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import app.peloskin.data.store.SettingsStore
import app.peloskin.ui.overlay.SkinService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Start with the bike, and come back after an update (installing one stops the app).
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        SettingsStore.init(context)
        if (SettingsStore.settings.value.startOnBoot && Settings.canDrawOverlays(context)) {
            SkinService.start(context)
        }
    }
}
