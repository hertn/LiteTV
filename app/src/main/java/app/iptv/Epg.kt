@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.iptv

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.os.SystemClock
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.media3.ui.AspectRatioFrameLayout
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Bundle
import android.util.Xml
import android.view.*
import android.widget.*
import androidx.media3.common.*
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.zip.GZIPInputStream
import org.json.JSONArray
import org.json.JSONObject

// the TV guide (EPG): downloaded file -> database on disk -> only the channels on screen are read back
fun MainActivity.epgMaxAge(): Long = if (epgHours <= 0) Long.MAX_VALUE else epgHours * 3600_000L

// the guide files in view: the active playlist's, or every playlist's while favorites / search results are shown
fun MainActivity.scopeNow(): List<String> =
    if (multi()) sources.toList().flatMap { epgUrls(it) }.distinct().map { hx(it) }
    else (sources.getOrNull(act)?.let { s -> epgUrls(s).map { hx(it) } } ?: emptyList())

// the guide data or the scope changed: forget what was read, redraw
fun MainActivity.epgChanged() {
    scope = scopeNow(); epgVer++
    progCache.evictAll(); descCache.evictAll(); hitKey.clear(); ui.postInvalidate()
}

fun MainActivity.syncScope() { if (scopeNow() != scope) epgChanged() }

// the guide keys a playlist channel can be found under (best first), see idKeys at the top of the file
fun MainActivity.keysOf(c: Ch): List<String> = keyCache.getOrPut(c.id) { idKeys(c.id) }

// programmes of one channel (all days kept), read from the database the first time and then remembered for a while;
// the channel's keys are tried one after the other until one of them has programmes
fun MainActivity.progsOf(c: Ch): List<Prog> {
    val ks = keysOf(c); if (ks.isEmpty()) return emptyList()
    progCache.get(c.id)?.let { return it }
    val sc = scope; if (sc.isEmpty()) return emptyList()
    val v = epgVer; var l: List<Prog> = emptyList()
    for (k in ks) {
        l = queryProgs(k, sc)
        if (l.isNotEmpty()) { hitKey[c.id] = k; break }
    }
    if (v == epgVer) progCache.put(c.id, l)
    return l
}

// descriptions are only loaded for programmes that are on now or soon; the rest are read when you open them
fun MainActivity.queryProgs(k: String, sc: List<String>): List<Prog> {
    val now = System.currentTimeMillis(); val hi = now + 12 * 3600_000L; val lo = now - 3600_000L
    val out = ArrayList<Prog>()
    try {
        val sql = "SELECT s, e, t, CASE WHEN s < $hi AND e > $lo THEN d ELSE '' END FROM p WHERE ch = ? AND src IN (" + sc.joinToString(",") { "?" } + ") ORDER BY s"
        epgDb.rawQuery(sql, (listOf(k) + sc).toTypedArray()).use { cur ->
            while (cur.moveToNext()) out.add(Prog(cur.getLong(0), cur.getLong(1), cur.getString(2) ?: "", cur.getString(3) ?: ""))
        }
    } catch (e: Exception) {}
    return out.distinctBy { it.start }
}

fun MainActivity.descOf(c: Ch, p: Prog): String {
    if (p.desc.isNotEmpty()) return p.desc
    val gk = hitKey[c.id] ?: keysOf(c).firstOrNull() ?: return ""
    val k = gk + "|" + p.start
    descCache.get(k)?.let { return it }
    val sc = scope; var d = ""
    if (sc.isNotEmpty()) {
        try {
            val sql = "SELECT d FROM p WHERE ch = ? AND s = ${p.start} AND src IN (" + sc.joinToString(",") { "?" } + ") LIMIT 1"
            epgDb.rawQuery(sql, (listOf(gk) + sc).toTypedArray()).use { cur -> if (cur.moveToFirst()) d = cur.getString(0) ?: "" }
        } catch (e: Exception) {}
    }
    descCache.put(k, d)
    return d
}

