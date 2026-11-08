package com.haloapps.guard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder

/**
 * Watches which app is on screen (via Usage access). When a locked app has been closed for the
 * grace period, its photo access is taken away. If "give it back automatically" is on, access
 * is restored the moment the app is opened again.
 */
class PhotoLockService : Service() {

    companion object {
        private const val CHANNEL = "photo_lock"
        private const val NOTIF_ID = 7001
        private const val TICK_MS = 2000L
        private val HELPERS = setOf(
            "android",
            "com.android.systemui",
            "com.android.intentresolver",
            "com.google.android.providers.media.module",
            "com.android.providers.media.module",
            "com.google.android.photopicker",
            "com.google.android.documentsui",
            "com.android.documentsui",
            "com.google.android.permissioncontroller",
            "com.android.permissioncontroller",
            "com.sec.android.app.camera",
            "com.sec.android.gallery3d",
            "com.samsung.android.app.sharelive",
            "com.samsung.android.honeyboard",
            "com.google.android.inputmethod.latin"
        )
    }

    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private var lastQuery = 0L
    private var foreground: String? = null
    private val leftAt = HashMap<String, Long>()
    private val startedAt = System.currentTimeMillis()
    private var lastStatus = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Photo lock", NotificationManager.IMPORTANCE_LOW)
            )
        }
        goForeground("Starting…")
        thread = HandlerThread("photo-lock")
        thread.start()
        handler = Handler(thread.looper)
        lastQuery = System.currentTimeMillis() - 10 * 60 * 1000L // learn what's on screen now
        handler.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground(if (lastStatus.isEmpty()) "Starting…" else lastStatus)
        return START_STICKY
    }

    private fun goForeground(text: String) {
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, LockActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentTitle("Photo lock is on")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun setStatus(text: String) {
        if (text == lastStatus) return
        lastStatus = text
        goForeground(text)
    }

    private val tick = object : Runnable {
        override fun run() {
            try {
                step()
            } catch (e: Exception) {
                // keep going
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    private fun step() {
        val locked = PhotoLock.lockedApps(this)
        if (locked.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        // 1) Follow app open/close events
        val usm = getSystemService(UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(lastQuery, now)
        lastQuery = now
        val e = UsageEvents.Event()
        val opened = ArrayList<String>()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val pkg = e.packageName ?: continue
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    // Photo pickers, share sheets, permission dialogs, camera… opened FROM the app
                    // don't count as leaving it.
                    if (pkg !in HELPERS || pkg in locked) {
                        val prev = foreground
                        if (prev != null && prev != pkg && !leftAt.containsKey(prev)) leftAt[prev] = e.timeStamp
                        foreground = pkg
                        leftAt.remove(pkg)
                        opened.add(pkg)
                    }
                }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    foreground?.let { if (!leftAt.containsKey(it)) leftAt[it] = e.timeStamp }
                    foreground = null
                }
            }
        }

        val ready = Shell.state(this) == Shell.State.READY
        if (!ready) {
            setStatus("Paused — open Shizuku and start it, then come back")
            return
        }

        // 2) Opened again → give access back (if enabled)
        if (PhotoLock.autoRestore(this)) {
            for (pkg in opened.distinct()) {
                if (pkg in locked && PhotoLock.isRevokedByUs(this, pkg)) {
                    PhotoLock.restore(this, pkg, "you opened it")
                }
            }
        }

        // 3) Closed long enough → take access away
        val grace = PhotoLock.graceSeconds(this) * 1000L
        for (pkg in locked) {
            if (pkg == foreground) continue
            val left = leftAt[pkg] ?: startedAt
            if (now - left < grace) continue
            if (!PhotoLock.hasAccess(this, pkg)) continue
            PhotoLock.revoke(this, pkg, "app was closed")
        }
        setStatus("Protecting ${locked.size} app${if (locked.size == 1) "" else "s"}")
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
        super.onDestroy()
    }
}
