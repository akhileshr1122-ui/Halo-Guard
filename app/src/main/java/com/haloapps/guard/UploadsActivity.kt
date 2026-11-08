package com.haloapps.guard

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A log of every detected upload, sorted into automatic / manual / allowed cloud. */
class UploadsActivity : BaseActivity() {

    private lateinit var statusBox: LinearLayout
    private lateinit var periodRow: LinearLayout
    private lateinit var filterRow: LinearLayout
    private lateinit var summary: TextView
    private lateinit var listBox: LinearLayout
    private var days = 7
    private var filter = 0 // 0 all, 1 automatic, 2 manual, 3 allowed cloud
    private var rows: List<Db.Upload> = emptyList()
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = setupScreen(
            "Uploads",
            "Every time an app sends out 1 MB or more: when it happened, whether you did it, and where it went.",
            1
        )
        statusBox = vbox()
        content.addView(statusBox)

        content.addView(sectionLabel("Time window"))
        val (ps, pr) = chipRow()
        periodRow = pr
        content.addView(ps)

        content.addView(sectionLabel("Show"))
        val (fs, fr) = chipRow()
        filterRow = fr
        content.addView(fs)

        summary = text("", 14f, textColor).apply { setPadding(0, dp(16), 0, dp(10)) }
        content.addView(summary)
        listBox = vbox()
        content.addView(listBox)

