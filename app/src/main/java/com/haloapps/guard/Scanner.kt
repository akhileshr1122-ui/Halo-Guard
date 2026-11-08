package com.haloapps.guard

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Process

enum class PhotoAccess(val label: String) {
    NONE("No photo access"),
    PARTIAL("Only photos you picked"),
    FULL("Can see ALL your photos & videos"),
    ALL_FILES("Can read EVERY file on your phone")
}

data class AppReport(
    val pkg: String,
    val label: String,
    val uid: Int,
    val photo: PhotoAccess,
    val sensitive: List<String>,
    val trackers: List<String>,
    val upTotal: Long,
    val upBackground: Long,
    val download: Long,
    val expected: Boolean,
    val trusted: Boolean,
    val score: Int,
    val reasons: List<String>
) {
    val level: String
        get() = when {
            score >= 60 -> "HIGH"
            score >= 35 -> "MEDIUM"
            else -> "LOW"
        }
}

class Usage {
    var total = 0L
    var background = 0L
    var rx = 0L
}

class Perms(val photo: PhotoAccess, val sensitive: List<String>)

class Scanner(private val ctx: Context) {

    private val pm: PackageManager = ctx.packageManager

    companion object {
        const val MB = 1024L * 1024L

        /** Apps whose whole job is to upload/sync photos or chats. Uploads from them are expected. */
        val EXPECTED_UPLOADERS = setOf(
            "com.google.android.apps.photos",
            "com.google.android.apps.docs",
            "com.microsoft.skydrive",
            "com.dropbox.android",
            "com.samsung.android.scloud",
            "com.sec.android.gallery3d",
            "com.amazon.clouddrive.photos",
            "com.whatsapp",
            "com.whatsapp.w4b",
            "org.telegram.messenger",
            "org.thoughtcrime.securesms",
            "com.google.android.youtube",
            "com.android.vending",
            "com.google.android.gms"
        )
    }

