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
// rows: 0 Themes, 1 Font size, 2 Seasonal decorations, 3 Blur behind menus, 4 Transparency | 5 Buffer size, 6 Sleep timer | 7 Playlist refresh,
//       8 Guide refresh, 9 Guide length, 10 Update EPG now | 11 Favorites sort, 12 Within groups, 13 Reorder groups | 14 App update, 15 Error log, 16 About
fun MainActivity.settingsItems() = listOf(
    "Themes" to themeNames()[themeIdx.coerceIn(0, THEMES.lastIndex)],
    "Font Size" to FONT_NAMES[fontIdx],
    "Seasonal Decorations" to (if (seasonOn) "On" else "Off"),
    "Blur Behind Menus" to (if (android.os.Build.VERSION.SDK_INT < 24) "Not available" else if (blurOn) "On" else "Off"),
    "Transparency" to (if (seeThru) "On" else "Off"),
    "Buffering" to BUF_NAMES[bufIdx],
    "Sleep Timer" to (if (sleepMin <= 0) "Off" else "$sleepMin min"),
    "Playlist Refresh" to (if (plDays <= 0) "Manual only" else if (plDays == 1) "Every day" else "Every $plDays days"),
    "Guide Refresh" to (if (epgHours <= 0) "Manual only" else "Every $epgHours hours"),
    "Guide Length" to "$epgDaysN days",
    "Update EPG Now" to "",
    "Favorites Sort" to FAV_SORTS[favSort],
    "Within Groups" to FAV_INNER[favInner],
    "Reorder Groups" to "",
    "App Update" to when (updState) { 1 -> "Checking..."; 2 -> "Up to date"; 3 -> "$updTag available"; 4 -> "Downloading $updPct%"; 5 -> "Ready to install"; 6 -> "Failed"; else -> "" },
    "Error Log" to (if (errLog.isEmpty()) "Empty" else "${errLog.size} entries"),
    "About" to "")

// icon in the little square at the start of each row
val SETTING_ICONS = listOf("palette", "text", "leaf", "blur", "layers", "gauge", "moon", "refresh", "refresh", "cal", "down", "star", "star", "sliders", "down", "bug", "info")

// a heading above these rows
fun MainActivity.settingSection(i: Int): String? = when (i) { 0 -> "APPEARANCE"; 5 -> "PLAYBACK"; 7 -> "PLAYLISTS & TV GUIDE"; 11 -> "FAVORITES"; 14 -> "APP"; else -> null }

// the explanation of the selected row (shown in the footer)
fun MainActivity.settingDesc(i: Int): String = when (i) {
    0 -> "Colour of the highlights. Christmas, Halloween and New Year also colour the menus"
    1 -> "Size of the text on the TV screens"
    2 -> "Holiday icons next to the selected row" + season().let { if (it != 0) ". Today: " + seasonName(it) else "" }
    3 -> if (android.os.Build.VERSION.SDK_INT < 24) "This TV is too old to copy the video picture (needs Android 7)"
         else if (!seeThru) "Turn Transparency on to use the blur"
         else "Blurs the picture behind menus, like Apple TV. Turn it off if the TV feels slow. Off by default on TVs with 1 GB of memory or less"
    4 -> "See the picture through the menus. Off = solid menus, lighter on slow TVs"
    5 -> if (bufIdx == 0) "Channels start quickly. Best for most connections (applies when you leave Settings)" else "Channels take a moment longer to start but cope better with weak Wi-Fi (applies when you leave Settings)"
    6 -> "Closes the app after this time (a warning shows first)"
    7 -> "How often the playlists are downloaded again"
    8 -> "How often the TV guide updates itself"
    9 -> "How many days of programmes to keep"
    10 -> epgStatus()
    11 -> when (favSort) {
        0 -> "Grouped by category, in the order you choose"
        1 -> "In the order you added them"
        2 -> "By channel number, playlist by playlist"
        3 -> "Alphabetical, A to Z"
        else -> "Reverse alphabetical, Z to A"
    }
    12 -> if (favSort == 0) "Order of the channels inside each group" else "Only used with Custom groups"
    13 -> if (favSort == 0) "Move the favorite groups up or down" else "Only used with Custom groups"
    15 -> "Recent problems since the app started"
    16 -> "Version, who made LiteTV, the source code and how to support it"
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

// "Within groups" and "Reorder groups" only apply to the Custom groups sort; blur needs Android 7
fun MainActivity.settingEnabled(i: Int): Boolean = when (i) { 12, 13 -> favSort == 0; 3 -> seeThru && android.os.Build.VERSION.SDK_INT >= 24; else -> true }

// d = -1 (LEFT) or +1 (RIGHT / OK)
fun MainActivity.settingAct(i: Int, d: Int) {
    if (!settingEnabled(i)) return   // greyed-out rows (Blur with Transparency off, old Android) do nothing
    when (i) {
        0 -> if (d > 0) ui.openThemes()
        1 -> { fontIdx = (fontIdx + d + FONT_NAMES.size) % FONT_NAMES.size; prefs.edit().putInt("fontSize", fontIdx).apply() }
        2 -> { seasonOn = !seasonOn; prefs.edit().putBoolean("season", seasonOn).apply() }
        3 -> setBlur(!blurOn)
        4 -> toggleSeeThru(!seeThru)
        5 -> { bufIdx = (bufIdx + d + BUF_NAMES.size) % BUF_NAMES.size; prefs.edit().putInt("buf", bufIdx).apply(); bufDirty = true }
        6 -> { val k = SLEEP_OPTS.indexOf(sleepMin).let { if (it < 0) 0 else it }; setSleep(SLEEP_OPTS[(k + d + SLEEP_OPTS.size) % SLEEP_OPTS.size]) }
        7 -> { val k = PL_OPTS.indexOf(plDays).let { if (it < 0) 3 else it }; plDays = PL_OPTS[(k + d + PL_OPTS.size) % PL_OPTS.size]; prefs.edit().putInt("plD", plDays).apply() }
        8 -> {
            val k = REFRESH_OPTS.indexOf(epgHours).let { if (it < 0) 2 else it }
            epgHours = REFRESH_OPTS[(k + d + REFRESH_OPTS.size) % REFRESH_OPTS.size]; prefs.edit().putInt("epgH", epgHours).apply()
        }
        9 -> {
            val k = DAYS_OPTS.indexOf(epgDaysN).let { if (it < 0) 3 else it }
            epgDaysN = DAYS_OPTS[(k + d + DAYS_OPTS.size) % DAYS_OPTS.size]; prefs.edit().putInt("epgD", epgDaysN).apply(); daysDirty = true
        }
        10 -> if (d > 0) { if (epgJobs.get() > 0) flash("EPG is already updating") else checkEpg(true) }
        11 -> { favSort = (favSort + d + FAV_SORTS.size) % FAV_SORTS.size; prefs.edit().putInt("favSort", favSort).apply(); applyFavSort() }
        12 -> if (favSort == 0) { favInner = (favInner + d + FAV_INNER.size) % FAV_INNER.size; prefs.edit().putInt("favInner", favInner).apply(); applyFavSort() }
        13 -> if (favSort == 0 && d > 0) ui.openFavGroups()
        14 -> if (d > 0) when (updState) { 3 -> downloadUpdate(); 5 -> installUpdate(); 1, 4 -> {}; else -> checkUpdate() }
        15 -> if (d > 0) ui.openLog()
        16 -> if (d > 0) ui.openAbout()
    }
}