        content.addView(
            text(
                "How uploads are sorted:\n" +
                    "• Automatic: the app sent the data while it was closed or in the background. Nobody pressed upload.\n" +
                    "• Manual: you had the app open on screen at the time, so you most likely sent it (a post, a chat photo…).\n" +
                    "• Allowed cloud: apps you're fine with, like Google Drive and OneDrive. They're listed but never alert you.\n\n" +
                    "Halo Guard sees the size, timing and destination of each upload, not its contents. " +
                    "An upload can be photos, videos, files or other app data. Android updates its counters " +
                    "every few minutes, so times are approximate.",
                12f, subColor
            ).apply { setPadding(0, dp(24), 0, 0) }
        )
    }

    override fun onResume() {
        super.onResume()
        renderStatus()
        renderChips()
        load()
    }

    private fun renderStatus() {
        statusBox.removeAllViews()
        if (!GuardVpnService.running) {
            val c = card()
            c.addView(text("Upload logging is off", 16f, amber, bold = true))
            c.addView(
                infoLine(
                    "Uploads are logged while tracking is on. Turn it on in the Photo trail tab.",
                    textColor
                )
            )
            c.addView(button("Go to Photo trail", accent) {
                startActivity(Intent(this, TrailActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(10) }
            })
            statusBox.addView(c)
        } else {
            statusBox.addView(
                text(
                    "● Logging uploads. You'll get an alert when an app uploads 5 MB+ on its own.",
                    13f, green
                ).apply { setPadding(0, 0, 0, dp(8)) }
            )
        }
    }

    private fun renderChips() {
        periodRow.removeAllViews()
        listOf(1 to "24 hours", 7 to "7 days", 30 to "30 days").forEach { (d, name) ->
            periodRow.addView(chip(name, d == days) {
                if (days != d) {
                    days = d
                    renderChips()
                    load()
                }
            })
        }
        filterRow.removeAllViews()
        listOf("All", "Automatic", "Manual", "Allowed cloud").forEachIndexed { i, name ->
            filterRow.addView(chip(name, i == filter) {
                filter = i
                renderChips()
                render()
            })
        }
    }

    private fun load() {
        if (loading) return
        loading = true
        val d = days
        val ctx = applicationContext
        Thread {
            val r = try {
                Db.get(ctx).uploads(System.currentTimeMillis() - d * 86_400_000L)
            } catch (e: Exception) {
                emptyList<Db.Upload>()
            }
            runOnUiThread {
                loading = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (d != days) load() else {
                    rows = r
                    render()
                }
            }
        }.start()
    }

    private fun kind(u: Db.Upload): Int = when {
        AllowedCloud.isAllowed(this, u.pkg) -> 3
        u.mode == "auto" -> 1
        else -> 2
    }

    private val dayFmt = SimpleDateFormat("EEE MMM d, h:mm a", Locale.getDefault())
    private val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())

    private fun render() {
        listBox.removeAllViews()
        val auto = rows.filter { kind(it) == 1 }
        val manual = rows.filter { kind(it) == 2 }
        val cloud = rows.filter { kind(it) == 3 }
        summary.text = "${rows.size} uploads · ${auto.size} automatic (${fmtBytes(auto.sumOf { it.bytes })}) · " +
            "${manual.size} manual · ${cloud.size} allowed cloud"

        val shown = if (filter == 0) rows else rows.filter { kind(it) == filter }
        if (shown.isEmpty()) {
            listBox.addView(infoLine(if (rows.isEmpty()) "No uploads logged yet." else "Nothing in this group.", subColor))
            return
        }
        for (u in shown.take(200)) listBox.addView(uploadCard(u))
    }

    private fun uploadCard(u: Db.Upload): LinearLayout {
        val k = kind(u)
        val c = card()
        val top = hbox()
        top.gravity = Gravity.CENTER_VERTICAL
        val icon = ImageView(this)
        try {
            icon.setImageDrawable(packageManager.getApplicationIcon(u.pkg))
        } catch (e: Exception) {
        }
        top.addView(icon, LinearLayout.LayoutParams(dp(36), dp(36)))
        val col = vbox()
        col.setPadding(dp(12), 0, dp(8), 0)
        col.addView(text("${appLabel(u.pkg)} · ${fmtBytes(u.bytes)}", 15f, textColor, bold = true))
        col.addView(text(dayFmt.format(Date(u.start)) + " – " + timeFmt.format(Date(u.finish)), 12f, subColor))
        top.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val (badgeText, badgeColor) = when (k) {
            1 -> "AUTOMATIC" to red
            2 -> "MANUAL" to accent
            else -> "ALLOWED CLOUD" to green
        }
        top.addView(text(badgeText, 10f, bgColor, bold = true).apply {
            background = rounded(badgeColor, 8)
            setPadding(dp(8), dp(4), dp(8), dp(4))
        })
        c.addView(top)

        c.addView(
            infoLine(
                when (k) {
                    1 -> "Sent while the app was closed — not started by you."
                    2 -> "You had the app open at the time."
                    else -> "A cloud app you allow."
                },
                if (k == 1) red else subColor
            )
        )

        val dests = u.dests.split('|').filter { it.isNotBlank() }
        if (dests.isEmpty()) {
            c.addView(infoLine("Went to: not recorded (lookup was cached or made before tracking).", subColor))
        } else {
            c.addView(text("Went to:", 13f, subColor, bold = true).apply { setPadding(0, dp(8), 0, 0) })
            for (d in dests) {
                val upload = d.substringAfter(": ", "").split(", ").any { Owners.looksLikeUpload(it) }
                c.addView(infoLine("• $d" + if (upload) "  ⬆" else "", if (upload && k == 1) red else textColor))
            }
        }

        val row = hbox()
        row.setPadding(0, dp(12), 0, 0)
        val allowed = AllowedCloud.isAllowed(this, u.pkg)
        row.addView(button(if (allowed) "Allowed ✓" else "Allow this app", if (allowed) green else subColor) {
            AllowedCloud.set(this, u.pkg, !allowed)
            render()
        })
        row.addView(space())
        val locked = PhotoLock.isLocked(this, u.pkg)
        row.addView(button(if (locked) "Photo lock ✓" else "Lock photos", if (locked) green else amber) {
            PhotoLock.setLocked(this, u.pkg, !locked)
            render()
        })
        row.addView(space())
        row.addView(button("Permissions", accent) {
            safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${u.pkg}")))
        })
        c.addView(row)
        return c
    }
}
