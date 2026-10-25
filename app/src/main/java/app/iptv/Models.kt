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

data class Ch(val id: String, val name: String, val group: String, val url: String, val logo: String, val num: Int, val src: String = "", val catchup: String = "", val cSrc: String = "", val cDays: Int = 0)
data class Prog(val start: Long, val stop: Long, val title: String, val desc: String)
data class Src(val id: String, val name: String, val m3u: String)
data class Epg(val id: String, val name: String, val url: String, val all: Boolean, val srcIds: List<String>, val owner: String = "")
data class DBtn(val label: String, val color: Int, val act: (android.app.Dialog) -> Unit)

// settings choices: guide refresh (hours, 0 = only by hand) and guide length (days)
val REFRESH_OPTS = intArrayOf(0, 6, 12, 24, 48)
val DAYS_OPTS = intArrayOf(2, 3, 5, 7, 10, 14)
val PL_OPTS = intArrayOf(0, 1, 3, 7, 14)   // playlist refresh choices in days (0 = only by hand)
val SLEEP_OPTS = intArrayOf(0, 30, 60, 90, 120)   // sleep timer choices in minutes (0 = off)
