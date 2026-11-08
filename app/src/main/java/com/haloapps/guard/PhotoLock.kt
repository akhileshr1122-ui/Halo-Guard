package com.haloapps.guard

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build

/**
 * Photo lock: for apps you choose, Halo Guard takes away photo/video/file access as soon as you
 * leave the app, and (optionally) hands it back the moment you open the app again.
 * Taking a permission away also stops the app — so it can't keep uploading in the background.
 */
object PhotoLock {

    val PHOTO_PERMS = listOf(
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.READ_MEDIA_VIDEO",
        "android.permission.READ_EXTERNAL_STORAGE"
    )
    private const val ALL_FILES = "ALL_FILES"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("guard", Context.MODE_PRIVATE)

    fun lockedApps(ctx: Context): Set<String> =
        HashSet(prefs(ctx).getStringSet("locked", emptySet()) ?: emptySet())

    fun isLocked(ctx: Context, pkg: String) = pkg in lockedApps(ctx)

    fun setLocked(ctx: Context, pkg: String, on: Boolean) {
        val set = lockedApps(ctx).toMutableSet()
        if (on) set.add(pkg) else set.remove(pkg)
        prefs(ctx).edit().putStringSet("locked", set).apply()
        Db.get(ctx).logEvent(pkg, if (on) "Photo lock turned on" else "Photo lock turned off")
        if (!on && isRevokedByUs(ctx, pkg)) {
            // give access back when the user removes the lock
            Thread { restore(ctx, pkg, "Lock removed — access given back") }.start()
        }
        if (set.isNotEmpty()) startService(ctx)
    }

    fun graceSeconds(ctx: Context): Int = prefs(ctx).getInt("lock_grace", 10)
    fun setGraceSeconds(ctx: Context, s: Int) = prefs(ctx).edit().putInt("lock_grace", s).apply()
    fun autoRestore(ctx: Context): Boolean = prefs(ctx).getBoolean("lock_auto_restore", false)
    fun setAutoRestore(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("lock_auto_restore", on).apply()

    fun startService(ctx: Context) {
        val i = Intent(ctx, PhotoLockService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        } catch (e: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    fun grantedPhotoPerms(ctx: Context, pkg: String): List<String> {
        val pi = try {
            ctx.packageManager.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
        } catch (e: Exception) {
            return emptyList()
        }
        val req = pi.requestedPermissions ?: return emptyList()
        val flags = pi.requestedPermissionsFlags ?: return emptyList()
        val out = ArrayList<String>()
        for (p in PHOTO_PERMS) {
            val i = req.indexOf(p)
            if (i >= 0 && i < flags.size && (flags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0) out.add(p)
        }
        return out
    }

    fun allFilesAllowed(ctx: Context, pkg: String): Boolean = try {
        val ai = ctx.packageManager.getApplicationInfo(pkg, 0)
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        ops != null && ops.unsafeCheckOpNoThrow("android:manage_external_storage", ai.uid, pkg) ==
            AppOpsManager.MODE_ALLOWED
    } catch (e: Exception) {
        false
    }

    fun hasAccess(ctx: Context, pkg: String): Boolean =
        grantedPhotoPerms(ctx, pkg).isNotEmpty() || allFilesAllowed(ctx, pkg)

    fun isRevokedByUs(ctx: Context, pkg: String): Boolean = prefs(ctx).contains("revoked_$pkg")

    /** Takes photo/file access away. Returns true if something was revoked. */
    fun revoke(ctx: Context, pkg: String, why: String): Boolean {
        val perms = grantedPhotoPerms(ctx, pkg)
        val allFiles = allFilesAllowed(ctx, pkg)
        if (perms.isEmpty() && !allFiles) return false
        val cmds = perms.map { "pm revoke $pkg $it" }.toMutableList()
        if (allFiles) cmds.add("appops set $pkg MANAGE_EXTERNAL_STORAGE ignore")
        val (code, out) = Shell.run(cmds.joinToString("; "))
        val stillHas = hasAccess(ctx, pkg)
        val label = label(ctx, pkg)
        if (stillHas) {
            Db.get(ctx).logEvent(pkg, "Couldn't remove photo access from $label ($code: ${out.take(80)})")
            return false
        }
        // remember exactly what we took, so we can give exactly that back
        val previous = prefs(ctx).getString("revoked_$pkg", "") ?: ""
        val taken = (previous.split(',').filter { it.isNotBlank() } + perms +
            (if (allFiles) listOf(ALL_FILES) else emptyList())).distinct()
        prefs(ctx).edit().putString("revoked_$pkg", taken.joinToString(",")).apply()
        Db.get(ctx).logEvent(pkg, "🔒 Took photo access from $label — $why")
        return true
    }

    /** Gives back what we took away. */
    fun restore(ctx: Context, pkg: String, why: String): Boolean {
        val saved = prefs(ctx).getString("revoked_$pkg", null) ?: return false
        val items = saved.split(',').filter { it.isNotBlank() }
        val cmds = ArrayList<String>()
        for (it in items) {
            if (it == ALL_FILES) cmds.add("appops set $pkg MANAGE_EXTERNAL_STORAGE allow")
            else cmds.add("pm grant $pkg $it")
        }
        if (cmds.isNotEmpty()) Shell.run(cmds.joinToString("; "))
        prefs(ctx).edit().remove("revoked_$pkg").apply()
        Db.get(ctx).logEvent(pkg, "🔓 Gave photo access back to ${label(ctx, pkg)} — $why")
        return true
    }

    /** Forget our record without granting (the app asked again and you allowed it yourself). */
    fun forget(ctx: Context, pkg: String) {
        prefs(ctx).edit().remove("revoked_$pkg").apply()
    }

    fun label(ctx: Context, pkg: String): String = try {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }
}
