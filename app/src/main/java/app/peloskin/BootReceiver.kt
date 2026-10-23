package app.peloskin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import app.peloskin.overlay.SkinService
import app.peloskin.store.SettingsStore

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        SettingsStore.init(context)
        if (SettingsStore.settings.value.startOnBoot && Settings.canDrawOverlays(context)) {
            SkinService.start(context)
        }
    }
}
