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

// ---- seasonal decorations: picked from the date, moving holidays (Easter, Thanksgiving, Advent) are calculated ----
const val SE_PARTY = 1; const val SE_HEART = 2; const val SE_FLAG_EE = 3; const val SE_BUN = 4; const val SE_WILLOW = 5; const val SE_EGG = 6
const val SE_VAPPU = 7; const val SE_WREATH = 8; const val SE_FIREWORK = 9; const val SE_CORNFLOWER = 10; const val SE_LEAF = 11; const val SE_CAKE = 12
const val SE_PUMPKIN = 13; const val SE_BAT = 14; const val SE_ACORN = 15; const val SE_TURKEY = 16; const val SE_FLAG_FI = 17
const val SE_HAT = 18; const val SE_HAT_GIFT = 19
const val SE_CANDLES = 21   // 21..24 = Advent wreath with 1..4 candles lit

fun doyOf(y: Int, m: Int, d: Int): Int = java.util.GregorianCalendar(y, m - 1, d).get(java.util.Calendar.DAY_OF_YEAR)

// Easter Sunday (Gregorian), as day of the year
fun easterDoy(y: Int): Int {
    val a = y % 19; val b = y / 100; val c = y % 100; val d = b / 4; val e = b % 4
    val f = (b + 8) / 25; val g = (b - f + 1) / 3; val h = (19 * a + b - d - g + 15) % 30
    val i = c / 4; val k = c % 4; val l = (32 + 2 * e + 2 * i - h - k) % 7
    val m = (a + 11 * h + 22 * l) / 451
    val month = (h + l - 7 * m + 114) / 31; val day = (h + l - 7 * m + 114) % 31 + 1
    return doyOf(y, month, day)
}

// US Thanksgiving = 4th Thursday of November
fun thanksDoy(y: Int): Int {
    val first = java.util.GregorianCalendar(y, 10, 1).get(java.util.Calendar.DAY_OF_WEEK)   // SUNDAY = 1, THURSDAY = 5
    return doyOf(y, 11, 1 + (5 - first + 7) % 7 + 21)
}

// 4th Advent = the last Sunday on or before Dec 24 (it is Dec 24 itself only when Dec 24 is a Sunday)
fun advent4Doy(y: Int): Int {
    val w = java.util.GregorianCalendar(y, 11, 24).get(java.util.Calendar.DAY_OF_WEEK)
    return doyOf(y, 12, 24) - (w - 1)
}

fun seasonId(t: Long): Int {
    val c = java.util.Calendar.getInstance(); c.timeInMillis = t
    val y = c.get(java.util.Calendar.YEAR); val m = c.get(java.util.Calendar.MONTH) + 1
    val d = c.get(java.util.Calendar.DAY_OF_MONTH); val n = c.get(java.util.Calendar.DAY_OF_YEAR)
    // special days first: they replace the month's icon for that one day only
    if ((m == 1 && d == 1) || (m == 12 && d == 31)) return SE_PARTY
    if (m == 2 && d == 14) return SE_HEART
    if (m == 2 && d == 24) return SE_FLAG_EE        // Estonian Independence Day
    if (m == 5 && d == 1) return SE_VAPPU
    if (m == 6 && (d == 23 || d == 24)) return SE_WREATH
    if (m == 7 && d == 4) return SE_FIREWORK
    if (m == 8 && d == 20) return SE_CORNFLOWER     // Estonian Day of Restoration of Independence
    if (m == 10 && d == 18) return SE_CAKE          // birthday
    if (m == 10 && d == 31) return SE_BAT
    if (m == 12 && d == 6) return SE_FLAG_FI        // Finnish Independence Day
    if (m == 12 && d in 24..26) return SE_HAT_GIFT
    val es = easterDoy(y)
    if (n == es - 47) return SE_BUN                 // Vastlapaev
    if (n == es - 7) return SE_WILLOW               // Palm Sunday
    if (n in es - 2..es + 1) return SE_EGG          // Good Friday - Easter Monday
    val tg = thanksDoy(y)
    if (n in tg - 3..tg + 1) return SE_TURKEY       // Thanksgiving week, Monday - Friday
    val a4 = advent4Doy(y)
    for (k in 0..3) if (n == a4 - 7 * (3 - k)) return SE_CANDLES + k   // the four Advent Sundays
    // the month's own icon
    if (m == 12) return SE_HAT
    if (m == 11) return SE_ACORN
    if (m == 10) return if (d >= 15) SE_PUMPKIN else SE_LEAF
    if (m == 9) return SE_LEAF
    return 0
}

// what today's icon celebrates, shown under the Seasonal decorations setting
fun seasonName(id: Int): String = when (id) {
    SE_PARTY -> "New Year"
    SE_HEART -> "Valentine's Day"
    SE_FLAG_EE -> "Estonian Independence Day"
    SE_BUN -> "Vastlapäev (Shrove Tuesday)"
    SE_WILLOW -> "Palm Sunday"
    SE_EGG -> "Easter"
    SE_VAPPU -> "May Day (Vappu)"
    SE_WREATH -> "Midsummer"
    SE_FIREWORK -> "Independence Day (US)"
    SE_CORNFLOWER -> "Estonian Day of Restoration of Independence"
    SE_LEAF -> "Autumn"
    SE_CAKE -> "Hert's birthday! (the creator of this app)"
    SE_PUMPKIN -> "Halloween season"
    SE_BAT -> "Halloween"
    SE_ACORN -> "November"
    SE_TURKEY -> "Thanksgiving"
    SE_FLAG_FI -> "Finnish Independence Day"
    SE_HAT -> "December"
    SE_HAT_GIFT -> "Christmas"
    in SE_CANDLES..SE_CANDLES + 3 -> "Advent, candle ${id - SE_CANDLES + 1}"
    else -> ""
}
