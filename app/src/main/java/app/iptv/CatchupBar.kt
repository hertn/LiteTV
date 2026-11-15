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

// seek bar for catch-up: where you are in the programme; LEFT / RIGHT scrub (holding = bigger steps), OK = jump there, or pause / play
class CatchupBar(ctx: Context, val a: MainActivity) : View(ctx) {
    init { alpha = UI_ALPHA }
    val pt = Paint(Paint.ANTI_ALIAS_FLAG)
    var target = -1L   // where the bar points while you scrub (-1 = it follows the player)
    val thumbs = android.util.LruCache<Int, Bitmap>(80)   // preview pictures, one per 10 seconds of the programme
    val thumbLoading = HashSet<Int>()   // slots being downloaded right now (up to 3 at once)
    val thumbFail = HashSet<Int>()   // slots the server could not give a picture for (so we say so instead of showing black forever)
    var thumbGen = 0   // goes up when a new programme starts, so late answers for the old one are thrown away
    var lastDir = 1   // which way you last scrubbed: pictures in that direction are loaded first
    val thumbRun = Runnable { pumpThumbs() }
    init { pt.isFilterBitmap = true }
    fun slot(ms: Long) = (ms / 10000L).toInt()
    fun thumbUrl(sec: Int): String? {
        val s = a.catchupSrc ?: return null
        if (!s.contains("/catchup/")) return null   // previews only come from the catch-up helper on your server
        return s.replace("/catchup/", "/thumb/") + (if (s.contains("?")) "&" else "?") + "t=" + sec
    }
    fun resetThumbs() { thumbGen++; thumbs.evictAll(); thumbLoading.clear(); thumbFail.clear(); removeCallbacks(thumbRun) }
    // the pictures worth having next, most important first: where you are pointing, then ahead in the scrub direction, then a little behind
    fun wantedThumbs(): List<Int> {
        val dur = a.player.duration; if (dur <= 0) return emptyList()
        val last = slot(maxOf(0L, dur - 2000L))
        val scrub = target >= 0
        val c = slot(if (scrub) target else a.pos())
        val order = ArrayList<Int>(); order.add(c)
        if (scrub) { for (k in 1..8) order.add(c + lastDir * k); for (k in 1..3) order.add(c - lastDir * k) }
        else for (k in 1..4) { order.add(c + k); order.add(c - k) }   // bar just opened: a few on each side of where you are
        return order.filter { it in 0..last && thumbs.get(it) == null && it !in thumbFail && it !in thumbLoading }
    }
    fun pumpThumbs() {
        if (visibility != View.VISIBLE || thumbUrl(0) == null) return
        for (sl in wantedThumbs()) { if (thumbLoading.size >= 3) break; fetchThumb(sl) }
    }
    fun fetchThumb(sl: Int) {
        val u = thumbUrl(sl * 10) ?: return
        val gen = thumbGen
        thumbLoading.add(sl)
        Thread {
            var bm: Bitmap? = null
            try {
                val cn = java.net.URL(u).openConnection() as java.net.HttpURLConnection
                cn.connectTimeout = 4000; cn.readTimeout = 20000
                if (cn.responseCode == 200) bm = BitmapFactory.decodeStream(cn.inputStream)
                cn.disconnect()
            } catch (e: Exception) {}
            val got = bm
            post {
                if (gen != thumbGen) return@post
                thumbLoading.remove(sl)
                if (got != null) thumbs.put(sl, got) else thumbFail.add(sl)
                invalidate(); pumpThumbs()
            }
        }.start()
    }
    // the loaded picture closest to a slot (shown while the exact one is still on its way)
    fun nearThumb(sl: Int): Bitmap? {
        for (k in 1..30) { thumbs.get(sl + k * lastDir)?.let { return it }; thumbs.get(sl - k * lastDir)?.let { return it } }
        return null
    }
    val hide = Runnable { if (target < 0 && a.player.playWhenReady) visibility = View.GONE }
    val commit = Runnable { if (target >= 0) { a.player.seekTo(target); target = -1L; show() } }
    fun show() {
        val was = visibility == View.VISIBLE
        visibility = View.VISIBLE; removeCallbacks(hide); postDelayed(hide, 6000); invalidate(); a.ui.blur.start()
        if (!was && target < 0) pumpThumbs()   // bar just opened: start loading pictures around where you are
    }
    fun nudge(dir: Int, rep: Int) {
        val p = a.player; val dur = p.duration
        if (dur <= 0 || !p.isCurrentMediaItemSeekable) { a.flash("This stream cannot be skipped", 1500); return }
        val step = if (rep >= 20) 60_000L else if (rep >= 8) 30_000L else 10_000L   // holding the key makes the steps bigger
        val base = if (target >= 0) target else a.pos()
        lastDir = dir
        target = (base + dir * step).coerceIn(0L, maxOf(0L, dur - 2000L))
        show(); removeCallbacks(commit)
        if (thumbUrl(0) == null) postDelayed(commit, 900)   // with previews nothing happens until you press OK (BACK cancels)
        removeCallbacks(thumbRun); postDelayed(thumbRun, 150)
    }
    fun close() { removeCallbacks(commit); removeCallbacks(thumbRun); removeCallbacks(hide); target = -1L; visibility = View.GONE }
    fun okPress() {
        if (target >= 0) { removeCallbacks(commit); commit.run() }
        else { a.player.playWhenReady = !a.player.playWhenReady; show() }
    }
    fun tm(ms: Long): String {
        val s = maxOf(0L, ms) / 1000; val h = s / 3600; val m = s % 3600 / 60; val x = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, x) else String.format("%02d:%02d", m, x)
    }
    override fun onDraw(c: Canvas) {
        if (visibility == View.VISIBLE) postInvalidateDelayed(500)
        a.ui.beginShapes(this)
        val u = a.ui; val cp = a.catchupProg ?: return; val pl = a.player; val dur = pl.duration
        if (dur <= 0) return
        if (target < 0 && thumbLoading.isEmpty()) pumpThumbs()   // keeps the pictures around the playing spot ready (also once the length becomes known)
        val pos = (if (target >= 0) target else a.pos()).coerceIn(0L, dur); val buf = pl.bufferedPosition.coerceIn(0L, dur)
        val w = width.toFloat(); val h = height.toFloat(); val U = w / 1920f; u.U = U
        // same rounded panel as the channel banner; the bar is white, the knob shows where you are
        val bw = w * 0.88f; val gx = (w - bw) / 2; val rh = 210 * U; val y = h - rh - h * 0.06f
        u.matRect(c, gx, y, bw, rh, 24 * U)
        val tx = gx + 36 * U; val mw = bw - 72 * U
        u.txt(c, cp.title, tx, y + 50 * U, mw - 160 * U, 40f, -1, 700)
        if (!pl.playWhenReady) u.txt(c, "Paused", tx + mw, y + 50 * U, 160 * U, 25f, -1, 700, android.graphics.Paint.Align.RIGHT)
        val chName = a.cur?.let { (if (it.num > 0) "${it.num} \u00b7 " else "") + it.name + "   \u00b7   " } ?: ""   // which channel this recording is from
        u.txt(c, "$chName${java.text.SimpleDateFormat("EEE dd.MM").format(cp.start)}  ${a.hm.format(cp.start)} \u2013 ${a.hm.format(cp.stop)}", tx, y + 96 * U, mw, 25f, u.SEC)
        val bx = tx + 120 * U; val bww = mw - 240 * U; val by = y + 150 * U; val bh = 8 * U
        u.rr(c, bx, by, bww, bh, bh / 2, 0x38FFFFFF); u.rr(c, bx, by, bww * buf / dur, bh, bh / 2, 0x59FFFFFF); u.rr(c, bx, by, bww * pos / dur, bh, bh / 2, -1)
        pt.color = -1; c.drawCircle(bx + bww * pos / dur, by + bh / 2, 11 * U, pt)
        u.txt(c, tm(pos), tx, by + bh / 2, 110 * U, 25f, -1, 600)
        u.txt(c, tm(dur), tx + mw, by + bh / 2, 110 * U, 25f, u.SEC, 600, android.graphics.Paint.Align.RIGHT)
        if (target >= 0) {
            val now = a.pos(); val diff = Math.abs(target - now)
            val lab = tm(target) + "  (" + (if (target >= now) "+" else "\u2212") + tm(diff) + ")"
            val hasTh = thumbUrl(0) != null
            val tw = (if (hasTh) 480 else 400) * U; val th = if (hasTh) 270 * U else 0f
            val cx = (bx + bww * pos / dur).coerceIn(bx + tw / 2, bx + bww - tw / 2)
            val ph = th + 76 * U; val py = y - 12 * U - ph
            u.matRect(c, cx - tw / 2 - 12 * U, py, tw + 24 * U, ph, 18 * U)
            if (hasTh) {
                val bmp = thumbs.get(slot(target)) ?: nearThumb(slot(target))
                if (bmp != null) c.drawBitmap(bmp, null, RectF(cx - tw / 2, py + 12 * U, cx + tw / 2, py + 12 * U + th), pt)
                else {
                    u.rr(c, cx - tw / 2, py + 12 * U, tw, th, 10 * U, 0xFF000000.toInt())
                    val msg = if (slot(target) in thumbFail) "No preview" else "Loading preview..."
                    u.txt(c, msg, cx, py + 12 * U + th / 2, tw, 24f, u.SEC, 400, android.graphics.Paint.Align.CENTER)
                }
            }
            u.txt(c, lab, cx, py + ph - 32 * U, tw, 29f, -1, 700, android.graphics.Paint.Align.CENTER)
        }
    }
}
