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

// playlist sources: reading and saving them, downloading, parsing M3U, the scheduled refresh
fun MainActivity.open(u: String) = (URL(u).openConnection() as HttpURLConnection).apply {
    connectTimeout = 15000; readTimeout = 30000; setRequestProperty("User-Agent", "Mozilla/5.0")
}.inputStream

fun MainActivity.newId() = java.util.UUID.randomUUID().toString().take(8)

fun MainActivity.readSrcs(): List<Src> = try {
    val a = JSONArray(prefs.getString("srcs", "[]"))
    (0 until a.length()).map { k ->
        val o = a.getJSONObject(k); val m = o.optString("m"); val n = o.optString("n")
        Src(o.optString("i").ifEmpty { hx(m + n) }, n, m)
    }
} catch (e: Exception) { emptyList() }

fun MainActivity.saveSrcs(l: List<Src>) {
    val a = JSONArray(); l.forEach { a.put(JSONObject().put("i", it.id).put("n", it.name).put("m", it.m3u)) }
    prefs.edit().putString("srcs", a.toString()).apply()
}

fun MainActivity.readEpgs(): List<Epg> = try {
    val a = JSONArray(prefs.getString("epgs", "[]"))
    (0 until a.length()).map { k ->
        val o = a.getJSONObject(k); val ids = o.optJSONArray("a")
        Epg(o.optString("i"), o.optString("n"), o.optString("u"), o.optBoolean("all"), if (ids == null) emptyList() else (0 until ids.length()).map { j -> ids.getString(j) }, o.optString("o"))
    }
} catch (e: Exception) { emptyList() }

fun MainActivity.saveEpgs(l: List<Epg>) {
    val a = JSONArray()
    l.forEach { a.put(JSONObject().put("i", it.id).put("n", it.name).put("u", it.url).put("all", it.all).put("a", JSONArray(it.srcIds)).put("o", it.owner)) }
    prefs.edit().putString("epgs", a.toString()).apply()
}

// EPGs assigned to this playlist (or flagged "all"); none assigned = the url-tvg found in the M3U header
fun MainActivity.epgUrls(s: Src): List<String> {
    val m = epgs.filter { it.all || s.id in it.srcIds }.map { it.url.trim() }.filter { it.startsWith("http") }.distinct()
    return if (m.isNotEmpty()) m else prefs.getString("auto_" + hx(s.m3u), "")!!.split(" ").filter { it.startsWith("http") }
}

fun MainActivity.hx(u: String) = Integer.toHexString(u.hashCode())

// Reload button: forgets the channel logos and the in-memory guide caches; playlists and guides are re-downloaded by activate(refresh = true),
// which keeps the old file (and the guide already imported) for any source whose download fails
fun MainActivity.clearLogoAndGuideCaches() {
    cacheDir.listFiles()?.filter { it.name.startsWith("lg") }?.forEach { it.delete() }
    logos.evictAll(); failed.clear(); progCache.evictAll(); descCache.evictAll()
    runOnUiThread { catchupBar.resetThumbs() }
}
fun MainActivity.cleanCache() {
    val keep = HashSet<String>()
    for (s in sources) { keep.add("l" + hx(s.m3u)); epgUrls(s).forEach { keep.add("e" + hx(it)) } }
    filesDir.listFiles()?.filter { (it.name.startsWith("l") || it.name.startsWith("e")) && it.name !in keep }?.forEach { it.delete() }
    // guide rows of guide files that no playlist uses any more
    val keys = sources.flatMap { epgUrls(it) }.map { hx(it) }.distinct()
    try { epgDb.execSQL(if (keys.isEmpty()) "DELETE FROM p" else "DELETE FROM p WHERE src NOT IN (" + keys.joinToString(",") { "'$it'" } + ")") } catch (e: Exception) {}
}

// downloads every url in parallel to its own cache file; failed ones keep their old cache
fun MainActivity.fetch(urls: List<String>, pre: String, refresh: Boolean, maxAge: Long): List<File> {
    val files = urls.map { File(filesDir, pre + hx(it)) }
    val fails = java.util.concurrent.atomic.AtomicInteger()
    urls.indices.map { i ->
        Thread {
            val f = files[i]
            if (refresh || !f.exists() || System.currentTimeMillis() - f.lastModified() > maxAge) {
                try { val t = File(f.path + ".tmp"); open(urls[i]).use { s -> t.outputStream().use { s.copyTo(it) } }; t.renameTo(f) }
                catch (e: Exception) { fails.incrementAndGet(); logErr((if (pre == "l") "Playlist" else "EPG") + " download failed (" + hostOf(urls[i]) + "): " + (e.message ?: e.javaClass.simpleName)) }
            }
        }.apply { start() }
    }.forEach { it.join() }
    val what = if (pre == "l") "playlist" else "EPG"
    if (pre == "e" && fails.get() > 0) epgFailed = true
    if (fails.get() > 0) runOnUiThread { Toast.makeText(this, "${fails.get()} $what source(s) failed", Toast.LENGTH_LONG).show() }
    return files.filter { it.exists() }
}

