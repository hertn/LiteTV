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

class Banner(ctx: Context, val a: MainActivity) : View(ctx) {
    init { alpha = UI_ALPHA }
    var ch: Ch? = null
    val hide = Runnable { visibility = View.GONE }
    fun show(c: Ch) { ch = c; visibility = View.VISIBLE; removeCallbacks(hide); postDelayed(hide, 5000); invalidate() }
    override fun onDraw(c: Canvas) {
        val u = a.ui; val ch = ch ?: return; val w = width.toFloat(); val h = height.toFloat()
        val gx = w * 0.06f; val rh = h / 7.5f * 1.1f; val y = h - rh - h * 0.07f
        val logoW = w * 0.31f * 0.36f; val tw = w * 0.62f; val tx = gx + logoW + 16 * u.d; val mw = tw - logoW - 32 * u.d
        u.fill(c, gx, y, logoW, rh, u.LOGOBG); u.drawLogo(c, ch, gx, y, logoW, rh)
        u.fill(c, gx + logoW, y, tw - logoW, rh, u.ROW)
        val pr = a.now(ch); val nx = a.next(ch)
        u.txt(c, "${ch.num}: ${ch.name}", tx, y + rh * 0.22f, mw, 14f, u.GRAY)
        u.txt(c, pr?.title ?: "No guide data", tx, y + rh * 0.48f, mw, 22f, -1, true)
        if (pr != null) {
            u.txt(c, "${a.hm.format(pr.start)} - ${a.hm.format(pr.stop)}", tx, y + rh * 0.70f, 130 * u.d, 14f, -1)
            u.progress(c, tx + 140 * u.d, y + rh * 0.63f, mw - 140 * u.d, pr)
        }
        if (nx != null) u.txt(c, "Next ${a.hm.format(nx.start)}  ${nx.title}", tx, y + rh * 0.90f, mw, 14f, u.GRAY)
    }
}
