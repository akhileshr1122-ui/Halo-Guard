package com.haloapps.guard

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private val bgColor = Color.parseColor("#0E1116")
    private val cardColor = Color.parseColor("#171B22")
    private val chipColor = Color.parseColor("#232935")
    private val textColor = Color.parseColor("#E8EAED")
    private val subColor = Color.parseColor("#9AA0A6")
    private val red = Color.parseColor("#FF5A5F")
    private val amber = Color.parseColor("#FFB020")
    private val green = Color.parseColor("#3DDC84")
    private val accent = Color.parseColor("#7AA2FF")

    private lateinit var content: LinearLayout
    private lateinit var statusBox: LinearLayout
    private lateinit var periodRow: LinearLayout
    private lateinit var filterRow: LinearLayout
    private lateinit var summary: TextView
    private lateinit var listBox: LinearLayout

    private var days = 7
    private var filter = 0
    private var scanning = false
    private var reports: List<AppReport> = emptyList()

    private val prefs by lazy { getSharedPreferences("guard", MODE_PRIVATE) }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(bgColor)
        content = vbox()
        content.setPadding(dp(16), dp(16), dp(16), dp(32))
        scroll.addView(content)
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val b = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(b.left, b.top, b.right, b.bottom)
            } else {
                v.setPadding(
                    insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom
                )
            }
            insets
        }
        setContentView(scroll)

        content.addView(text("Halo Guard", 28f, textColor, bold = true))
        content.addView(
            text(
                "See which apps can read your photos, which ones send data off your phone, " +
                    "and which carry trackers.", 14f, subColor
            ).apply { setPadding(0, dp(4), 0, dp(16)) }
        )

        statusBox = vbox()
        content.addView(statusBox)

        content.addView(sectionLabel("Time window"))
        periodRow = hbox()
        content.addView(periodRow)

        content.addView(sectionLabel("Show"))
        filterRow = hbox()
        val filterScroll = HorizontalScrollView(this)
        filterScroll.isHorizontalScrollBarEnabled = false
        filterScroll.addView(filterRow)
        content.addView(filterScroll)

        summary = text("", 14f, textColor).apply { setPadding(0, dp(18), 0, dp(10)) }
        content.addView(summary)

        listBox = vbox()
        content.addView(listBox)

        content.addView(
            text(
                "How this works: Halo Guard has no internet permission, so nothing it sees ever " +
                    "leaves your phone. Android encrypts each app's traffic, so no app can see " +
                    "WHAT another app uploads — Halo Guard shows how much each app sends, whether " +
                    "it did so while you weren't using it, what it is allowed to read, and which " +
                    "tracking SDKs are built in. Tracker detection is based on the app's declared " +
                    "components, so it can miss some.",
                12f, subColor
            ).apply { setPadding(0, dp(24), 0, 0) }
        )

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        }
        Notifier.ensureChannel(this)
        Watcher.schedule(this)
    }

    override fun onResume() {
        super.onResume()
        renderStatus()
        renderChips()
        scan()
    }

    // ---------------------------------------------------------------- scanning

    private fun trustedSet(): Set<String> =
        HashSet(prefs.getStringSet("trusted", emptySet()) ?: emptySet())

    private fun scan() {
        if (scanning) return
        scanning = true
        summary.text = "Scanning your apps…"
        val d = days
        val trusted = trustedSet()
        val appCtx = applicationContext
        Thread {
            val result = try {
                Scanner(appCtx).scan(d, trusted)
            } catch (e: Exception) {
                emptyList<AppReport>()
            }
            runOnUiThread {
                scanning = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (d != days) {
                    scan()
                } else {
                    reports = result
                    renderList()
                }
            }
        }.start()
    }

    // ---------------------------------------------------------------- rendering

    private fun renderStatus() {
        statusBox.removeAllViews()
        if (!Scanner(this).hasUsageAccess()) {
            val c = card()
            c.addView(text("Step 1: allow Usage access", 16f, amber, bold = true))
            c.addView(
                text(
                    "Needed to see how much data each app uploads and whether it does it in the " +
                        "background. Find Halo Guard in the list and switch it on.",
                    13f, textColor
                ).apply { setPadding(0, dp(6), 0, dp(10)) }
            )
            c.addView(button("Open Usage access settings", accent) { openUsageAccess() })
            statusBox.addView(c)
        } else {
            statusBox.addView(
                text(
                    "● Watching in the background. You'll get an alert if an app that can see " +
                        "your photos uploads a lot while you're not using it.",
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
                    scan()
                }
            })
        }
        filterRow.removeAllViews()
        listOf("All apps", "Photo access", "Background uploads", "Trackers").forEachIndexed { i, name ->
            filterRow.addView(chip(name, i == filter) {
                filter = i
                renderChips()
                renderList()
            })
        }
    }

    private fun renderList() {
        listBox.removeAllViews()
        val photoCount = reports.count { it.photo >= PhotoAccess.FULL }
        val high = reports.count { it.level == "HIGH" && !it.trusted }
        val window = when (days) { 1 -> "24 h"; 7 -> "7 days"; else -> "30 days" }
        summary.text = "${reports.size} apps scanned ($window) · $photoCount can see all your photos · " +
            "$high high risk"

        val shown = reports.filter {
            when (filter) {
                1 -> it.photo >= PhotoAccess.FULL
                2 -> it.upBackground >= 5 * Scanner.MB
                3 -> it.trackers.isNotEmpty()
                else -> true
            }
        }.let { list ->
            if (filter == 2) list.sortedByDescending { it.upBackground } else list
        }

        if (shown.isEmpty()) {
            listBox.addView(text("Nothing to show here.", 14f, subColor))
            return
        }
        shown.take(200).forEach { listBox.addView(appCard(it)) }
    }

    private fun appCard(r: AppReport): View {
        val c = card()

        val top = hbox()
        top.gravity = Gravity.CENTER_VERTICAL
        val icon = ImageView(this)
        try {
            icon.setImageDrawable(packageManager.getApplicationIcon(r.pkg))
        } catch (e: Exception) {
            // no icon
        }
        top.addView(icon, LinearLayout.LayoutParams(dp(40), dp(40)))
        val names = vbox()
        names.setPadding(dp(12), 0, dp(8), 0)
        names.addView(text(r.label, 16f, textColor, bold = true))
        names.addView(text(r.pkg, 11f, subColor))
        top.addView(names, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(badge(r))
        c.addView(top)

        val photoColor = when (r.photo) {
            PhotoAccess.ALL_FILES -> red
            PhotoAccess.FULL -> amber
            else -> subColor
        }
        c.addView(infoLine("Photos:  " + r.photo.label, photoColor))

        if (r.upTotal > 0) {
            val bgHeavy = r.upBackground >= 5 * Scanner.MB && !r.expected
            c.addView(
                infoLine(
                    "Uploaded:  ${fmtBytes(r.upTotal)}  (${fmtBytes(r.upBackground)} while you weren't using it)",
                    if (bgHeavy) red else textColor
                )
            )
        }
        if (r.sensitive.isNotEmpty()) {
            c.addView(infoLine("Also allowed:  " + r.sensitive.joinToString(", "), textColor))
        }
        if (r.trackers.isNotEmpty()) {
            c.addView(infoLine("Trackers inside:  " + r.trackers.joinToString(", "), amber))
        }
        r.reasons.forEach { c.addView(infoLine("•  $it", subColor)) }

        if (r.level == "HIGH" && !r.trusted && !r.expected) {
            c.addView(
                text(
                    "Recommendation: avoid this app, or remove its Photos permission.",
                    13f, red, bold = true
                ).apply { setPadding(0, dp(8), 0, 0) }
            )
        }

        val actions = hbox()
        actions.setPadding(0, dp(12), 0, 0)
        actions.addView(button("Permissions", accent) {
            safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${r.pkg}")))
        })
        actions.addView(space())
        actions.addView(button(if (r.trusted) "Trusted ✓" else "I trust it", if (r.trusted) green else subColor) {
            toggleTrust(r.pkg)
        })
        actions.addView(space())
        actions.addView(button("Uninstall", red) {
            safeStart(Intent(Intent.ACTION_DELETE, Uri.parse("package:${r.pkg}")))
        })
        c.addView(actions)
        return c
    }

    private fun toggleTrust(pkg: String) {
        val set = trustedSet().toMutableSet()
        if (!set.add(pkg)) set.remove(pkg)
        prefs.edit().putStringSet("trusted", set).apply()
        reports = reports.map { if (it.pkg == pkg) it.copy(trusted = pkg in set) else it }
        renderList()
    }

    private fun openUsageAccess() {
        try {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            safeStart(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
    }

    private fun safeStart(i: Intent) {
        try {
            startActivity(i)
        } catch (e: Exception) {
            // nothing to open
        }
    }

    // ---------------------------------------------------------------- view helpers

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun vbox(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun hbox(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

    private fun space(): View = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(8), 1) }

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun text(s: String, sizeSp: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = s
            textSize = sizeSp
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun sectionLabel(s: String): TextView =
        text(s.uppercase(), 11f, subColor, bold = true).apply { setPadding(0, dp(14), 0, dp(6)) }

    private fun infoLine(s: String, color: Int): TextView =
        text(s, 13f, color).apply { setPadding(0, dp(6), 0, 0) }

    private fun card(): LinearLayout = vbox().apply {
        background = rounded(cardColor, 16)
        setPadding(dp(14), dp(14), dp(14), dp(14))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) }
    }

    private fun chip(label: String, selected: Boolean, onClick: () -> Unit): TextView =
        text(label, 13f, if (selected) bgColor else textColor, bold = selected).apply {
            background = rounded(if (selected) accent else chipColor, 18)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(8) }
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun button(label: String, color: Int, onClick: () -> Unit): TextView =
        text(label, 13f, color, bold = true).apply {
            background = rounded(chipColor, 10)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun badge(r: AppReport): TextView {
        val (label, color) = when {
            r.trusted -> "TRUSTED" to green
            r.level == "HIGH" -> "HIGH" to red
            r.level == "MEDIUM" -> "MEDIUM" to amber
            else -> "LOW" to green
        }
        return text(label, 11f, bgColor, bold = true).apply {
            background = rounded(color, 8)
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
    }
}
