package com.haloapps.guard

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TreeMap

object Trail {
    class Burst(val start: Long, val end: Long, val tx: Long)

    /** Time windows (Android stores ~2-hour buckets) in which [uid] sent at least [minBytes]. */
    @Suppress("DEPRECATION")
    fun bursts(ctx: Context, uid: Int, start: Long, end: Long, minBytes: Long): List<Burst> {
        val nsm = ctx.getSystemService(NetworkStatsManager::class.java) ?: return emptyList()
        val map = TreeMap<Long, LongArray>()
        for (type in intArrayOf(ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_MOBILE)) {
            try {
                val s = nsm.queryDetailsForUid(type, null, start, end, uid)
                val b = NetworkStats.Bucket()
                while (s.hasNextBucket()) {
                    s.getNextBucket(b)
                    val e = map.getOrPut(b.startTimeStamp) { longArrayOf(b.endTimeStamp, 0L) }
                    e[1] += b.txBytes
                }
                s.close()
            } catch (e: Exception) {
            }
        }
        return map.entries.filter { it.value[1] >= minBytes }
            .map { Burst(it.key, it.value[0], it.value[1]) }
            .sortedByDescending { it.start }
    }

    class CompanyHit(val company: String, val count: Int, val domains: List<String>, val uploadish: Boolean, val blocked: Int)

    fun byCompany(domains: List<Db.DomainCount>): List<CompanyHit> {
        val groups = LinkedHashMap<String, MutableList<Db.DomainCount>>()
        for (d in domains) groups.getOrPut(Owners.ownerLabel(d.domain)) { ArrayList() }.add(d)
        return groups.map { (company, list) ->
            CompanyHit(
                company,
                list.sumOf { it.count },
                list.sortedByDescending { it.count }.map { it.domain },
                list.any { Owners.looksLikeUpload(it.domain) },
                list.sumOf { it.blocked }
            )
        }.sortedByDescending { it.count }
    }
}

class TrailActivity : BaseActivity() {

    private lateinit var statusBox: LinearLayout
    private lateinit var periodRow: LinearLayout
    private lateinit var scopeRow: LinearLayout
    private lateinit var summary: TextView
    private lateinit var listBox: LinearLayout
    private var days = 1
    private var allApps = false
    private var focusPkg: String? = null
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        focusPkg = intent.getStringExtra("pkg")
        val content = setupScreen(
            "Photo trail",
            "Where your apps send data, and which companies end up with it.",
            2
        )
        statusBox = vbox()
        content.addView(statusBox)

        content.addView(sectionLabel("Time window"))
        val (ps, pr) = chipRow()
        periodRow = pr
        content.addView(ps)

        content.addView(sectionLabel("Show"))
        val (ss, sr) = chipRow()
        scopeRow = sr
        content.addView(ss)

        summary = text("", 14f, textColor).apply { setPadding(0, dp(16), 0, dp(10)) }
        content.addView(summary)
        listBox = vbox()
        content.addView(listBox)

        content.addView(
            text(
                "How this works: when tracking is on, Halo Guard runs a private on-phone VPN that only " +
                    "looks at server-name lookups (DNS). Each time an app asks \"where is " +
                    "upload.facebook.com?\" Halo Guard records which app asked, then passes the question " +
                    "on unchanged. Your actual photos and traffic never pass through Halo Guard. Android " +
                    "encrypts app traffic, so this shows which company and server received an app's " +
                    "uploads, not the photos themselves. Upload amounts come from Android's own data " +
                    "counters, grouped in roughly 2-hour blocks.",
                12f, subColor
            ).apply { setPadding(0, dp(24), 0, 0) }
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        focusPkg = intent.getStringExtra("pkg")
    }

