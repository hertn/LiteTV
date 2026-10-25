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

// ---- themes ----
// accent = selectors / bars, panel = every panel, past = finished shows, tint = menu gradient + info box + info page, lav / lavT = menu highlight and its text
data class Theme(val name: String, val accent: Int, val panel: Int, val past: Int, val tint: Int, val lav: Int, val lavT: Int)

fun hsv(h: Float, s: Float, v: Float): Int = android.graphics.Color.HSVToColor(floatArrayOf(h, s, v))
fun withAlpha(c: Int, a: Int): Int = (a shl 24) or (c and 0xFFFFFF)
fun blend(a: Int, b: Int, f: Float): Int {
    fun ch(s: Int) = (((a shr s) and 255) * (1 - f) + ((b shr s) and 255) * f).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

val THEMES = listOf(
    Theme("LiteTV Purple", 0xFF990AE3.toInt(), 0xFF130C1A.toInt(), 0xFF2F2B3C.toInt(), 0xFF20152D.toInt(), 0xFFD9B8F0.toInt(), 0xFF3A0057.toInt()),
    Theme("Ocean Blue", 0xFF0A84E3.toInt(), 0xFF0A1420.toInt(), 0xFF26323F.toInt(), 0xFF102235.toInt(), 0xFFB8D9F0.toInt(), 0xFF00315A.toInt()),
    Theme("Emerald", 0xFF12A150.toInt(), 0xFF0A1A12.toInt(), 0xFF283A30.toInt(), 0xFF10291D.toInt(), 0xFFB8F0CF.toInt(), 0xFF003A1C.toInt()),
    Theme("Sunset Orange", 0xFFE36A0A.toInt(), 0xFF1C110A.toInt(), 0xFF3C3028.toInt(), 0xFF2D1B10.toInt(), 0xFFF0D2B8.toInt(), 0xFF572400.toInt()),
    Theme("Crimson", 0xFFD91E3C.toInt(), 0xFF1A0A0E.toInt(), 0xFF3C2A30.toInt(), 0xFF2D1018.toInt(), 0xFFF0B8C4.toInt(), 0xFF57001A.toInt()),
    Theme("Graphite", 0xFF6B7280.toInt(), 0xFF121212.toInt(), 0xFF2E2E2E.toInt(), 0xFF1E1E1E.toInt(), 0xFFD0D4E0.toInt(), 0xFF20222A.toInt())
)

// custom theme: the user steps through 18 swatches per colour (16 hues + 2 neutrals); each row has its own saturation / brightness
const val SW = 18
fun swatch(row: Int, i: Int): Int {
    if (i < 16) {
        val h = i * 22.5f
        return when (row) { 0 -> hsv(h, 0.9f, 0.9f); 1 -> hsv(h, 0.55f, 0.16f); else -> hsv(h, 0.3f, 0.95f) }
    }
    return when (row) {
        0 -> if (i == 16) hsv(0f, 0f, 0.9f) else hsv(0f, 0f, 0.5f)
        1 -> if (i == 16) hsv(0f, 0f, 0.05f) else hsv(0f, 0f, 0.2f)
        else -> if (i == 16) hsv(0f, 0f, 1f) else hsv(0f, 0f, 0.75f)
    }
}

fun customTheme(a: Int, p: Int, h: Int): Theme {
    val acc = swatch(0, a); val pan = swatch(1, p); val lav = swatch(2, h)
    val hs = FloatArray(3); android.graphics.Color.colorToHSV(lav, hs)
    if (hs[1] > 0.05f) hs[1] = minOf(1f, hs[1] + 0.5f)
    hs[2] = 0.28f
    return Theme("Custom", acc, pan, blend(pan, -1, 0.13f), blend(pan, -1, 0.07f), lav, android.graphics.Color.HSVToColor(hs))
}

var T: Theme = THEMES[0]   // the theme in use right now

// palette: panel color for everything, grey only for finished shows, accent for selectors (all follow the active theme)
val C_PUR: Int get() = T.accent                       // selectors / accents
val C_ROW: Int get() = T.panel                        // ALL panels: categories, playlists, channel rows, date/channel cells, options bar, current + upcoming shows, bars, dialogs
val C_PAST: Int get() = T.past                        // grey: shows that have already finished (only these)
val C_INFO: Int get() = withAlpha(T.tint, 0xF2)       // program info box: more transparent
val C_SEL: Int get() = T.panel
val C_TILE: Int get() = T.panel
val C_PROG: Int get() = T.panel
val C_CHIP: Int get() = T.panel
val C_BG: Int get() = T.panel
val C_FIELD: Int get() = T.panel
