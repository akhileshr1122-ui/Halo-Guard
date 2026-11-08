package com.haloapps.guard

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.Process
import java.util.Calendar

/** Apps whose uploads you're fine with (cloud storage you chose). */
object AllowedCloud {
    val DEFAULTS = setOf(
        "com.google.android.apps.docs",   // Google Drive
        "com.microsoft.skydrive"          // OneDrive
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("guard", Context.MODE_PRIVATE)

    fun all(ctx: Context): Set<String> =
        HashSet(prefs(ctx).getStringSet("allowed_cloud", DEFAULTS) ?: DEFAULTS)

    fun isAllowed(ctx: Context, pkg: String) = pkg in all(ctx)

    fun set(ctx: Context, pkg: String, allowed: Boolean) {
        val s = all(ctx).toMutableSet()
        if (allowed) s.add(pkg) else s.remove(pkg)
        prefs(ctx).edit().putStringSet("allowed_cloud", s).apply()
    }
}

/**
 * Spots individual uploads as they happen. Every minute it reads Android's per-app "bytes sent"
 * counters; when an app's counter jumps, an upload session starts. When the app stops sending for
 * a few minutes, the session is saved with: how much was sent, whether you had the app on screen
 * (manual) or not (automatic), and which servers/companies the app was talking to at that moment.
 */
class UploadMonitor(private val ctx: Context) {

    companion object {
        private const val STEP_BYTES = 256L * 1024          // counts as "sending"
        private const val MIN_UPLOAD = 1L * Scanner.MB      // smaller sessions are ignored
        private const val QUIET_MS = 3L * 60 * 1000         // session ends after 3 quiet minutes
        private const val ALERT_BYTES = 5L * Scanner.MB     // automatic uploads above this alert you
        private const val ALERT_COOLDOWN_MS = 30L * 60 * 1000
    }

    private class Session(val uid: Int, val start: Long) {
        var bytes = 0L
        var lastGrowth = start
    }

    private var anchor = 0L
    private var lastPoll = 0L
    private var snapshot: Map<Int, Long>? = null
    private val sessions = HashMap<Int, Session>()

    private fun startOfDay(t: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = t
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    @Suppress("DEPRECATION")
    private fun totals(start: Long, end: Long): Map<Int, Long> {
        val out = HashMap<Int, Long>()
        val nsm = ctx.getSystemService(NetworkStatsManager::class.java) ?: return out
        for (type in intArrayOf(ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_MOBILE)) {
            try {
                val s = nsm.querySummary(type, null, start, end)
                val b = NetworkStats.Bucket()
                while (s.hasNextBucket()) {
                    s.getNextBucket(b)
                    out[b.uid] = (out[b.uid] ?: 0L) + b.txBytes
                }
                s.close()
            } catch (e: Exception) {
            }
        }
        return out
    }

    /** Call about once a minute. */
    fun poll() {
        if (!Scanner(ctx).hasUsageAccess()) return
        val now = System.currentTimeMillis()
        val day = startOfDay(now)
        if (day != anchor) {
            // new day → new counting base
            anchor = day
            snapshot = null
        }
        val cur = totals(anchor, now + 60_000)
        val prev = snapshot
        snapshot = cur
        val pollStart = if (lastPoll == 0L) now else lastPoll
        lastPoll = now
        if (prev != null) {
            for ((uid, tx) in cur) {
                if (uid < Process.FIRST_APPLICATION_UID || uid == Process.myUid()) continue
                val delta = tx - (prev[uid] ?: 0L)
                if (delta < STEP_BYTES) continue
                val s = sessions.getOrPut(uid) { Session(uid, pollStart) }
                s.bytes += delta
                s.lastGrowth = now
            }
        }
        val done = sessions.values.filter { now - it.lastGrowth >= QUIET_MS }
        for (s in done) {
            sessions.remove(s.uid)
            if (s.bytes >= MIN_UPLOAD) finish(s)
        }
    }

    private fun finish(s: Session) {
        val pkg = ctx.packageManager.getPackagesForUid(s.uid)?.firstOrNull() ?: return
        val end = s.lastGrowth
        val manual = wasOnScreen(pkg, s.start - 60_000, end)
        val mode = if (manual) "manual" else "auto"

        val db = Db.get(ctx)
        val hits = Trail.byCompany(db.domains(s.uid, s.start - 120_000, end + 30_000, 30))
            .sortedWith(compareByDescending<Trail.CompanyHit> { it.uploadish }.thenByDescending { it.count })
            .take(4)
        val dests = hits.joinToString("|") { h -> h.company + ": " + h.domains.take(3).joinToString(", ") }
        db.insertUpload(Db.Upload(s.start, end, s.uid, pkg, s.bytes, mode, dests))

        if (mode == "auto" && s.bytes >= ALERT_BYTES && !AllowedCloud.isAllowed(ctx, pkg) &&
            pkg !in prefsTrusted()
        ) {
            val prefs = ctx.getSharedPreferences("guard", Context.MODE_PRIVATE)
            val key = "upl_alert_$pkg"
            val now = System.currentTimeMillis()
            if (now - prefs.getLong(key, 0L) >= ALERT_COOLDOWN_MS) {
                prefs.edit().putLong(key, now).apply()
                val label = PhotoLock.label(ctx, pkg)
                val where = if (hits.isEmpty()) "an unknown server" else hits.joinToString(", ") { it.company }
                Notifier.alert(
                    ctx, pkg, label,
                    "$label uploaded ${fmtBytes(s.bytes)} on its own",
                    "$label sent ${fmtBytes(s.bytes)} while you weren't using it → $where. " +
                        "Open Halo Guard → Uploads to see the trail, or turn on Photo lock for it."
                )
            }
        }
    }

    private fun prefsTrusted(): Set<String> =
        ctx.getSharedPreferences("guard", Context.MODE_PRIVATE).getStringSet("trusted", emptySet()) ?: emptySet()

    /** True if [pkg] was the app on screen at any point between start and end. */
    private fun wasOnScreen(pkg: String, start: Long, end: Long): Boolean {
        val usm = ctx.getSystemService(UsageStatsManager::class.java) ?: return false
        val events = try {
            usm.queryEvents(start - 30 * 60 * 1000L, end)
        } catch (e: Exception) {
            return false
        }
        val e = UsageEvents.Event()
        var onScreen = false
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.packageName != pkg) continue
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    if (e.timeStamp in start..end) return true
                    onScreen = true
                }
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> {
                    if (e.timeStamp >= start && onScreen) return true // was open when the upload began
                    onScreen = false
                }
            }
        }
        return onScreen
    }
}
