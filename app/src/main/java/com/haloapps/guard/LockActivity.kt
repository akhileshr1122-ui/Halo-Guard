package com.haloapps.guard

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LockActivity : BaseActivity() {

    private lateinit var setupBox: LinearLayout
    private lateinit var optionsBox: LinearLayout
    private lateinit var lockedBox: LinearLayout
    private lateinit var candidatesBox: LinearLayout
    private lateinit var logBox: LinearLayout
    private var loading = false

    private class Candidate(val pkg: String, val label: String, val photo: PhotoAccess)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = setupScreen(
            "Photo lock",
            "Chosen apps lose photo & file access the moment you close them. Removing access also " +
                "stops the app, so it can't keep uploading in the background.",
            2
        )
        setupBox = vbox()
        content.addView(setupBox)
        optionsBox = vbox()
        content.addView(optionsBox)

        content.addView(sectionLabel("Locked apps"))
        lockedBox = vbox()
        content.addView(lockedBox)

        content.addView(sectionLabel("Other apps that can see all your photos"))
        candidatesBox = vbox()
        content.addView(candidatesBox)

        content.addView(sectionLabel("Activity"))
        logBox = vbox()
        content.addView(logBox)
    }

    override fun onResume() {
        super.onResume()
        renderSetup()
        renderOptions()
        load()
        if (PhotoLock.lockedApps(this).isNotEmpty()) PhotoLock.startService(this)
    }

    private fun renderSetup() {
        setupBox.removeAllViews()
        if (!Scanner(this).hasUsageAccess()) {
            val c = card()
            c.addView(text("Allow Usage access", 16f, amber, bold = true))
            c.addView(infoLine("Photo lock needs it to know when you close an app.", textColor))
            c.addView(button("Open Usage access settings", accent) {
                try {
                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:$packageName")))
                } catch (e: Exception) {
                    safeStart(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }
            }.apply { setPadding(dp(12), dp(8), dp(12), dp(8)) })
            setupBox.addView(c)
        }

        val c = card()
        when (Shell.state(this)) {
            Shell.State.NOT_INSTALLED -> {
                c.addView(text("Step 1: install Shizuku", 16f, amber, bold = true))
                c.addView(
                    infoLine(
                        "Android doesn't let one app change another app's permissions. Shizuku is a free, " +
                            "open-source helper that gives Halo Guard that ability without rooting your phone.",
                        textColor
                    )
                )
                c.addView(spaced(button("Get Shizuku from Play Store", accent) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${Shell.SHIZUKU_PKG}")))
                    } catch (e: Exception) {
                        safeStart(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${Shell.SHIZUKU_PKG}")))
                    }
                }))
            }
            Shell.State.NOT_RUNNING -> {
                c.addView(text("Step 2: start Shizuku", 16f, amber, bold = true))
                c.addView(
                    infoLine(
                        "Open Shizuku → \"Start via Wireless debugging\" and follow its pairing steps (one-time). " +
                            "Needs Developer options + Wi-Fi. After every phone restart, tap Start in Shizuku again.",
                        textColor
                    )
                )
                c.addView(spaced(button("Open Shizuku", accent) {
                    packageManager.getLaunchIntentForPackage(Shell.SHIZUKU_PKG)?.let { safeStart(it) }
                }))
            }
            Shell.State.NEEDS_PERMISSION -> {
                c.addView(text("Step 3: allow Halo Guard in Shizuku", 16f, amber, bold = true))
                c.addView(infoLine("Tap below and choose \"Allow all the time\".", textColor))
                c.addView(spaced(button("Allow", accent) {
                    Shell.requestPermission()
                    setupBox.postDelayed({ renderSetup() }, 1500)
                }))
            }
            Shell.State.READY -> {
                c.addView(text("● Photo lock is ready", 16f, green, bold = true))
                c.addView(infoLine("Shizuku is running and Halo Guard is allowed.", textColor))
            }
        }
        setupBox.addView(c)
    }

    private fun spaced(v: android.view.View): android.view.View {
        v.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) }
        return v
    }

    private fun renderOptions() {
        optionsBox.removeAllViews()
        optionsBox.addView(sectionLabel("Take access away after I leave the app"))
        val (gs, gr) = chipRow()
        val grace = PhotoLock.graceSeconds(this)
        listOf(3 to "Right away", 10 to "After 10 sec", 60 to "After 1 min").forEach { (s, name) ->
            gr.addView(chip(name, grace == s) {
                PhotoLock.setGraceSeconds(this, s)
                renderOptions()
            })
        }
        optionsBox.addView(gs)

        optionsBox.addView(sectionLabel("When I open the app again"))
        val (rs, rr) = chipRow()
        val auto = PhotoLock.autoRestore(this)
        rr.addView(chip("It asks me for access", !auto) {
            PhotoLock.setAutoRestore(this, false)
            renderOptions()
        })
        rr.addView(chip("Give it back automatically", auto) {
            PhotoLock.setAutoRestore(this, true)
            renderOptions()
        })
        optionsBox.addView(rs)
    }

    private fun load() {
        if (loading) return
        loading = true
        val ctx = applicationContext
        Thread {
            val scanner = Scanner(ctx)
            val locked = PhotoLock.lockedApps(ctx)
            val cands = ArrayList<Candidate>()
            try {
                for (ai in scanner.userApps()) {
                    if (ai.packageName in locked) continue
                    val p = scanner.permissions(ai)
                    if (p.photo >= PhotoAccess.FULL) cands.add(Candidate(ai.packageName, scanner.label(ai), p.photo))
                }
            } catch (e: Exception) {
            }
            cands.sortBy { it.label.lowercase() }
            val lockedInfo = locked.map { pkg ->
                Triple(pkg, PhotoLock.label(ctx, pkg), PhotoLock.hasAccess(ctx, pkg))
            }.sortedBy { it.second.lowercase() }
            val events = try { Db.get(ctx).events(40) } catch (e: Exception) { emptyList<Db.Event>() }
            runOnUiThread {
                loading = false
                if (!isFinishing && !isDestroyed) render(lockedInfo, cands, events)
            }
        }.start()
    }

    private val timeFmt = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())

    private fun render(locked: List<Triple<String, String, Boolean>>, cands: List<Candidate>, events: List<Db.Event>) {
        lockedBox.removeAllViews()
        if (locked.isEmpty()) {
            lockedBox.addView(infoLine("No apps locked yet. Pick some below.", subColor))
        }
        for ((pkg, label, has) in locked) {
            lockedBox.addView(appRow(pkg, label,
                if (has) "Has access right now (in use or you allowed it)" else "🔒 No photo access",
                if (has) amber else green,
                "Unlock", subColor
            ) {
                PhotoLock.setLocked(this, pkg, false)
                load()
            })
        }

        candidatesBox.removeAllViews()
        if (cands.isEmpty()) {
            candidatesBox.addView(infoLine("None — nice.", subColor))
        } else {
            candidatesBox.addView(button("Lock all ${cands.size} apps", amber) {
                for (cd in cands) PhotoLock.setLocked(this, cd.pkg, true)
                load()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
            })
            for (cd in cands) {
                candidatesBox.addView(appRow(cd.pkg, cd.label, cd.photo.label, amber, "Lock", accent) {
                    PhotoLock.setLocked(this, cd.pkg, true)
                    load()
                })
            }
        }

        logBox.removeAllViews()
        if (events.isEmpty()) logBox.addView(infoLine("Nothing yet.", subColor))
        for (ev in events) {
            logBox.addView(infoLine("${timeFmt.format(Date(ev.ts))}  ·  ${ev.msg}", textColor))
        }
    }

    private fun appRow(
        pkg: String, label: String, status: String, statusColor: Int,
        action: String, actionColor: Int, onAction: () -> Unit
    ): LinearLayout {
        val c = card()
        val row = hbox()
        row.gravity = Gravity.CENTER_VERTICAL
        val icon = ImageView(this)
        try {
            icon.setImageDrawable(packageManager.getApplicationIcon(pkg))
        } catch (e: Exception) {
        }
        row.addView(icon, LinearLayout.LayoutParams(dp(36), dp(36)))
        val col = vbox()
        col.setPadding(dp(12), 0, dp(8), 0)
        col.addView(text(label, 15f, textColor, bold = true))
        col.addView(text(status, 12f, statusColor))
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(button(action, actionColor, onAction))
        c.addView(row)
        return c
    }
}