    fun hasUsageAccess(): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java) ?: return false
        return ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName
        ) == AppOpsManager.MODE_ALLOWED
    }

    /** Installed apps the user actually interacts with: everything they installed + launchable preinstalled apps. */
    @Suppress("DEPRECATION")
    fun userApps(): List<ApplicationInfo> =
        pm.getInstalledApplications(0).filter { ai ->
            ai.packageName != ctx.packageName &&
                ((ai.flags and ApplicationInfo.FLAG_SYSTEM) == 0 ||
                    pm.getLaunchIntentForPackage(ai.packageName) != null)
        }

    fun label(ai: ApplicationInfo): String =
        try { pm.getApplicationLabel(ai).toString() } catch (e: Exception) { ai.packageName }

    @Suppress("DEPRECATION")
    fun permissions(ai: ApplicationInfo): Perms {
        val pi: PackageInfo = try {
            pm.getPackageInfo(ai.packageName, PackageManager.GET_PERMISSIONS)
        } catch (e: Exception) {
            return Perms(PhotoAccess.NONE, emptyList())
        }
        val req: Array<String> = pi.requestedPermissions ?: return Perms(PhotoAccess.NONE, emptyList())
        val flags: IntArray = pi.requestedPermissionsFlags ?: IntArray(req.size)

        fun granted(p: String): Boolean {
            val i = req.indexOf(p)
            return i >= 0 && i < flags.size && (flags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
        }

        var allFiles = false
        if (req.contains("android.permission.MANAGE_EXTERNAL_STORAGE")) {
            allFiles = try {
                val ops = ctx.getSystemService(AppOpsManager::class.java)
                ops != null && ops.unsafeCheckOpNoThrow(
                    "android:manage_external_storage", ai.uid, ai.packageName
                ) == AppOpsManager.MODE_ALLOWED
            } catch (e: Exception) {
                false
            }
        }

        val photo = when {
            allFiles -> PhotoAccess.ALL_FILES
            granted("android.permission.READ_MEDIA_IMAGES") ||
                granted("android.permission.READ_MEDIA_VIDEO") -> PhotoAccess.FULL
            granted("android.permission.READ_MEDIA_VISUAL_USER_SELECTED") -> PhotoAccess.PARTIAL
            granted("android.permission.READ_EXTERNAL_STORAGE") -> PhotoAccess.FULL // older apps
            else -> PhotoAccess.NONE
        }

        val sensitive = ArrayList<String>()
        if (granted("android.permission.ACCESS_BACKGROUND_LOCATION")) sensitive.add("Location (all the time)")
        else if (granted("android.permission.ACCESS_FINE_LOCATION") ||
            granted("android.permission.ACCESS_COARSE_LOCATION")) sensitive.add("Location")
        if (granted("android.permission.READ_CONTACTS")) sensitive.add("Contacts")
        if (granted("android.permission.CAMERA")) sensitive.add("Camera")
        if (granted("android.permission.RECORD_AUDIO")) sensitive.add("Microphone")
        if (granted("android.permission.READ_SMS")) sensitive.add("SMS")
        if (granted("android.permission.READ_CALL_LOG")) sensitive.add("Call log")
        return Perms(photo, sensitive)
    }

    /**
     * Bytes each app (by UID) sent and received over Wi-Fi + mobile data between start and end.
     * "background" = sent while the app was NOT in the foreground.
     */
    @Suppress("DEPRECATION")
    fun uploads(start: Long, end: Long): Map<Int, Usage> {
        val out = HashMap<Int, Usage>()
        val nsm = ctx.getSystemService(NetworkStatsManager::class.java) ?: return out
        for (type in intArrayOf(ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_MOBILE)) {
            try {
                val stats = nsm.querySummary(type, null, start, end)
                val b = NetworkStats.Bucket()
                while (stats.hasNextBucket()) {
                    stats.getNextBucket(b)
                    val u = out.getOrPut(b.uid) { Usage() }
                    u.total += b.txBytes
                    u.rx += b.rxBytes
                    if (b.state == NetworkStats.Bucket.STATE_DEFAULT) u.background += b.txBytes
                }
                stats.close()
            } catch (e: Exception) {
                // no usage access or this network type has no data
            }
        }
        return out
    }

    fun scan(days: Int, trusted: Set<String>): List<AppReport> {
        val end = System.currentTimeMillis()
        val start = end - days * 86_400_000L
        val usage: Map<Int, Usage> = if (hasUsageAccess()) uploads(start, end) else emptyMap()
        return userApps()
            .map { ai -> build(ai, usage[ai.uid], trusted) }
            .sortedWith(compareBy<AppReport> { it.trusted }.thenByDescending { it.score })
    }

    private fun build(ai: ApplicationInfo, u: Usage?, trusted: Set<String>): AppReport {
        val pkg = ai.packageName
        val p = permissions(ai)
        val tr = Trackers.detect(pm, pkg)
        val total = u?.total ?: 0L
        val bg = u?.background ?: 0L
        val rx = u?.rx ?: 0L
        val expected = pkg in EXPECTED_UPLOADERS

        val reasons = ArrayList<String>()
        var s = 0

        when (p.photo) {
            PhotoAccess.ALL_FILES -> { s += 40; reasons.add("Can open every photo, video and document on your phone") }
            PhotoAccess.FULL -> { s += 30; reasons.add("Can see all your photos and videos") }
            PhotoAccess.PARTIAL -> s += 5
            PhotoAccess.NONE -> {}
        }

        val bgMb = bg / MB
        var upPts = when {
            bgMb >= 200 -> 35
            bgMb >= 50 -> 25
            bgMb >= 5 -> 10
            else -> 0
        }
        if (expected) upPts /= 3
        if (upPts > 0) {
            reasons.add(
                if (expected) "Uploads in the background (normal for a backup / sync / chat app)"
                else "Sends data out while you are not using it"
            )
        }
        s += upPts

        if (p.photo >= PhotoAccess.FULL && bgMb >= 5 && !expected) {
            s += 10
            reasons.add("Has photo access AND uploads in the background — check this one")
        }

        if (tr.isNotEmpty()) {
            s += minOf(tr.size * 5, 25)
            reasons.add("Contains ${tr.size} tracking/advertising SDK" + if (tr.size > 1) "s" else "")
        }
        s += minOf(p.sensitive.size * 4, 16)

        return AppReport(
            pkg = pkg,
            label = label(ai),
            uid = ai.uid,
            photo = p.photo,
            sensitive = p.sensitive,
            trackers = tr,
            upTotal = total,
            upBackground = bg,
            download = rx,
            expected = expected,
            trusted = pkg in trusted,
            score = minOf(s, 100),
            reasons = reasons
        )
    }
}

fun fmtBytes(b: Long): String = when {
    b >= 1024L * 1024L * 1024L -> String.format("%.1f GB", b / (1024.0 * 1024.0 * 1024.0))
    b >= 1024L * 1024L -> String.format("%.1f MB", b / (1024.0 * 1024.0))
    b >= 1024L -> "${b / 1024} KB"
    else -> "$b B"
}
