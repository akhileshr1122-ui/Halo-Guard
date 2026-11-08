package com.haloapps.guard

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * Runs shell commands (pm grant / pm revoke) through Shizuku. A normal app is not allowed to
 * change another app's permissions; Shizuku gives it the same rights as "adb shell" without root.
 */
object Shell {
    const val SHIZUKU_PKG = "moe.shizuku.privileged.api"

    enum class State { NOT_INSTALLED, NOT_RUNNING, NEEDS_PERMISSION, READY }

    fun state(ctx: Context): State {
        val alive = try { Shizuku.pingBinder() } catch (e: Throwable) { false }
        if (!alive) {
            val installed = try {
                ctx.packageManager.getPackageInfo(SHIZUKU_PKG, 0); true
            } catch (e: Exception) { false }
            return if (installed) State.NOT_RUNNING else State.NOT_INSTALLED
        }
        val granted = try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) { false }
        return if (granted) State.READY else State.NEEDS_PERMISSION
    }

    fun requestPermission() {
        try { Shizuku.requestPermission(77) } catch (e: Throwable) { }
    }

    /** Runs a command as the shell user. Returns (exitCode, output). */
    fun run(cmd: String): Pair<Int, String> {
        return try {
            val m = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java, Array<String>::class.java, String::class.java
            )
            m.isAccessible = true
            val p = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
            val out = p.inputStream.bufferedReader().readText()
            val err = p.errorStream.bufferedReader().readText()
            val code = p.waitFor()
            code to (out + err).trim()
        } catch (e: Throwable) {
            -1 to (e.message ?: e.toString())
        }
    }
}
