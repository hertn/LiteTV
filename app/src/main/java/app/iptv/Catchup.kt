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

// ---- catch-up (rewatching past shows) ----
fun catchupMode(c: Ch): String {
    val m = c.catchup.lowercase().trim()
    if (m.isEmpty() || m == "0" || m == "false" || m == "none") return ""
    return when (m) {
        "append" -> if (c.cSrc.isNotEmpty()) "append" else ""
        "shift", "timeshift" -> "shift"
        "flussonic", "flussonic-hls", "flussonic-ts", "fs" -> "flussonic"
        "xc", "xtream", "xtream-codes" -> "xc"
        else -> if (c.cSrc.isNotEmpty()) "default" else ""
    }
}
fun catchupDays(c: Ch): Int = if (catchupMode(c).isEmpty()) 0 else if (c.cDays > 0) c.cDays else 7

val RE_CF = Regex("""\$?\{(utc|utcend|start|end|lutc|now|timestamp):([^}]*)\}""")
val RE_CD = Regex("""\$?\{(duration|offset):(\d+)\}""")
val RE_CV = Regex("""\$?\{(utc|utcend|start|end|lutc|now|timestamp|duration|offset|Y|m|d|H|M|S)\}""")
val RE_XC = Regex("""^(https?://[^/]+)/(?:live/)?([^/]+)/([^/]+)/(\d+)(?:\.\w+)?$""")

// date pieces in UTC: Y m d H M S, anything else is copied as it is
fun utcFmt(f: String, ms: Long): String {
    val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")); cal.timeInMillis = ms
    fun p2(n: Int) = if (n < 10) "0$n" else "$n"
    val sb = StringBuilder()
    for (ch in f) sb.append(when (ch) {
        'Y' -> cal.get(java.util.Calendar.YEAR).toString()
        'm' -> p2(cal.get(java.util.Calendar.MONTH) + 1)
        'd' -> p2(cal.get(java.util.Calendar.DAY_OF_MONTH))
        'H' -> p2(cal.get(java.util.Calendar.HOUR_OF_DAY))
        'M' -> p2(cal.get(java.util.Calendar.MINUTE))
        'S' -> p2(cal.get(java.util.Calendar.SECOND))
        else -> ch.toString()
    })
    return sb.toString()
}

// fills in {utc} {utcend} {start} {end} {duration} {offset} {lutc} {Y}{m}{d}{H}{M}{S} and {utc:YmdHMS}-style placeholders
fun fillCatchup(t: String, st: Long, en: Long, now: Long): String {
    val ss = st / 1000; val es = en / 1000; val ns = now / 1000
    var s = RE_CF.replace(t) { m ->
        val v = when (m.groupValues[1]) { "utc", "start" -> st; "utcend", "end" -> en; else -> now }
        utcFmt(m.groupValues[2], v)
    }
    s = RE_CD.replace(s) { m ->
        val n = maxOf(1L, m.groupValues[2].toLong())
        ((if (m.groupValues[1] == "duration") es - ss else ns - ss) / n).toString()
    }
    return RE_CV.replace(s) { m ->
        when (m.groupValues[1]) {
            "utc", "start" -> ss.toString()
            "utcend", "end" -> es.toString()
            "lutc", "now", "timestamp" -> ns.toString()
            "duration" -> (es - ss).toString()
            "offset" -> (ns - ss).toString()
            else -> utcFmt(m.groupValues[1], st)
        }
    }
}

fun flussonicTpl(u: String): String? {
    val q = u.indexOf('?'); val base = if (q >= 0) u.substring(0, q) else u; val qs = if (q >= 0) u.substring(q) else ""
    val nb = when {
        base.endsWith("/mpegts") || base.endsWith("/embed.ts") -> base.substringBeforeLast('/') + "/timeshift_abs-{utc}.ts"
        base.endsWith(".m3u8") -> base.removeSuffix(".m3u8") + "-{utc}-{duration}.m3u8"
        else -> return null
    }
    return nb + qs
}

fun xcTpl(u: String): String? {
    val q = u.indexOf('?'); val base = if (q >= 0) u.substring(0, q) else u
    val g = RE_XC.find(base)?.groupValues ?: return null
    return g[1] + "/timeshift/" + g[2] + "/" + g[3] + "/{duration:60}/{Y}-{m}-{d}:{H}-{M}/" + g[4] + ".ts"
}

// the link that plays one past programme of a channel, or null when the channel has no usable catch-up recipe
fun catchupUrl(c: Ch, p: Prog): String? {
    val tpl: String = when (catchupMode(c)) {
        "default" -> if ("://" in c.cSrc) c.cSrc else c.url + c.cSrc
        "append" -> c.url + c.cSrc
        "shift" -> c.url + (if ('?' in c.url) "&" else "?") + "utc={utc}&lutc={lutc}"
        "flussonic" -> flussonicTpl(c.url) ?: return null
        "xc" -> xcTpl(c.url) ?: return null
        else -> return null
    }
    return fillCatchup(tpl, p.start, p.stop, System.currentTimeMillis())
}
