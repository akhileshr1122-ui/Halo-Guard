package com.haloapps.guard

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.HttpURLConnection
import java.net.URL
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * "Photo trail" tracker. A local VPN that ONLY captures DNS lookups (all other traffic goes
 * straight to the internet as normal). Every time an app looks up a server name we record
 * which app asked and for which server, then forward the lookup to a real DNS server.
 * Optionally answers "does not exist" for known tracker domains (blocking them), and by
 * default for known scam / phishing / malware sites (see ThreatList).
 *
 * Lookups are forwarded encrypted (DNS-over-HTTPS to Cloudflare, then Google), so the Wi-Fi
 * network and internet provider can't read which sites the phone looks up. Plain DNS is only
 * used if both encrypted servers fail (e.g. a hotel Wi-Fi login page).
 */
class GuardVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.haloapps.guard.START"
        const val ACTION_STOP = "com.haloapps.guard.STOP"
        private const val VPN_ADDR = "10.111.0.2"
        private const val DNS_ADDR = "10.111.0.1"
        private val UPSTREAM = listOf("1.1.1.1", "8.8.8.8")
        private val DOH = listOf("https://1.1.1.1/dns-query", "https://dns.google/dns-query")

        /** Last time a lookup went out encrypted / unencrypted (ms), for the status card. */
        @Volatile var lastEncryptedAt = 0L
            private set
        @Volatile var lastPlainAt = 0L
            private set
        @Volatile var lastDohServer = ""
            private set
        /** Dangerous-site lookups blocked since the VPN started. */
        @Volatile var threatsBlocked = 0
            private set

        @Volatile var running = false
            private set

        fun start(ctx: Context) {
            ctx.startService(Intent(ctx, GuardVpnService::class.java).setAction(ACTION_START))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, GuardVpnService::class.java).setAction(ACTION_STOP))
        }
    }

    private var tun: ParcelFileDescriptor? = null
    private var reader: Thread? = null
    private var writer: Thread? = null
    private var monitor: Thread? = null
    @Volatile private var active = false
    private val pool = Executors.newFixedThreadPool(8)
    private val pending = LinkedBlockingQueue<Db.DnsRow>()
    private val recent = ConcurrentHashMap<String, Long>()
    private var out: FileOutputStream? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            prefs().edit().putBoolean("vpn_on", false).apply()
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }
        // ACTION_START, or started by the system as an "Always-on VPN" (null/other action)
        prefs().edit().putBoolean("vpn_on", true).apply()
        if (!active) startTunnel()
        return START_STICKY
    }

    private fun prefs() = getSharedPreferences("guard", Context.MODE_PRIVATE)

    private fun startTunnel() {
        val builder = Builder()
            .setSession("Halo Guard – Photo trail")
            .addAddress(VPN_ADDR, 32)
            .addDnsServer(DNS_ADDR)
            .addRoute(DNS_ADDR, 32)
            .setBlocking(true)
        try {
            builder.addDisallowedApplication(packageName)
        } catch (e: Exception) {
        }
        if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)
        val fd = try {
            builder.establish()
        } catch (e: Exception) {
            null
        }
        if (fd == null) {
            stopSelf()
            return
        }
        tun = fd
        active = true
        running = true
        out = FileOutputStream(fd.fileDescriptor)
        Db.get(this).prune()
        ThreatList.ensureLoaded(this)
        threatsBlocked = 0

        reader = Thread({ readLoop(fd) }, "guard-vpn-read").also { it.start() }
        writer = Thread({ flushLoop() }, "guard-vpn-db").also { it.start() }
        monitor = Thread({ monitorLoop() }, "guard-uploads").also { it.start() }
    }

    private fun readLoop(fd: ParcelFileDescriptor) {
        val input = FileInputStream(fd.fileDescriptor)
        val buf = ByteArray(32767)
        while (active) {
            val n = try {
                input.read(buf)
            } catch (e: Exception) {
                -1
            }
            if (n <= 0) {
                if (!active) break
                try { Thread.sleep(20) } catch (e: InterruptedException) { break }
                continue
            }
            val packet = buf.copyOf(n)
            try {
                handlePacket(packet)
            } catch (e: Exception) {
                // malformed packet — ignore
            }
        }
    }

    private fun handlePacket(p: ByteArray) {
        val version = (p[0].toInt() shr 4) and 0xF
        if (version != 4) return
        val ihl = (p[0].toInt() and 0xF) * 4
        if (p[9].toInt() != 17) return // UDP only
        if (p.size < ihl + 8 + 12) return
        val srcIp = p.copyOfRange(12, 16)
        val dstIp = p.copyOfRange(16, 20)
        val srcPort = u16(p, ihl)
        val dstPort = u16(p, ihl + 2)
        if (dstPort != 53) return
        val dns = p.copyOfRange(ihl + 8, p.size)
        val domain = Dns.queryName(dns) ?: return

        val uid = ownerUid(srcIp, srcPort, dstIp, dstPort)
        val threat = prefs().getBoolean("block_threats", true) && ThreatList.isThreat(domain)
        val block = threat || prefs().getBoolean("block_trackers", false) && Owners.isTracker(domain)
        record(uid, domain, block)
        if (threat) warnThreat(uid, domain)

        if (block) {
            writeBack(srcIp, srcPort, dstIp, dstPort, Dns.nxdomain(dns))
            return
        }
        pool.execute {
            val answer = forward(dns)
            if (answer != null) writeBack(srcIp, srcPort, dstIp, dstPort, answer)
        }
    }

    private fun ownerUid(srcIp: ByteArray, srcPort: Int, dstIp: ByteArray, dstPort: Int): Int = try {
        val cm = getSystemService(ConnectivityManager::class.java)
        cm.getConnectionOwnerUid(
            OsConstants.IPPROTO_UDP,
            InetSocketAddress(InetAddress.getByAddress(srcIp), srcPort),
            InetSocketAddress(InetAddress.getByAddress(dstIp), dstPort)
        )
    } catch (e: Exception) {
        -1
    }

    private fun record(uid: Int, domain: String, blocked: Boolean) {
        val key = "$uid|$domain"
        val now = System.currentTimeMillis()
        val last = recent[key]
        if (last != null && now - last < 60_000) return // de-duplicate bursts
        recent[key] = now
        if (recent.size > 5000) recent.clear()
        pending.offer(Db.DnsRow(now, uid, domain.lowercase(), blocked))
    }

    private fun flushLoop() {
        val db = Db.get(this)
        while (active || pending.isNotEmpty()) {
            val batch = ArrayList<Db.DnsRow>()
            val first = try {
                pending.poll(5, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                null
            }
            if (first != null) batch.add(first)
            pending.drainTo(batch)
            try {
                db.insertDns(batch)
            } catch (e: Exception) {
            }
            if (!active && first == null) break
        }
    }

    /** Checks every minute for apps that just uploaded something (see UploadMonitor). */
    private fun monitorLoop() {
        val m = UploadMonitor(applicationContext)
        while (active) {
            try {
                m.poll()
            } catch (e: Exception) {
            }
            try {
                Thread.sleep(60_000)
            } catch (e: InterruptedException) {
                break
            }
        }
    }

    private val warned = ConcurrentHashMap<String, Long>()

    /** One alert per site per hour: "Blocked a dangerous site that <app> tried to open". */
    private fun warnThreat(uid: Int, domain: String) {
        threatsBlocked++
        if (domain.startsWith("_")) return
        val root = Owners.rootDomain(domain)
        val now = System.currentTimeMillis()
        val last = warned[root]
        if (last != null && now - last < 3600_000L) return
        warned[root] = now
        val pkg = try { packageManager.getPackagesForUid(uid)?.firstOrNull() } catch (e: Exception) { null } ?: "android"
        val label = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) { "An app" }
        Notifier.alert(
            this, pkg, label,
            "Blocked a dangerous site",
            "$label tried to open $root, which is on a scam/phishing/malware list. Halo Guard stopped it. " +
                "If you just tapped a link in a message or email, don't enter any passwords or card details there.",
        )
    }

    private fun forward(query: ByteArray): ByteArray? {
        if (prefs().getBoolean("encrypted_dns", true)) {
            for (url in DOH) {
                val r = forwardHttps(url, query)
                if (r != null) {
                    lastEncryptedAt = System.currentTimeMillis()
                    lastDohServer = if (url.contains("1.1.1.1")) "Cloudflare" else "Google"
                    return r
                }
            }
        }
        val plain = forwardPlain(query)
        if (plain != null) lastPlainAt = System.currentTimeMillis()
        return plain
    }

    /** DNS-over-HTTPS (RFC 8484). Halo Guard itself is outside the VPN, so this can't loop. */
    private fun forwardHttps(url: String, query: ByteArray): ByteArray? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 3500
            conn.readTimeout = 3500
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/dns-message")
            conn.setRequestProperty("Accept", "application/dns-message")
            conn.setFixedLengthStreamingMode(query.size)
            conn.outputStream.use { it.write(query) }
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.use { it.readBytes() }
            if (body.size < 12) null else body
        } catch (e: Exception) {
            null
        }
        // Not disconnecting lets Android reuse the encrypted connection for the next lookup.
    }

    private fun forwardPlain(query: ByteArray): ByteArray? {
        for (server in UPSTREAM) {
            var sock: DatagramSocket? = null
            try {
                sock = DatagramSocket()
                protect(sock)
                sock.soTimeout = 4000
                val addr = InetAddress.getByName(server)
                sock.send(DatagramPacket(query, query.size, addr, 53))
                val resp = ByteArray(4096)
                val pkt = DatagramPacket(resp, resp.size)
                sock.receive(pkt)
                return resp.copyOf(pkt.length)
            } catch (e: Exception) {
                // try next server
            } finally {
                sock?.close()
            }
        }
        return null
    }

    /** Wraps a DNS answer in IPv4 + UDP headers (reply goes from our fake DNS IP back to the app). */
    private fun writeBack(appIp: ByteArray, appPort: Int, dnsIp: ByteArray, dnsPort: Int, payload: ByteArray) {
        val total = 20 + 8 + payload.size
        val pkt = ByteArray(total)
        pkt[0] = 0x45
        pkt[2] = (total shr 8).toByte()
        pkt[3] = total.toByte()
        pkt[6] = 0x40 // don't fragment
        pkt[8] = 64 // TTL
        pkt[9] = 17 // UDP
        System.arraycopy(dnsIp, 0, pkt, 12, 4)
        System.arraycopy(appIp, 0, pkt, 16, 4)
        val csum = ipChecksum(pkt, 0, 20)
        pkt[10] = (csum shr 8).toByte()
        pkt[11] = csum.toByte()
        // UDP header (checksum 0 = not used, allowed for IPv4)
        pkt[20] = (dnsPort shr 8).toByte()
        pkt[21] = dnsPort.toByte()
        pkt[22] = (appPort shr 8).toByte()
        pkt[23] = appPort.toByte()
        val udpLen = 8 + payload.size
        pkt[24] = (udpLen shr 8).toByte()
        pkt[25] = udpLen.toByte()
        System.arraycopy(payload, 0, pkt, 28, payload.size)
        val o = out ?: return
        synchronized(o) {
            try {
                o.write(pkt)
            } catch (e: Exception) {
            }
        }
    }

    private fun ipChecksum(b: ByteArray, off: Int, len: Int): Int {
        var sum = 0L
        var i = off
        while (i < off + len) {
            sum += ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
            i += 2
        }
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }

    private fun u16(b: ByteArray, off: Int): Int = ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    private fun shutdown() {
        active = false
        running = false
        try {
            tun?.close()
        } catch (e: Exception) {
        }
        tun = null
        reader?.interrupt()
        monitor?.interrupt()
    }

    override fun onRevoke() {
        // Another VPN (e.g. Hush) took over, or the user turned us off in Settings.
        prefs().edit().putBoolean("vpn_on", false).apply()
        shutdown()
        stopSelf()
    }

    override fun onDestroy() {
        shutdown()
        pool.shutdownNow()
        super.onDestroy()
    }
}

/** Minimal DNS message helpers. */
object Dns {
    fun queryName(m: ByteArray): String? {
        if (m.size < 13) return null
        val qd = ((m[4].toInt() and 0xFF) shl 8) or (m[5].toInt() and 0xFF)
        if (qd < 1) return null
        val sb = StringBuilder()
        var i = 12
        var guard = 0
        while (i < m.size && guard++ < 64) {
            val len = m[i].toInt() and 0xFF
            if (len == 0) break
            if (len and 0xC0 != 0) return null
            if (i + 1 + len > m.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            for (k in 0 until len) sb.append((m[i + 1 + k].toInt() and 0xFF).toChar())
            i += 1 + len
        }
        return if (sb.isEmpty()) null else sb.toString()
    }

    /** Turns a query into a "name does not exist" answer. */
    fun nxdomain(q: ByteArray): ByteArray {
        val r = q.copyOf()
        r[2] = (0x80 or (q[2].toInt() and 0x01)).toByte() // QR=1, keep RD
        r[3] = 0x83.toByte() // RA=1, RCODE=3 (NXDOMAIN)
        // no answers / authority; keep question (+ any EDNS record)
        r[6] = 0; r[7] = 0; r[8] = 0; r[9] = 0
        return r
    }
}