    override fun onResume() {
        super.onResume()
        renderStatus()
        renderChips()
        load()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 10 && resultCode == RESULT_OK) {
            GuardVpnService.start(this)
            statusBox.postDelayed({ renderStatus() }, 800)
        }
    }

    private fun startTracking() {
        val prep = VpnService.prepare(this)
        if (prep != null) {
            @Suppress("DEPRECATION")
            startActivityForResult(prep, 10)
        } else {
            GuardVpnService.start(this)
            statusBox.postDelayed({ renderStatus() }, 800)
        }
    }

    private fun renderStatus() {
        statusBox.removeAllViews()
        val c = card()
        val on = GuardVpnService.running
        if (on) {
            c.addView(text("● Tracking is on", 16f, green, bold = true))
            c.addView(
                text(
                    "Recording which servers each app talks to. Leave this on to build up the trail.",
                    13f, textColor
                ).apply { setPadding(0, dp(6), 0, dp(10)) }
            )
        } else {
            c.addView(text("Tracking is off", 16f, amber, bold = true))
            c.addView(
                text(
                    "Turn on to record where every app sends data. Only one VPN can run at a time, " +
                        "so this pauses Hush. Turn on \"Block trackers\" below to keep ad/tracker blocking.",
                    13f, textColor
                ).apply { setPadding(0, dp(6), 0, dp(10)) }
            )
        }
        val row = hbox()
        row.addView(
            if (on) button("Stop tracking", red) {
                GuardVpnService.stop(this)
                statusBox.postDelayed({ renderStatus() }, 500)
            } else button("Start tracking", accent) { startTracking() }
        )
        row.addView(space())
        val blocking = prefs.getBoolean("block_trackers", false)
        row.addView(button(if (blocking) "Block trackers: ON" else "Block trackers: OFF", if (blocking) green else subColor) {
            prefs.edit().putBoolean("block_trackers", !blocking).apply()
            renderStatus()
        })
        c.addView(row)
        c.addView(
            text(
                "Tip: in Settings → Connections → More connection settings → Private DNS, choose " +
                    "Off or Automatic. A custom Private DNS provider bypasses tracking. For it to start " +
                    "after a restart: Settings → Connections → More connection settings → VPN → " +
                    "Halo Guard ⚙ → Always-on VPN.",
                11f, subColor
            ).apply { setPadding(0, dp(10), 0, 0) }
        )
        statusBox.addView(c)
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
        scopeRow.removeAllViews()
        val fp = focusPkg
        if (fp != null) {
            scopeRow.addView(chip(appLabel(fp), true) {})
            scopeRow.addView(chip("Show all", false) {
                focusPkg = null
                renderChips()
                load()
            })
        } else {
            scopeRow.addView(chip("Apps with photo access", !allApps) {
                allApps = false
                renderChips()
                load()
            })
            scopeRow.addView(chip("All apps", allApps) {
                allApps = true
                renderChips()
                load()
            })
        }
    }

    private class AppTrail(
        val pkg: String,
        val label: String,
        val photo: PhotoAccess,
        val sent: Long,
        val sentBg: Long,
        val companies: List<Trail.CompanyHit>,
        val bursts: List<Pair<Trail.Burst, List<String>>>
    )

    private class Result(val apps: List<AppTrail>, val overall: List<Pair<String, Set<String>>>, val hasDns: Boolean)

    private fun load() {
        if (loading) return
        loading = true
        summary.text = "Building the trail…"
        val d = days
        val all = allApps
        val focus = focusPkg
        val ctx = applicationContext
        Thread {
            val r = try {
                compute(ctx, d, all, focus)
            } catch (e: Exception) {
                Result(emptyList(), emptyList(), false)
            }
            runOnUiThread {
                loading = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (d != days || all != allApps || focus != focusPkg) load() else render(r)
            }
        }.start()
    }

    private fun compute(ctx: Context, days: Int, all: Boolean, focus: String?): Result {
        val end = System.currentTimeMillis()
        val start = end - days * 86_400_000L
        val scanner = Scanner(ctx)
        val db = Db.get(ctx)
        val usage = if (scanner.hasUsageAccess()) scanner.uploads(start, end) else emptyMap()
        val apps = ArrayList<AppTrail>()
        val labelsByUid = HashMap<Int, String>()

        for (ai in scanner.userApps()) {
            labelsByUid[ai.uid] = scanner.label(ai)
            if (focus != null && ai.packageName != focus) continue
            val perms = scanner.permissions(ai)
            if (focus == null && !all && perms.photo < PhotoAccess.FULL) continue
            val u = usage[ai.uid]
            val domains = db.domains(ai.uid, start, end)
            if (focus == null && (u == null || u.total < 256 * 1024) && domains.isEmpty()) continue

            val companies = Trail.byCompany(domains)
            val bursts = Trail.bursts(ctx, ai.uid, start, end, 1 * Scanner.MB).take(8).map { b ->
                val inWindow = db.domains(ai.uid, b.start, b.end, 50)
                b to Trail.byCompany(inWindow).take(3).map { it.company }
            }
            apps.add(
                AppTrail(
                    ai.packageName, scanner.label(ai), perms.photo,
                    u?.total ?: 0L, u?.background ?: 0L, companies, bursts
                )
            )
        }
        apps.sortByDescending { it.sent }

        // Which companies hear from your phone at all, and from which apps
        val overallMap = HashMap<String, MutableSet<String>>()
        val overallCount = HashMap<String, Int>()
        for (row in db.allUidDomains(start, end)) {
            val company = Owners.owner(row.domain)?.toString() ?: continue
            overallMap.getOrPut(company) { HashSet() }.add(labelsByUid[row.uid] ?: "System / other")
            overallCount[company] = (overallCount[company] ?: 0) + row.count
        }
        val overall = overallMap.entries.sortedByDescending { overallCount[it.key] ?: 0 }
            .map { it.key to (it.value as Set<String>) }
        return Result(apps, overall, db.hasAnyDns())
    }

    private val timeFmt = SimpleDateFormat("EEE MMM d, h a", Locale.getDefault())
    private val hourFmt = SimpleDateFormat("h a", Locale.getDefault())

    private fun render(r: Result) {
        listBox.removeAllViews()
        summary.text = if (r.apps.isEmpty()) "No uploads found in this window."
        else "${r.apps.size} app${if (r.apps.size == 1) "" else "s"} sent data · biggest senders first"

        if (!r.hasDns) {
            listBox.addView(
                text(
                    "No destinations recorded yet. Start tracking above, then use your phone normally — " +
                        "the trail fills in as apps go online.",
                    13f, amber
                ).apply { setPadding(0, 0, 0, dp(10)) }
            )
        }

        for (a in r.apps.take(60)) listBox.addView(appCard(a))

        if (focusPkg == null && r.overall.isNotEmpty()) {
            listBox.addView(sectionLabel("Companies receiving data from your phone"))
            val c = card()
            for ((company, apps) in r.overall.take(30)) {
                val names = apps.sorted().take(6).joinToString(", ") + if (apps.size > 6) " +${apps.size - 6}" else ""
                c.addView(infoLine("$company  ←  $names", textColor))
            }
            listBox.addView(c)
        }
    }

    private fun appCard(a: AppTrail): LinearLayout {
        val c = card()
        val top = hbox()
        top.addView(text(a.label, 16f, textColor, bold = true))
        c.addView(top)
        if (a.photo >= PhotoAccess.FULL) c.addView(infoLine(a.photo.label, amber))
        c.addView(
            infoLine(
                "Sent ${fmtBytes(a.sent)}  (${fmtBytes(a.sentBg)} while you weren't using it)",
                if (a.sentBg >= 5 * Scanner.MB) red else textColor
            )
        )

        if (a.companies.isNotEmpty()) {
            c.addView(text("Ended up with:", 13f, subColor, bold = true).apply { setPadding(0, dp(10), 0, 0) })
            for (h in a.companies.take(6)) {
                val doms = h.domains.take(3).joinToString(", ")
                val tag = when {
                    h.blocked > 0 -> "  [blocked ${h.blocked}×]"
                    h.uploadish -> "  ⬆ upload server"
                    else -> ""
                }
                val color = when {
                    h.blocked > 0 -> green
                    h.uploadish -> red
                    else -> textColor
                }
                c.addView(infoLine("• ${h.company}$tag\n   $doms", color))
            }
        } else {
            c.addView(infoLine("No destinations recorded for this app yet.", subColor))
        }

        if (a.bursts.isNotEmpty()) {
            c.addView(text("Upload bursts:", 13f, subColor, bold = true).apply { setPadding(0, dp(10), 0, 0) })
            for ((b, companies) in a.bursts) {
                val whenTxt = timeFmt.format(Date(b.start)) + "–" + hourFmt.format(Date(b.end))
                val dest = if (companies.isEmpty()) "destination not recorded" else companies.joinToString(", ")
                c.addView(infoLine("${fmtBytes(b.tx)}  ·  $whenTxt  →  $dest", textColor))
            }
        }

        val row = hbox()
        row.setPadding(0, dp(12), 0, 0)
        row.addView(button("Permissions", accent) {
            safeStart(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${a.pkg}")))
        })
        if (a.photo >= PhotoAccess.FULL) {
            row.addView(space())
            val locked = PhotoLock.isLocked(this, a.pkg)
            row.addView(button(if (locked) "Photo lock ✓" else "Lock photos", if (locked) green else amber) {
                PhotoLock.setLocked(this, a.pkg, !locked)
                load()
            })
        }
        c.addView(row)
        return c
    }
}
