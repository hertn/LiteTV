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
    fun show(c: Ch) { ch = c; visibility = View.VISIBLE; removeCallbacks(hide); postDelayed(hide, 5000); invalidate(); a.ui.blur.start() }
    // the banner after a channel change (5 s, only when no list is open): a rounded panel with now and next
    override fun onDraw(c: Canvas) {
        a.ui.beginShapes(this)
        val u = a.ui; val ch = ch ?: return; val w = width.toFloat(); val h = height.toFloat(); val U = w / 1920f; u.U = U
        val bw = w * 0.62f; val bh = 250 * U; val bx = w * 0.06f; val by = h - bh - h * 0.06f
        u.matRect(c, bx, by, bw, bh, 24 * U)
        u.tile(c, ch, bx + 50 * U, by + 50 * U, 266 * U, 150 * U, 0x14FFFFFF)   // same 50 px gap on every side
        val tx = bx + 352 * U; val tw = bw - 388 * U; val pr = a.now(ch); val nx = a.next(ch)
        u.txt(c, "${ch.num}: ${ch.name}", tx, by + 50 * U, tw, 26f, u.SEC)
        u.txt(c, pr?.title ?: "No guide data", tx, by + 98 * U, tw, 46f, -1, 700)
        if (pr != null) {
            val ly = by + 160 * U; var x = tx + u.live(c, tx, ly, 18f) + 18 * U
            val t = "${a.hm.format(pr.start)} \u2013 ${a.hm.format(pr.stop)}"; u.txt(c, t, x, ly, 300 * U, 25f, -1); x += u.tw(t, 25f) + 18 * U
            val left = "${u.minLeft(pr)} min left"; val lw = u.tw(left, 25f)
            u.progress(c, x, ly - 3 * U, tx + tw - lw - 18 * U - x, pr, 6f); u.txt(c, left, tx + tw - lw, ly, lw + 4 * U, 25f, u.SEC)
        }
        if (nx != null) {
            val ny = by + 210 * U; u.txt(c, "Next", tx, ny, 100 * U, 25f, -1, 600); var x = tx + u.tw("Next", 25f, 600) + 18 * U
            val s = "${a.hm.format(nx.start)}   ${nx.title}"; val rem = a.hasRem(ch, nx)
            u.txt(c, s, x, ny, tw - (x - tx) - (if (rem) 34 * U else 0f), 25f, u.SEC); x += minOf(u.tw(s, 25f), tw - (x - tx) - 34 * U) + 22 * U
            if (rem) u.icon(c, "bell", x, ny, 20 * U, 0, true)
        }
        if (T.gold && u.animOn()) postInvalidateDelayed(66)
    }
}
