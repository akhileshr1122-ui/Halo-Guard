package com.haloapps.guard

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Arrays

/**
 * Known scam, phishing and malware sites (HaGeZi "Threat Intelligence Feeds", mini version,
 * ~190k domains). A starter copy ships inside the app; a fresh copy is downloaded once a day.
 *
 * Domains are stored as sorted 64-bit hashes (~1.5 MB) instead of strings (~25 MB).
 * A listed domain also blocks all of its subdomains.
 */
object ThreatList {
    private const val URL_LIST =
        "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/tif.mini-onlydomains.txt"
    private const val FILE = "threats.txt"
    private const val ASSET = "threats.txt"
    private const val MAX_AGE_MS = 24L * 3600_000L

    @Volatile private var hashes = LongArray(0)
    @Volatile var count = 0
        private set
    /** When the list in use was downloaded (0 = the copy bundled with the app). */
    @Volatile var updatedAt = 0L
        private set
    @Volatile private var loading = false

    fun isThreat(domain: String): Boolean {
        val h = hashes
        if (h.isEmpty()) return false
        var d = domain.lowercase().trimEnd('.')
        while (true) {
            if (Arrays.binarySearch(h, hash(d)) >= 0) return true
            val dot = d.indexOf('.')
            if (dot < 0) return false
            d = d.substring(dot + 1)
            if (!d.contains('.')) return false // stop at "example.com", never block a whole TLD
        }
    }

    /** Loads the saved list (or the bundled one), then refreshes it in the background if old. */
    fun ensureLoaded(ctx: Context) {
        if (loading) return
        loading = true
        Thread({
            try {
                val app = ctx.applicationContext
                if (hashes.isEmpty()) loadFromDisk(app)
                val f = File(app.filesDir, FILE)
                if (!f.exists() || System.currentTimeMillis() - f.lastModified() > MAX_AGE_MS) download(app)
            } catch (_: Exception) {
            } finally {
                loading = false
            }
        }, "guard-threats").start()
    }

    private fun loadFromDisk(ctx: Context) {
        val f = File(ctx.filesDir, FILE)
        if (f.exists()) {
            parse(f.bufferedReader().readLines())?.let { set(it, f.lastModified()); return }
        }
        try {
            ctx.assets.open(ASSET).bufferedReader().use { r -> parse(r.readLines())?.let { set(it, 0L) } }
        } catch (_: Exception) {
            // no bundled copy in this build
        }
    }

    private fun download(ctx: Context) {
        val conn = URL(URL_LIST).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        try {
            if (conn.responseCode != 200) return
            val lines = conn.inputStream.bufferedReader().readLines()
            val parsed = parse(lines) ?: return
            if (parsed.size < 10_000) return // something is wrong with this download; keep the old one
            val tmp = File(ctx.filesDir, "$FILE.tmp")
            tmp.writeText(lines.joinToString("\n"))
            val f = File(ctx.filesDir, FILE)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            set(parsed, f.lastModified())
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(lines: List<String>): LongArray? {
        val out = LongArray(lines.size)
        var n = 0
        for (raw in lines) {
            val l = raw.trim()
            if (l.isEmpty() || l[0] == '#' || l[0] == '!') continue
            out[n++] = hash(l.lowercase().removePrefix("*.").trimEnd('.'))
        }
        if (n == 0) return null
        val a = out.copyOf(n)
        a.sort()
        return a
    }

    private fun set(a: LongArray, at: Long) {
        hashes = a
        count = a.size
        updatedAt = at
    }

    /** 64-bit FNV-1a. */
    private fun hash(s: String): Long {
        var h = -0x340d631b7bdddcdbL
        for (c in s) {
            h = h xor c.code.toLong()
            h *= 0x100000001b3L
        }
        return h
    }
}