// channels of one playlist: from memory, else from the cached file / download (also remembers the EPG url in its header)
fun MainActivity.loadPlaylist(s: Src, refresh: Boolean): List<Ch> = synchronized(lk("l" + s.id)) {
    if (refresh) chCache.remove(s.id)
    chCache[s.id]?.let { return@synchronized it }
    val all = ArrayList<Ch>()
    for (f in fetch(listOf(s.m3u), "l", refresh, plMaxAge())) {
        val head = f.bufferedReader().use { it.readLine() } ?: ""
        val auto = Regex("(?:url-tvg|x-tvg-url)=\"([^\"]*)\"").find(head)?.groupValues?.get(1)
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.joinToString(" ") ?: ""
        prefs.edit().putString("auto_" + hx(s.m3u), auto).apply()
        all.addAll(f.useLines { parseM3u(it).toList() }.map { c -> c.copy(src = s.id) })
    }
    if (all.isNotEmpty()) chCache[s.id] = all
    all
}

// the hourly check while the app is on screen (and once more each time you come back to it)
fun MainActivity.plMaxAge(): Long = if (plDays <= 0) Long.MAX_VALUE else plDays * 86400_000L

// playlists whose downloaded file is older than the Playlist refresh setting are downloaded again; only while no list is open,
// because the channel list is rebuilt (the other playlists follow in the background)
fun MainActivity.checkPlaylists() {
    if (plDays <= 0 || sources.isEmpty() || ui.mode != 0 || preloading) return
    val now = System.currentTimeMillis()
    val old = sources.toList().filter { val f = File(filesDir, "l" + hx(it.m3u)); f.exists() && now - f.lastModified() > plMaxAge() }
    if (old.isEmpty()) return
    for (o in old) chCache.remove(o.id)
    activate(act, false)
}

fun MainActivity.parseM3u(lines: Sequence<String>): List<Ch> {
    val out = ArrayList<Ch>(); val re = Regex("([\\w-]+)=\"([^\"]*)\"")
    var name = ""; var id = ""; var g = "Other"; var logo = ""; var no = 0; var have = false
    var cm = ""; var cs = ""; var cd = 0
    var hm0 = ""; var hs0 = ""; var hd0 = 0   // catch-up settings from the #EXTM3U header line, used when a channel has none of its own
    for (l in lines) {
        if (l.startsWith("#EXTM3U")) {
            val a = re.findAll(l).associate { it.groupValues[1] to it.groupValues[2] }
            hm0 = a["catchup"] ?: ""; hs0 = a["catchup-source"] ?: ""; hd0 = a["catchup-days"]?.toIntOrNull() ?: 0
        } else if (l.startsWith("#EXTINF")) {
            val a = re.findAll(l).associate { it.groupValues[1] to it.groupValues[2] }
            val q = l.lastIndexOf('"'); val cp = l.indexOf(',', maxOf(0, q))   // the name comes after the last comma that is outside the quotes
            name = (if (cp >= 0) l.substring(cp + 1) else l.substringAfterLast(',')).trim(); id = a["tvg-id"] ?: ""; if (id.isEmpty()) id = name
            g = a["group-title"]?.ifEmpty { null } ?: "Other"; logo = a["tvg-logo"] ?: ""; no = a["tvg-chno"]?.toIntOrNull() ?: 0
            cm = a["catchup"] ?: hm0; cs = a["catchup-source"] ?: hs0
            cd = a["catchup-days"]?.toIntOrNull() ?: a["tvg-rec"]?.toIntOrNull() ?: a["timeshift"]?.toIntOrNull() ?: hd0
            if (cm.isEmpty() && cs.isNotEmpty()) cm = "default"
            have = true
        } else if (have && l.isNotBlank() && !l.startsWith("#")) {
            out.add(Ch(id, name, g, l.trim(), logo, if (no > 0) no else out.size + 1, catchup = cm, cSrc = cs, cDays = cd)); have = false
        }
    }
    return out
}
