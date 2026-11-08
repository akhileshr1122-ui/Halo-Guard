package com.haloapps.guard

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Local-only log. Nothing here ever leaves the phone. */
class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "guard.db", null, 2) {

    companion object {
        @Volatile private var inst: Db? = null
        fun get(ctx: Context): Db = inst ?: synchronized(this) {
            inst ?: Db(ctx.applicationContext).also { inst = it }
        }
        private const val KEEP_MS = 30L * 86_400_000L
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE dns (ts INTEGER NOT NULL, uid INTEGER NOT NULL, domain TEXT NOT NULL, blocked INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX dns_uid_ts ON dns(uid, ts)")
        db.execSQL("CREATE INDEX dns_ts ON dns(ts)")
        db.execSQL("CREATE TABLE events (ts INTEGER NOT NULL, pkg TEXT, msg TEXT NOT NULL)")
        createUploads(db)
    }

    private fun createUploads(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS uploads (start INTEGER NOT NULL, finish INTEGER NOT NULL, " +
                "uid INTEGER NOT NULL, pkg TEXT NOT NULL, bytes INTEGER NOT NULL, mode TEXT NOT NULL, " +
                "dests TEXT NOT NULL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS uploads_start ON uploads(start)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createUploads(db)
    }

    /** One detected upload. mode = "manual" (app was on screen) or "auto" (app was closed). */
    class Upload(
        val start: Long, val finish: Long, val uid: Int, val pkg: String,
        val bytes: Long, val mode: String, val dests: String
    )

    fun insertUpload(u: Upload) {
        val cv = ContentValues()
        cv.put("start", u.start)
        cv.put("finish", u.finish)
        cv.put("uid", u.uid)
        cv.put("pkg", u.pkg)
        cv.put("bytes", u.bytes)
        cv.put("mode", u.mode)
        cv.put("dests", u.dests)
        writableDatabase.insert("uploads", null, cv)
    }

    fun uploads(since: Long, limit: Int = 300): List<Upload> {
        val out = ArrayList<Upload>()
        readableDatabase.rawQuery(
            "SELECT start, finish, uid, pkg, bytes, mode, dests FROM uploads WHERE start >= ? " +
                "ORDER BY start DESC LIMIT $limit", arrayOf(since.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(Upload(c.getLong(0), c.getLong(1), c.getInt(2), c.getString(3), c.getLong(4), c.getString(5), c.getString(6)))
            }
        }
        return out
    }

    class DnsRow(val ts: Long, val uid: Int, val domain: String, val blocked: Boolean)

    fun insertDns(rows: List<DnsRow>) {
        if (rows.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (r in rows) {
                val cv = ContentValues()
                cv.put("ts", r.ts)
                cv.put("uid", r.uid)
                cv.put("domain", r.domain)
                cv.put("blocked", if (r.blocked) 1 else 0)
                db.insert("dns", null, cv)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    class DomainCount(val domain: String, val count: Int, val blocked: Int, val lastTs: Long)

    /** Domains contacted by [uid] (or every app if uid == null) between start and end, most frequent first. */
    fun domains(uid: Int?, start: Long, end: Long, limit: Int = 200): List<DomainCount> {
        val out = ArrayList<DomainCount>()
        val where = if (uid == null) "ts BETWEEN ? AND ?" else "uid = ? AND ts BETWEEN ? AND ?"
        val args = if (uid == null) arrayOf(start.toString(), end.toString())
        else arrayOf(uid.toString(), start.toString(), end.toString())
        readableDatabase.rawQuery(
            "SELECT domain, COUNT(*), SUM(blocked), MAX(ts) FROM dns WHERE $where " +
                "GROUP BY domain ORDER BY COUNT(*) DESC LIMIT $limit", args
        ).use { c ->
            while (c.moveToNext()) out.add(DomainCount(c.getString(0), c.getInt(1), c.getInt(2), c.getLong(3)))
        }
        return out
    }

    class UidDomain(val uid: Int, val domain: String, val count: Int)

    fun allUidDomains(start: Long, end: Long): List<UidDomain> {
        val out = ArrayList<UidDomain>()
        readableDatabase.rawQuery(
            "SELECT uid, domain, COUNT(*) FROM dns WHERE ts BETWEEN ? AND ? GROUP BY uid, domain",
            arrayOf(start.toString(), end.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(UidDomain(c.getInt(0), c.getString(1), c.getInt(2)))
        }
        return out
    }

    fun hasAnyDns(): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM dns LIMIT 1", null).use { it.moveToFirst() }

    fun logEvent(pkg: String?, msg: String) {
        val cv = ContentValues()
        cv.put("ts", System.currentTimeMillis())
        cv.put("pkg", pkg)
        cv.put("msg", msg)
        writableDatabase.insert("events", null, cv)
    }

    class Event(val ts: Long, val pkg: String?, val msg: String)

    fun events(limit: Int = 60): List<Event> {
        val out = ArrayList<Event>()
        readableDatabase.rawQuery("SELECT ts, pkg, msg FROM events ORDER BY ts DESC LIMIT $limit", null).use { c ->
            while (c.moveToNext()) out.add(Event(c.getLong(0), if (c.isNull(1)) null else c.getString(1), c.getString(2)))
        }
        return out
    }

    fun prune() {
        val cutoff = (System.currentTimeMillis() - KEEP_MS).toString()
        writableDatabase.delete("dns", "ts < ?", arrayOf(cutoff))
        writableDatabase.delete("events", "ts < ?", arrayOf(cutoff))
        writableDatabase.delete("uploads", "start < ?", arrayOf(cutoff))
    }
}
