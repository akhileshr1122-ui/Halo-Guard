package com.haloapps.guard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.provider.Settings

object Notifier {
    private const val CHANNEL = "leak_alerts"

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            val ch = NotificationChannel(CHANNEL, "Data leak alerts", NotificationManager.IMPORTANCE_HIGH)
            ch.description = "Warns you when an app that can see your photos uploads data in the background"
            nm.createNotificationChannel(ch)
        }
    }

    fun alert(ctx: Context, pkg: String, label: String, title: String, body: String) {
        ensureChannel(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return

        val settingsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val reviewPi = PendingIntent.getActivity(
            ctx, pkg.hashCode(), settingsIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openPi = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val action = Notification.Action.Builder(
            Icon.createWithResource(ctx, R.drawable.ic_stat_shield),
            "Review $label permissions",
            reviewPi
        ).build()

        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(openPi)
            .addAction(action)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(pkg.hashCode(), n)
        } catch (e: SecurityException) {
            // notification permission not granted
        }
    }
}