// end of the last programme in the database (for the guide files in view): the guide screen only lists days that have data
fun MainActivity.epgEnd(): Long {
    val v = epgVer
    if (endVer == v) return endCache
    val sc = scope; var m = 0L
    if (sc.isNotEmpty()) {
        try {
            epgDb.rawQuery("SELECT MAX(e) FROM p WHERE src IN (" + sc.joinToString(",") { "?" } + ")", sc.toTypedArray()).use { cur ->
                if (cur.moveToFirst() && !cur.isNull(0)) m = cur.getLong(0)
            }
        } catch (e: Exception) {}
    }
    endCache = m; endVer = v
    return m
}

// keys of every channel of every playlist that uses this guide file: only those programmes are stored
fun MainActivity.wantedIds(u: String): HashSet<String> {
    val out = HashSet<String>()
    for (s in sources.toList()) if (u in epgUrls(s)) {
        val l = chCache[s.id] ?: try { loadPlaylist(s, false) } catch (e: Exception) { emptyList() }
        for (c in l) out.addAll(keysOf(c))
    }
    return out
}

// how many past days of guide to keep for the channels that use this guide file (the longest catch-up among them, at most 14)
fun MainActivity.pastDays(u: String): Int {
    var m = 0
    for (s in sources.toList()) if (u in epgUrls(s)) for (c in (chCache[s.id] ?: emptyList())) m = maxOf(m, catchupDays(c))
    return minOf(m, 14)
}

// reads one downloaded guide file straight into the database (nothing is collected in RAM); skipped when it was already imported
fun MainActivity.importEpg(u: String, f: File) {
    val key = hx(u); val keep = wantedIds(u); val past = pastDays(u)
    val mark = "${f.lastModified()}:$epgDaysN:${keep.size}:${dayStart()}:k3:$past"   // k2 = matching rules version (a new version re-imports the file once)
    if (prefs.getString("imp_$key", "") == mark) return
    val lo = dayStart() - past * 86400000L; val hi = dayStart() + epgDaysN * 86400000L
    val fz = SimpleDateFormat("yyyyMMddHHmmss Z", java.util.Locale.US); val fl = SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.US)
    fun pt(s: String?): Long { val d = (s ?: "").trim(); return (if (d.length > 14) fz.parse(d) else fl.parse(d))!!.time }
    var n = 0; var bad = false
    val db = epgDb
    val kc = HashMap<String, String>()   // guide channel id -> its key (so the key is only worked out once per channel, not once per programme)
    val st = db.compileStatement("INSERT INTO p(src, ch, s, e, t, d) VALUES(?, ?, ?, ?, ?, ?)")
    db.beginTransaction()
    try {
        db.execSQL("DELETE FROM p WHERE src = ?", arrayOf<Any?>(key))
        try {
            val bis = f.inputStream().buffered(); bis.mark(2); val b0 = bis.read(); val b1 = bis.read(); bis.reset()
            val ins = if (b0 == 0x1f && b1 == 0x8b) GZIPInputStream(bis, 65536) else bis
            val p = Xml.newPullParser(); p.setInput(ins, null)
            var ch = ""; var s = 0L; var e = 0L; var ti = ""; var de = ""; var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "programme" -> {
                            ti = ""; de = ""
                            val attr = p.getAttributeValue(null, "channel") ?: ""
                            val raw = kc.getOrPut(attr) { idKey(attr) }
                            ch = if (raw.isNotEmpty() && (keep.isEmpty() || raw in keep)) raw else ""   // channels that are in none of your playlists are skipped
                            if (ch.isNotEmpty()) {
                                try { s = pt(p.getAttributeValue(null, "start")); e = pt(p.getAttributeValue(null, "stop")); if (!(e > lo && s < hi)) ch = "" } catch (x: Exception) { ch = "" }
                            }
                        }
                        "title" -> if (ch.isNotEmpty() && ti.isEmpty()) ti = p.nextText()
                        "desc" -> if (ch.isNotEmpty() && de.isEmpty()) de = p.nextText().take(300)
                    }
                } else if (ev == XmlPullParser.END_TAG && p.name == "programme" && ch.isNotEmpty()) {
                    st.clearBindings(); st.bindString(1, key); st.bindString(2, ch); st.bindLong(3, s); st.bindLong(4, e); st.bindString(5, ti); st.bindString(6, de)
                    st.executeInsert(); n++; ch = ""
                    if (n % 2000 == 0) { epgCnt = n; ui.postInvalidate() }
                }
                ev = p.next()
            }
            ins.close()
        } catch (ex: Exception) { bad = true; logErr("EPG file could not be read (" + hostOf(u) + "): " + (ex.message ?: ex.javaClass.simpleName)) }
        if (!bad || n > 0) db.setTransactionSuccessful()   // a broken file with nothing readable keeps the old guide
    } finally { db.endTransaction(); st.close() }
    if (!bad) prefs.edit().putString("imp_$key", mark).apply()
}

