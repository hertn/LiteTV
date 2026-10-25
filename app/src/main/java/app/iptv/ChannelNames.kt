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

// ---- tvg-id matching ----
// The playlist (iptv-org) and the guide (epgshare01) name the same channel differently, e.g.
//   playlist  Nickelodeon.us@East        guide  Nickelodeon.HD.us2
//   playlist  ABC.us@WMURTV              guide  WMUR-DT.us_locals1
// Both sides are turned into the same simple key: name (without HD / SD, without -DT / -TV endings) + country code.
//   Nickelodeon.us@East -> "nickelodeonus"      Nickelodeon.HD.us2 -> "nickelodeonus"
// A playlist channel can have a second key built from the call sign after the "@":
//   ABC.us@WMURTV -> "wmurus"                   WMUR-DT.us_locals1 -> "wmurus"
val Q_WORDS = setOf("hd", "sd", "fhd", "uhd", "4k", "hevc")
val FEED_WORDS = setOf("sd", "hd", "fhd", "uhd", "4k", "east", "west", "eastern", "western", "pacific", "central", "mountain", "north", "south", "feed", "backup", "alt", "plus1", "plus2", "us", "uk")
val RE_SUFFIX = Regex("-(dt|tv|hd|sd|ld|cd|lp|fm)\\d*$")
val RE_TAIL = Regex("(dt|tv|hd|sd|ld|cd|lp)\\d*$")
val RE_CTRY = Regex("^([a-z]{2})(?![a-z])")

// a piece of an id without dashes suffix and symbols: "wmur-dt" -> "wmur"
fun cleanName(s: String): String = s.lowercase().replace(RE_SUFFIX, "").filter { it.isLetterOrDigit() }

// "us2" / "us_locals1" / "us" -> "us"; anything that does not start with a 2 letter code -> ""
fun ctryOf(part: String): String = RE_CTRY.find(part.lowercase())?.groupValues?.get(1) ?: ""

// one id (without any "@" part) -> key
fun idKey(raw: String): String {
    val all = raw.lowercase().split('.').filter { it.isNotEmpty() }
    if (all.isEmpty()) return ""
    val ps = all.filter { it !in Q_WORDS }.ifEmpty { all }
    if (ps.size == 1) return cleanName(ps[0])
    val c = ctryOf(ps.last())
    val nameParts = if (c.isNotEmpty()) ps.dropLast(1) else ps
    return nameParts.joinToString("") { cleanName(it) } + c
}

// every key a playlist channel can be found under, best first
fun idKeys(id: String): List<String> {
    val out = ArrayList<String>()
    val before = id.substringBefore('@')
    val k1 = idKey(before); if (k1.isNotEmpty()) out.add(k1)
    if ('@' in id) {
        val t = id.substringAfter('@').lowercase().filter { it.isLetterOrDigit() }
        if (t.isNotEmpty() && t !in FEED_WORDS) {
            var call = t.replace(RE_TAIL, ""); if (call.length < 3) call = t
            val parts = before.lowercase().split('.').filter { it.isNotEmpty() }
            val c = if (parts.size > 1) ctryOf(parts.last()) else ""
            val k2 = call + c
            if (k2.isNotEmpty() && k2 !in out) out.add(k2)
        }
    }
    return out
}
