package com.cicidev.dynamicisland

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val on = Cfg.p(context).getBoolean("enabled", false)
        if (on && Settings.canDrawOverlays(context)) {
            try { context.startForegroundService(Intent(context, IslandService::class.java)) } catch (_: Exception) {}
        }
    }
}
