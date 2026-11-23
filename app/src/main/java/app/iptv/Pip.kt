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

// A rounded box that is laid out once at the corner size and moved/scaled with transforms, so a picture can grow to full
// screen and shrink back smoothly (GPU only, no re-layout). Corner radius follows the on-screen size.
open class Box(ctx: Context, val a: MainActivity) : FrameLayout(ctx) {
    fun u() = a.resources.displayMetrics.widthPixels / 1920f
    val bw get() = (360 * u()).toInt().toFloat(); val bh get() = (203 * u()).toInt().toFloat()   // its laid-out size (whole pixels)
    val box = RectF(); var rad = 0f   // where it is on screen, and its corner radius there
    init {
        pivotX = 0f; pivotY = 0f; clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, rad / maxOf(0.01f, scaleX)) } }
    }
    fun full() = RectF(0f, 0f, a.resources.displayMetrics.widthPixels.toFloat(), a.resources.displayMetrics.heightPixels.toFloat())
    fun setBox(r: RectF, radius: Float) {
        box.set(r); rad = radius; translationX = r.left; translationY = r.top; scaleX = r.width() / bw; scaleY = r.height() / bh; invalidateOutline()
    }
    var anim: android.animation.ValueAnimator? = null
    fun animBox(to: RectF, radius: Float, ms: Long, end: () -> Unit = {}) {
        anim?.cancel(); val from = RectF(box); val r0 = rad
        if (!a.ui.animOn()) { setBox(to, radius); end(); return }
        anim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms; interpolator = a.ui.EASE
            addUpdateListener { val t = it.animatedValue as Float
                setBox(RectF(from.left + (to.left - from.left) * t, from.top + (to.top - from.top) * t, from.right + (to.right - from.right) * t, from.bottom + (to.bottom - from.bottom) * t), r0 + (radius - r0) * t) }
            addListener(object : android.animation.AnimatorListenerAdapter() { override fun onAnimationEnd(x: android.animation.Animator) { if (anim === x) { anim = null; end() } } })
            start()
        }
    }
}