// downloads the guide files when they are older than the refresh setting (or when forced), then imports them;
// while it runs, Settings shows what it is doing (epgNow), and when everything is finished it shows "EPG up to date"
fun MainActivity.ensureEpg(urls: List<String>, force: Boolean, download: Boolean = true) {
    if (urls.isEmpty()) return
    if (epgJobs.getAndIncrement() == 0) epgFailed = false
    epgNow = "Starting..."; epgCnt = 0; ui.postInvalidate()
    try {
        val dl = urls.filter { force || !epgAt.containsKey(it) }
        if (download && dl.isNotEmpty()) {
            epgNow = if (dl.size == 1) "Downloading EPG..." else "Downloading ${dl.size} EPG files..."; ui.postInvalidate()
            fetch(dl, "e", force, epgMaxAge())
        }
        urls.forEachIndexed { idx, u ->
            synchronized(lk("e" + u)) {
                val f = File(filesDir, "e" + hx(u))
                if (f.exists()) {
                    epgNow = if (urls.size == 1) "Reading EPG..." else "Reading EPG ${idx + 1} of ${urls.size}..."; epgCnt = 0; ui.postInvalidate()
                    importEpg(u, f); epgAt[u] = f.lastModified()
                }
            }
        }
    } finally {
        if (epgJobs.decrementAndGet() == 0) { epgDoneAt = System.currentTimeMillis(); epgNow = ""; epgCnt = 0 }
        ui.postInvalidate()
    }
}

// the grey line under "Update EPG now" in Settings
fun MainActivity.epgStatus(): String = when {
    epgJobs.get() > 0 -> epgNow.ifEmpty { "Updating EPG..." } + (if (epgCnt > 0) "  \u00b7  $epgCnt programmes" else "")
    epgFailed -> "Some EPG downloads failed. OK tries again"
    epgDoneAt > 0 -> "EPG up to date (updated ${hm.format(epgDoneAt)})"
    else -> "Download the EPG again right now"
}

// loads every other playlist and its guide in the background so switching playlists is instant
fun MainActivity.preloadOthers(refresh: Boolean) {
    if (preloading) return
    preloading = true
    try {
        Thread.currentThread().priority = Thread.MIN_PRIORITY
        for (o in sources.toList()) {
            if (o.id == sources.getOrNull(act)?.id) continue
            try { loadPlaylist(o, refresh); ensureEpg(epgUrls(o), false) } catch (e: Exception) {}
        }
        // favorites span all playlists: refresh them once everything is loaded
        runOnUiThread { epgChanged(); if (grp == 0 && !searching) selectGroup(0, true) }
    } finally { preloading = false }
}

fun MainActivity.refreshEpgOnly() {
    val s = sources.getOrNull(act) ?: return
    ensureEpg(epgUrls(s), false); epgChanged()
    preloadOthers(false)
}

// scheduled / manual guide update: re-downloads the guide files that are older than the refresh setting (all of them when forced)
fun MainActivity.checkEpg(force: Boolean = false) {
    if (epgBusy || sources.isEmpty()) return
    if (!force && epgHours <= 0) return
    val now = System.currentTimeMillis()
    val all = sources.toList().flatMap { epgUrls(it) }.distinct()
    val old = all.filter { force || now - (epgAt[it] ?: File(filesDir, "e" + hx(it)).lastModified()) > epgMaxAge() }
    if (old.isEmpty()) return
    epgBusy = true
    Thread {
        try {
            Thread.currentThread().priority = Thread.MIN_PRIORITY
            ensureEpg(old, true); epgChanged()
            if (force) runOnUiThread { Toast.makeText(this, "EPG updated", Toast.LENGTH_SHORT).show() }
        } catch (e: Exception) {} finally { epgBusy = false }
    }.start()
}
