package com.haloapps.guard

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context

/**
 * Runs about once an hour in the background. Looks at the last 6 hours of network usage and
 * raises an alert when an app that can see your photos (or any untrusted app) uploads a lot of
 * data while you weren't using it.
 */
object Watcher {
    private const val JOB_ID = 4242
    private const val WINDOW_MS = 6L * 60 * 60 * 1000
    private const val COOLDOWN_MS = 12L * 60 * 60 * 1000
    private const val PHOTO_APP_LIMIT = 25L * Scanner.MB   // photo-access app uploading in background
    private const val ANY_APP_LIMIT = 150L * Scanner.MB    // any app uploading heavily in background

    fun schedule(ctx: Context) {
        val js = ctx.getSystemService(JobScheduler::class.java) ?: return
        if (js.getPendingJob(JOB_ID) != null) return
        val job = JobInfo.Builder(JOB_ID, ComponentName(ctx, WatchJobService::class.java))
            .setPeriodic(60L * 60 * 1000)
            .setPersisted(true)
            .build()
        js.schedule(job)
    }

    fun check(ctx: Context) {
        val scanner = Scanner(ctx)
        if (!scanner.hasUsageAccess()) return
        val prefs = ctx.getSharedPreferences("guard", Context.MODE_PRIVATE)
        val trusted = prefs.getStringSet("trusted", emptySet()) ?: emptySet()
        val now = System.currentTimeMillis()
        val usage = scanner.uploads(now - WINDOW_MS, now)
        val edit = prefs.edit()

        for (ai in scanner.userApps()) {
            val pkg = ai.packageName
            if (pkg in trusted || pkg in Scanner.EXPECTED_UPLOADERS) continue
            val bg = usage[ai.uid]?.background ?: 0L
            if (bg < PHOTO_APP_LIMIT) continue

            val key = "alerted_$pkg"
            if (now - prefs.getLong(key, 0L) < COOLDOWN_MS) continue

            val perms = scanner.permissions(ai)
            val canSeePhotos = perms.photo >= PhotoAccess.FULL
            if (!canSeePhotos && bg < ANY_APP_LIMIT) continue

            val label = scanner.label(ai)
            val title = "Stop using $label?"
            val body = if (canSeePhotos) {
                "$label uploaded ${fmtBytes(bg)} in the last 6 hours while you weren't using it, " +
                    "and it can see all your photos. If you didn't expect this, avoid the app or turn on Photo lock for it in Halo Guard, " +
                    "so it loses photo access whenever it is closed."
            } else {
                "$label uploaded ${fmtBytes(bg)} in the background in the last 6 hours. " +
                    "If you didn't expect this, avoid the app or uninstall it."
            }
            Notifier.alert(ctx, pkg, label, title, body)
            edit.putLong(key, now)
        }
        edit.apply()
    }
}
