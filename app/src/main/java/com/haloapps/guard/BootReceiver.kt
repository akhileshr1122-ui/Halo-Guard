package com.haloapps.guard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Watcher.schedule(context)
        if (PhotoLock.lockedApps(context).isNotEmpty()) PhotoLock.startService(context)
    }
}