// Pinned channel: a second, muted player in a small card in one corner, so an ad break on another channel can be watched
// while zapping. Pinning shrinks the picture into the corner (Apple PiP style); while the pinned channel itself is chosen,
// the big picture shows a "pinned" notice instead of opening a second stream (MainActivity.heldV). Not saved over restarts.
// Two live streams need two decoders (the Philips TV's hardware allows 2) and two connections to the provider.
class Pip(ctx: Context, a: MainActivity) : Box(ctx, a) {
    var ch: Ch? = null
    var corner = 0   // 0 top right, 1 bottom right, 2 bottom left, 3 top left: each Corner tap moves on one
    var parked = false   // grown to full screen while the main player takes over: place() leaves it alone
    val tex = TextureView(ctx)   // a TextureView (not a SurfaceView) so the rounded corners clip the picture
    val snap = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_XY }   // still frame shown until the stream's first picture
    var player: ExoPlayer? = null
    val retry = Runnable { player?.let { it.seekToDefaultPosition(); it.prepare() } }
    val caption = object : View(ctx) {
        override fun onDraw(c: Canvas) { drawCaption(c, width.toFloat(), height.toFloat()); postInvalidateDelayed(30_000L) }   // time left + progress bar
    }
    init { setBackgroundColor(0xFF000000.toInt()); addView(tex, LayoutParams(-1, -1)); addView(snap, LayoutParams(-1, -1)); addView(caption, LayoutParams(-1, -1)); visibility = View.GONE }

    fun cornerBox(k: Int = corner): RectF {
        val u = u(); val sw = a.resources.displayMetrics.widthPixels; val sh = a.resources.displayMetrics.heightPixels; val m = 40 * u
        val lift = if ((k == 1 || k == 2) && a.ui.visibility == View.VISIBLE && a.ui.mode == 4) 305 * u else 0f   // above the OK screen's tiles
        val x = if (k <= 1) sw - m - bw else m; val y = if (k == 0 || k == 3) m else sh - m - bh - lift
        return RectF(x, y, x + bw, y + bh)
    }
    private fun captionOn(on: Boolean) { caption.animate().cancel(); caption.animate().alpha(if (on) 1f else 0f).setDuration(if (on) 200 else 120).start(); if (on) caption.invalidate() }
    private fun load(c: Ch, still: Bitmap?) {
        snap.setImageBitmap(still); snap.alpha = 1f; snap.visibility = if (still != null) View.VISIBLE else View.GONE
        val p = player ?: makePlayer().also { player = it }
        removeCallbacks(retry); p.setMediaItem(MediaItem.fromUri(c.url)); p.prepare(); p.playWhenReady = true
    }

    // Pin: the picture (still frame) starts full screen and shrinks into the corner; the stream takes over when it starts
    fun pinFromFull(c: Ch, still: Bitmap?) {
        ch = c; parked = false; visibility = View.VISIBLE; caption.alpha = 0f; load(c, still); setBox(full(), 0f)
        animBox(cornerBox(), 18 * u(), 450) { captionOn(true); place() }
    }
    // Swap, second half: the main player now shows the old pinned channel, so this card becomes the other one in its corner
    fun becomeCorner(c: Ch, still: Bitmap?) { ch = c; parked = false; load(c, still); setBox(cornerBox(), 18 * u()); caption.alpha = 1f; caption.invalidate(); place() }
    // grows to full screen and stays there (parked) until the caller hands over to the main player
    fun growFull(end: () -> Unit) { parked = true; captionOn(false); animBox(full(), 0f, 450, end) }
    fun nextCorner() { corner = (corner + 1) % 4; animBox(cornerBox(), 18 * u(), 400) }
    fun unpin() {
        ch = null; parked = false; captionOn(false); val b = RectF(box); val dx = b.width() * 0.15f; val dy = b.height() * 0.15f
        animate().cancel(); animate().alpha(0f).setDuration(260).start()
        animBox(RectF(b.left + dx, b.top + dy, b.right - dx, b.bottom - dy), rad * 0.7f, 260) { drop() }
    }
    // gone at once (after a hand-over to the main player, or Unpin's fade)
    fun drop() { ch = null; parked = false; anim?.cancel(); removeCallbacks(retry); player?.release(); player = null; snap.setImageBitmap(null); visibility = View.GONE; alpha = 1f }

    private fun makePlayer(): ExoPlayer {
        val ds = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true).setUserAgent("Mozilla/5.0").setConnectTimeoutMs(10000)
        val p = ExoPlayer.Builder(a, DefaultRenderersFactory(a).setEnableDecoderFallback(true))
            .setMediaSourceFactory(DefaultMediaSourceFactory(ds))
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(8000, 8000, 800, 1500).build()).build()
        // a small picture: the lowest-quality stream, no sound, no subtitles
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setMaxVideoSizeSd()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        p.volume = 0f; p.setVideoTextureView(tex)
        p.addListener(object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) { caption.invalidate(); removeCallbacks(retry); postDelayed(retry, 10_000L) }   // keep trying while pinned
            override fun onPlaybackStateChanged(st: Int) { if (st == Player.STATE_READY) caption.invalidate() }
            override fun onRenderedFirstFrame() { if (snap.visibility == View.VISIBLE) snap.animate().alpha(0f).setDuration(200).withEndAction { snap.visibility = View.GONE; snap.setImageBitmap(null) }.start() }
        })
        return p
    }

    // resting place: its corner (lifted while the OK screen is open), above the OK screen's dark fade, under every other menu
    fun place() {
        if (ch == null || parked) return
        val ok = a.ui.visibility == View.VISIBLE && a.ui.mode == 4
        translationZ = if (ok) 8 * u() else 0f
        val r = cornerBox(); if (anim == null && r != box) animBox(r, 18 * u(), 240)
    }
    fun stop() { removeCallbacks(retry); player?.stop() }
    fun start() { if (ch != null) player?.let { it.prepare(); it.playWhenReady = true } }

    fun drawCaption(c: Canvas, w: Float, h: Float) {
        val ch = ch ?: return; val ui = a.ui; val u = u(); ui.U = u; val pr = a.now(ch)
        ui.p.shader = LinearGradient(0f, h / 2, 0f, h, 0, 0xD2000000.toInt(), Shader.TileMode.CLAMP); c.drawRect(0f, h / 2, w, h, ui.p); ui.p.shader = null
        ui.tile(c, ch, 12 * u, h - 66 * u, 74 * u, 44 * u, 0x2EFFFFFF)
        val tx = 96 * u; val tmw = w - tx - 12 * u
        ui.txt(c, pr?.title ?: ch.name, tx, h - 56 * u, tmw, 20f, ui.WHITE, 700)
        if (player?.playerError != null) ui.txt(c, "Reconnecting…", tx, h - 31 * u, tmw, 17f, ui.SEC, 600)
        else { val lw = ui.live(c, tx, h - 31 * u, 13f); if (pr != null) ui.txt(c, "${ui.minLeft(pr)} min left", tx + lw + 8 * u, h - 31 * u, tmw - lw - 8 * u, 17f, 0xD2FFFFFF.toInt(), 600) }
        if (pr != null) ui.progress(c, 12 * u, h - 14 * u, w - 24 * u, pr, 4f)
        ui.p.style = Paint.Style.STROKE; ui.p.strokeWidth = 1.5f * u; ui.p.color = 0x33FFFFFF   // faint edge, as on the menu cards
        c.drawRoundRect(RectF(0.75f * u, 0.75f * u, w - 0.75f * u, h - 0.75f * u), 18 * u, 18 * u, ui.p); ui.p.style = Paint.Style.FILL
    }
}
