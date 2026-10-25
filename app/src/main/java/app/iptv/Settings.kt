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

// settings panel (Settings screen): rows, headings, descriptions and what each row does; the drawing is in Ui.drawSettings
// settings panel rows: name to current value
// rows: 0 Themes, 1 Font size, 2 Seasonal decorations | 3 Buffer size, 4 Sleep timer | 5 Playlist refresh, 6 Guide refresh, 7 Guide length, 8 Update EPG now
//       | 9 Favorites sort, 10 Within groups, 11 Reorder groups | 12 App update, 13 Error log
fun MainActivity.settingsItems() = listOf(
    "Themes" to themeNames()[themeIdx.coerceIn(0, THEMES.size)],
    "Font size" to FONT_NAMES[fontIdx],
    "Seasonal decorations" to (if (seasonOn) "On" else "Off"),
    "Buffer size" to BUF_NAMES[bufIdx],
    "Sleep timer" to (if (sleepMin <= 0) "Off" else "$sleepMin min"),
    "Playlist refresh" to (if (plDays <= 0) "Manual only" else if (plDays == 1) "Every day" else "Every $plDays days"),
    "Guide refresh" to (if (epgHours <= 0) "Manual only" else "Every $epgHours hours"),
    "Guide length" to "$epgDaysN days",
    "Update EPG now" to "",
    "Favorites sort" to FAV_SORTS[favSort],
    "Within groups" to FAV_INNER[favInner],
    "Reorder groups" to "",
    "App update" to when (updState) { 1 -> "Checking..."; 2 -> "Up to date"; 3 -> "$updTag available"; 4 -> "Downloading $updPct%"; 5 -> "Ready to install"; 6 -> "Failed"; else -> "" },
    "Error log" to (if (errLog.isEmpty()) "Empty" else "${errLog.size} entries"))

// a heading above these rows
fun MainActivity.settingSection(i: Int): String? = when (i) { 0 -> "APPEARANCE"; 3 -> "PLAYBACK"; 5 -> "PLAYLISTS & TV GUIDE"; 9 -> "FAVORITES"; 12 -> "APP"; else -> null }

// rows that are changed with LEFT / RIGHT (shown as < value >)
fun MainActivity.settingArrows(i: Int): Boolean = i in 1..7 || i == 9 || i == 10

// the grey explanation under each row
fun MainActivity.settingDesc(i: Int): String = when (i) {
    0 -> "Colour scheme of the menus and lists"
    1 -> "Size of the text on the TV screens"
    2 -> "Holiday icons next to the selected row" + season().let { if (it != 0) ". Today: " + seasonName(it) else "" }
    3 -> "Bigger = fewer stutters, slower channel start (applies when you leave Settings)"
    4 -> "Closes the app after this time (a warning shows first)"
    5 -> "How often the playlists are downloaded again"
    6 -> "How often the TV guide updates itself"
    7 -> "How many days of programmes to keep"
    8 -> epgStatus()
    9 -> when (favSort) {
        0 -> "Grouped by category, in the order you choose"
        1 -> "In the order you added them"
        2 -> "By channel number, playlist by playlist"
        3 -> "Alphabetical, A to Z"
        else -> "Reverse alphabetical, Z to A"
    }
    10 -> if (favSort == 0) "Order of the channels inside each group" else "Only used with Custom groups"
    11 -> if (favSort == 0) "Move the favorite groups up or down" else "Only used with Custom groups"
    13 -> "Recent problems since the app started"
    else -> when (updState) {
        1 -> "Contacting GitHub..."
        2 -> "You have the latest version. OK checks again"
        3 -> "OK downloads it"
        4 -> "Please wait, this can take a minute"
        5 -> "OK installs it, Android asks you to confirm"
        6 -> "$updErr. OK tries again"
        else -> "Looks for a newer version by itself; OK checks now"
    }
}

// "Within groups" and "Reorder groups" only apply to the Custom groups sort
fun MainActivity.settingEnabled(i: Int): Boolean = if (i == 10 || i == 11) favSort == 0 else true

// d = -1 (LEFT) or +1 (RIGHT / OK)
fun MainActivity.settingAct(i: Int, d: Int) {
    when (i) {
        0 -> if (d > 0) ui.openThemes()
        1 -> { fontIdx = (fontIdx + d + FONT_NAMES.size) % FONT_NAMES.size; prefs.edit().putInt("fontSize", fontIdx).apply() }
        2 -> { seasonOn = !seasonOn; prefs.edit().putBoolean("season", seasonOn).apply() }
        3 -> { bufIdx = (bufIdx + d + BUF_NAMES.size) % BUF_NAMES.size; prefs.edit().putInt("buf", bufIdx).apply(); bufDirty = true }
        4 -> { val k = SLEEP_OPTS.indexOf(sleepMin).let { if (it < 0) 0 else it }; setSleep(SLEEP_OPTS[(k + d + SLEEP_OPTS.size) % SLEEP_OPTS.size]) }
        5 -> { val k = PL_OPTS.indexOf(plDays).let { if (it < 0) 3 else it }; plDays = PL_OPTS[(k + d + PL_OPTS.size) % PL_OPTS.size]; prefs.edit().putInt("plD", plDays).apply() }
        6 -> {
            val k = REFRESH_OPTS.indexOf(epgHours).let { if (it < 0) 2 else it }
            epgHours = REFRESH_OPTS[(k + d + REFRESH_OPTS.size) % REFRESH_OPTS.size]; prefs.edit().putInt("epgH", epgHours).apply()
        }
        7 -> {
            val k = DAYS_OPTS.indexOf(epgDaysN).let { if (it < 0) 3 else it }
            epgDaysN = DAYS_OPTS[(k + d + DAYS_OPTS.size) % DAYS_OPTS.size]; prefs.edit().putInt("epgD", epgDaysN).apply(); daysDirty = true
        }
        8 -> if (d > 0) { if (epgJobs.get() > 0) Toast.makeText(this, "EPG is already updating", Toast.LENGTH_SHORT).show() else checkEpg(true) }
        9 -> { favSort = (favSort + d + FAV_SORTS.size) % FAV_SORTS.size; prefs.edit().putInt("favSort", favSort).apply(); applyFavSort() }
        10 -> if (favSort == 0) { favInner = (favInner + d + FAV_INNER.size) % FAV_INNER.size; prefs.edit().putInt("favInner", favInner).apply(); applyFavSort() }
        11 -> if (favSort == 0 && d > 0) ui.openFavGroups()
        12 -> if (d > 0) when (updState) { 3 -> downloadUpdate(); 5 -> installUpdate(); 1, 4 -> {}; else -> checkUpdate() }
        13 -> if (d > 0) ui.openLog()
    }
}
