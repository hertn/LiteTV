@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.iptv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ComposeShader
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View

// Apple TV style screens, all drawn on one canvas. Sizes are written in pixels of a 1920 x 1080 screen (the redesign
// mockups) and scaled by U, so the layout is the same on any TV; only text follows the Font size setting.
class Ui(ctx: Context, val a: MainActivity) : View(ctx) {
    val d = ctx.resources.displayMetrics.density
    val fs: Float get() = a.fontScale
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
    var U = 1f   // 1920-px mockup units -> screen pixels (set on every draw)

    // colours (white text on the material; "secondary" and "tertiary" are white at 64% / 52%, as on Apple TV)
    val WHITE = -1; val SEC = 0xA3FFFFFF.toInt(); val TER = 0x85FFFFFF.toInt()
    val FOC: Int get() = if (T.redFocus) T.accent or 0xFF000000.toInt() else if (T.party) GOLDF else WHITE
    val GOLDF = 0xFFE8C66B.toInt()   // stands for "sparkling gold": rr/fill/circles drawn in it get the moving gold + glitter (plain gold where that is not possible)   // the focus highlight: white (Christmas: its red)
    val ONF: Int get() = if (T.redFocus) WHITE else 0xFF1C1C1E.toInt(); val ONF2: Int get() = if (T.redFocus) 0xCCFFFFFF.toInt() else 0x9E1C1C1E.toInt()   // text on the focus highlight
    val SELBG = 0x24FFFFFF; val FILL = 0x14FFFFFF; val HAIR = 0x14FFFFFF; val TRACK = 0x38FFFFFF; val BARC = 0xEBFFFFFF.toInt()
    val GRAY: Int get() = SEC
    val OUTLINE: Int get() = withAlpha(T.accent, if (T.gold) 0xBF else 0xCC)   // selected row / card outline: the theme's accent
    val REG: Typeface = Typeface.DEFAULT
    val MED: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val BOLD: Typeface = Typeface.DEFAULT_BOLD

    val dd = java.text.SimpleDateFormat("dd.MM"); val wd = java.text.SimpleDateFormat("EEE")
    val S = 6; val dir = if (REVERSED) 1 else -1
    // mode: 0 hidden, 1 channel list, 2 date/channel/program, 3 main menu (OK), 4 channel settings, 5 program info, 6 theme list,
    // 8 settings, 9 favorite group order, 10 error log, 11 reminder card, 12 remote buttons, 13 about, 14 reminders, 15 hidden channels, 16 TV guide grid
    var fromMode = 0; var fadeT0 = 0L
    var dimV = 0f; var dimAt = 0L   // guide logo dimming, animated
    // any change between two screens while the UI is up crossfades (drawScreens); opening it from the video slides it in (show)
    var mode = 0
        set(v) { if (v != field && field != 0 && v != 0 && visibility == View.VISIBLE && animOn()) { fromMode = field; fadeT0 = 0L }; if (v != field) bkF = v == 13; field = v }
    var sideLvl = 0; var sideFocus = 1; var catSel = 1; var plSel = 0   // sideLvl: 0 none, 1 categories, 2 playlists + categories
    var plGrab = false   // a playlist picked up (hold OK) to move it with UP / DOWN; OK drops it
    var plMenu = -1; var plAsk = -1   // the small cards beside a playlist (hold OK): Move / Remove / Cancel, then "Remove?"; the focused row, -1 = closed
    val panTop = HashMap<String, Int>(); val panSelY = HashMap<String, Float>()   // each side panel's first shown row, and where its selected row is (for the cards)
    var shiftD = -1f; var shiftAt = 0L   // the side panels' slide: where the lists start now, eased toward their place
    var side: Boolean
        get() = sideLvl > 0
        set(v) { if (!v) sideLvl = 0 }
    var sel = 0; var oi = 0; var col = 1; var dayI = 0; var progI = 0; var menuSel = 0
    var subI = 0; var subOpen = false; var oj = 0
    var setCat = -1   // Settings: -1 = the categories, else the category open (SET_CATS)
    val srch = SearchState()   // the Search page (Search.kt)
    var themeSel = 0; var setSel = 0; var grpSel = 0; var grpGrab = false; var logSel = 0; var remSel = 0; var remDay = 0; var hidSel = 0
    var infoRow = 0; var laterSel = 0   // Program Info: 0 = the buttons, 1 = the "Later on" shows (OK sets / removes a reminder)
    var infoCh: Ch? = null; var infoProg: Prog? = null; var backMode = 1; var infoBtn = 0; var infoBack = 1   // infoBack: the screen BACK returns to from Program Info
    var rbSel = 0; var rbSub = 0; var rbOpt = 0   // remote buttons: row, 0 list / 1 row menu / 2 waiting for a button, row menu choice
    var remCh: Ch? = null; var remProg: Prog? = null; var remBtn = 0; var remAt = 0L
    var selSince = SystemClock.uptimeMillis()
    val blur = Blur()
    init { isFocusable = true; isFocusableInTouchMode = true; alpha = UI_ALPHA }

    // every key press (and every screen change) restarts the idle timer; the screens close themselves after 30 s of nothing
    val idle = Runnable { if (mode != 0) close() }
    var quickHide = false   // channel settings shown by a channel change: hides after 5 s unless a tile is used
    var okDown = false      // OK is held down: the focused item draws pressed (MainActivity.dispatchKeyEvent)
    var okIdle = false     // program info opened with OK from the video: hides after 5 s until another button is pressed
    fun touch() { selSince = SystemClock.uptimeMillis(); removeCallbacks(idle); if (mode != 0 && mode != 11) postDelayed(idle, if ((quickHide || okIdle) && mode == 4) 5000L else 30000L) }
    fun rowH() = height * ROW_SCALE / 7.5f
    var tapK = 0; var tapN = 0; var tapAt = 0L   // quick-tap counter for the list's jump to top / bottom
    // list scroll: jd = how many rows the drawn list still lags behind the selection, springing to 0 (DarkShed's spring:
    // about 0.35 s, damping 0.9, like iOS). Capped at 30 rows so a long jump is a quick sweep, not hundreds of blurred rows.
    var jd = 0f; var jv = 0f; var jAt = 0L
    var jh = 0f; var jhv = 0f   // the channel list's highlight lag: it moves instead of the rows at the top end of the list
    // where the list sits for a selection: the first row on screen, and the highlight's row. The channel list starts at its
    // first channel instead of leaving empty rows above it (the quick guide keeps the highlight in its fixed slot)
    fun listTop(vp: Int) = vp - S   // the highlight and the buttons bar never move; the list goes round past its ends (see loops)
    fun jumpFrom(oldSel: Int) {
        val n = a.shown.size; val vp = { i: Int -> if (REVERSED) n - 1 - i else i }
        // adds to any slide still running and keeps its speed, so quick taps and holding the key flow into one motion
        if (!animOn()) { jd = 0f; jv = 0f; jh = 0f; jhv = 0f; return }
        if (jd == 0f && jh == 0f) jAt = 0L
        val o = vp(oldSel); var nw = vp(sel)
        if (loops()) { val p = n + 1; val d = Math.floorMod(nw - o, p); nw = o + if (d > p / 2) d - p else d }   // round the list the short way, over the divider
        val ot = listTop(o); val nt = listTop(nw)
        jd = (jd + ot - nt).coerceIn(-30f, 30f); jh = (jh + (o - ot) - (nw - nt)).coerceIn(-8f, 8f)
    }
    fun stepJump() {
        if (jd == 0f && jh == 0f) return
        val now = SystemClock.uptimeMillis(); val dt = if (jAt == 0L) 0f else minOf(0.032f, (now - jAt) / 1000f); jAt = now
        val w = (2 * Math.PI / 0.35).toFloat(); val k = w * w; val cd = 2 * 0.9f * w
        repeat(4) { jv += (-k * jd - cd * jv) * (dt / 4); jd += jv * (dt / 4); jhv += (-k * jh - cd * jhv) * (dt / 4); jh += jhv * (dt / 4) }
        if (Math.abs(jd) < 0.003f && Math.abs(jv) < 0.06f) { jd = 0f; jv = 0f }
        if (Math.abs(jh) < 0.003f && Math.abs(jhv) < 0.06f) { jh = 0f; jhv = 0f }
        if (jd != 0f || jh != 0f) postInvalidateOnAnimation()
    }
    fun wrap(i: Int): Int { val n = a.shown.size; return if (n == 0) 0 else ((i % n) + n) % n }
    fun chAt(i: Int): Ch? = a.shown.getOrNull(i)
    // the channel list and the quick guide go round: one divider slot (a round arrow) sits between the last and the first
    // channel, so there are no empty rows at the ends. vi = row in drawn order; short lists (< 8) don't go round, they'd repeat
    fun loops() = a.shown.size >= 8
    fun visCh(vi: Int): Ch? { val n = a.shown.size; val m = if (loops()) Math.floorMod(vi, n + 1) else vi; return if (m < 0 || m >= n) null else a.shown[if (REVERSED) n - 1 - m else m] }
    fun isSeam(vi: Int) = loops() && Math.floorMod(vi, a.shown.size + 1) == a.shown.size
    // the divider row: a thin line across the list where it stops, the round arrow in its middle (gap = room left for the arrow)
    fun seam(c: Canvas, x0: Float, x1: Float, cy: Float, gap: Float) {
        val cx = (x0 + x1) / 2; p.shader = null; p.style = Paint.Style.STROKE; p.strokeWidth = 2 * U; p.color = 0x46FFFFFF
        c.drawLine(x0, cy, cx - gap, cy, p); c.drawLine(cx + gap, cy, x1, cy, p)
        val r = 17 * U; p.strokeWidth = 4 * U; p.color = WHITE; c.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), 40f, 290f, false, p)
        p.style = Paint.Style.FILL; val a = Math.toRadians(330.0); val ex = cx + r * Math.cos(a).toFloat(); val ey = cy + r * Math.sin(a).toFloat(); val k = r * 0.55f
        c.drawPath(Path().apply { moveTo(ex + k * 0.15f, ey - k * 0.95f); lineTo(ex + k * 0.85f, ey + k * 0.35f); lineTo(ex - k * 0.6f, ey + k * 0.3f); close() }, p)
    }

    // ---------- drawing helpers ----------
    fun face(w: Int) = when { w >= 700 -> BOLD; w >= 500 -> MED; else -> REG }
    fun tsz(sz: Float) = sz * U * fs
    fun tw(s: String, sz: Float, w: Int = 400): Float { tp.textSize = tsz(sz); tp.typeface = face(w); return tp.measureText(s) }
    // text whose vertical centre is cy; accent = theme accent (the New Year gold is a shader)
    fun txt(c: Canvas, s: String, x: Float, cy: Float, maxW: Float, sz: Float, color: Int, w: Int = 400, align: Paint.Align = Paint.Align.LEFT, accent: Boolean = false) {
        tp.textSize = tsz(sz); tp.typeface = face(w); tp.textAlign = align
        if (accent) accentPaint(tp) else { tp.shader = null; tp.color = color }
        c.drawText(TextUtils.ellipsize(s, tp, maxOf(1f, maxW), TextUtils.TruncateAt.END).toString(), x, cy + tsz(sz) * 0.36f, tp)
        tp.shader = null
    }
    // like txt, but the selected item scrolls sideways (after 1 s without moving) when the text is too long
    fun stxt(c: Canvas, s: String, x: Float, cy: Float, maxW: Float, sz: Float, color: Int, w: Int, sel: Boolean) {
        val tw = tw(s, sz, w)
        if (!sel || tw <= maxW) { txt(c, s, x, cy, maxW, sz, color, w); return }
        val el = SystemClock.uptimeMillis() - selSince - 1000; val over = tw - maxW
        val off = if (el <= 0) 0f else minOf(over, el * 0.1f * U)
        tp.color = color; tp.shader = null; tp.textAlign = Paint.Align.LEFT
        c.save(); c.clipRect(x, cy - tsz(sz), x + maxW, cy + tsz(sz)); c.drawText(s, x - off, cy + tsz(sz) * 0.36f, tp); c.restore()
        if (off < over) postInvalidateOnAnimation() else if (el <= 0) tick(-el + 16)
    }
    fun fill(c: Canvas, x: Float, y: Float, w: Float, h: Float, color: Int) { p.shader = null; if (color == GOLDF && T.party) { sparkle(c) { c.drawRect(x, y, x + w, y + h, it) }; return }; p.color = color; c.drawRect(x, y, x + w, y + h, p) }
    fun rr(c: Canvas, x0: Float, y0: Float, w: Float, h: Float, r: Float, color: Int) {
        val g = (gDx != 0f || gDy != 0f) && gOff(x0, y0, w); val x = if (g) x0 + gDx else x0; val y = if (g) y0 + gDy else y0; p.shader = null; p.style = Paint.Style.FILL
        if (color == GOLDF && T.party) { goldRR.set(x, y, x + w, y + h); sparkle(c) { c.drawRoundRect(goldRR, r, r, it) }; return }
        p.color = color; c.drawRoundRect(RectF(x, y, x + w, y + h), r, r, p)
    }
    // New Year focus: the shimmering gold, a fine grain over it and glitter specks that twinkle (two sets taking turns)
    private val goldRR = RectF(); private var grain: android.graphics.BitmapShader? = null; private val glit = arrayOfNulls<android.graphics.BitmapShader>(2); private val gM = Matrix()
    private fun noiseTile(seed: Long, specks: Boolean): android.graphics.BitmapShader {
        val n = 96; val px = IntArray(n * n); val r = java.util.Random(seed)
        if (specks) repeat(26) { val i = r.nextInt(n * n); val a = 150 + r.nextInt(105); px[i] = (a shl 24) or 0xFFFFFF
            if (r.nextInt(3) == 0) for (o in intArrayOf(1, -1, n, -n)) { val j = i + o; if (j in px.indices) px[j] = ((a / 3) shl 24) or 0xFFF4D0 } }   // a few bigger, softer ones
        else for (i in px.indices) { val v = r.nextInt(255); px[i] = if (v > 127) ((v - 127) / 4 shl 24) or 0xFFFFFF else ((127 - v) / 3 shl 24) }
        return android.graphics.BitmapShader(Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }
    fun sparkle(c: Canvas, shape: (Paint) -> Unit) {
        accentPaint(p); shape(p)
        val g = grain ?: noiseTile(7, false).also { grain = it }
        val t = if (animOn()) SystemClock.uptimeMillis() / 1000f else 0f
        gM.setScale(U * 1.4f, U * 1.4f); g.setLocalMatrix(gM); p.shader = g; p.color = -1; p.alpha = 255; shape(p)
        for (k in 0..1) {
            val s = glit[k] ?: noiseTile(11L + k, true).also { glit[k] = it }
            gM.setScale(U * 1.6f, U * 1.6f); gM.postTranslate(k * 37 * U + t * 6 * U, k * 23 * U); s.setLocalMatrix(gM)
            p.shader = s; p.alpha = ((0.5f + 0.5f * Math.sin(t * 3.1 + k * Math.PI).toFloat()) * 255).toInt(); shape(p)
        }
        p.shader = null; p.alpha = 255
    }
    fun outline(c: Canvas, x0: Float, y0: Float, w: Float, h: Float, r: Float, col: Int = OUTLINE, sw: Float = 2f) {
        val g = (gDx != 0f || gDy != 0f) && gOff(x0, y0, w); val x = if (g) x0 + gDx else x0; val y = if (g) y0 + gDy else y0; p.shader = null; p.style = Paint.Style.STROKE; p.strokeWidth = sw * U; p.color = col
        c.drawRoundRect(RectF(x + U, y + U, x + w - U, y + h - U), r, r, p); p.style = Paint.Style.FILL
    }
    fun icon(c: Canvas, k: String, cx: Float, cy: Float, sz: Float, color: Int, accent: Boolean = false) {
        if (accent) accentPaint(p) else { p.shader = null; p.color = color }
        drawIcon(c, k, cx, cy, sz, p); p.shader = null
    }

    // accent colour; New Year = polished gold whose highlight sweeps across the screen every 4 s (still when animations are off)
    val goldM = Matrix(); var goldSh: LinearGradient? = null; var goldW = 0
    // one delayed redraw at a time: asking for one on every frame used to start a new chain each frame, and they piled up
    private var tickAt = 0L
    private val tickR = Runnable { tickAt = 0L; invalidate() }
    fun tick(ms: Long) { val t = SystemClock.uptimeMillis() + ms; if (tickAt != 0L && tickAt <= t) return; removeCallbacks(tickR); tickAt = t; postDelayed(tickR, ms) }
    // the system animation setting, read once a second at most (it is a settings lookup, and it was asked many times a frame)
    private var animAt = 0L; private var animV = true
    fun animOn(): Boolean { val n = SystemClock.uptimeMillis(); if (n - animAt > 1000) { animAt = n; animV = animOn0() }; return animV }
    fun animOn0() = try { android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f } catch (e: Exception) { true }
    fun accentPaint(pt: Paint) {
        if (!T.gold) { pt.shader = null; pt.color = T.accent; return }
        if (goldSh == null || goldW != width) {
            goldW = width
            goldSh = LinearGradient(0f, 0f, 600 * U, 210 * U,
                intArrayOf(0xFF9A7425.toInt(), 0xFFD4A940.toInt(), 0xFFFFF3C4.toInt(), 0xFFE8C66A.toInt(), 0xFFB8862B.toInt(), 0xFF9A7425.toInt()),
                floatArrayOf(0f, 0.22f, 0.36f, 0.5f, 0.75f, 1f), Shader.TileMode.MIRROR)
        }
        val ph = if (animOn()) (SystemClock.uptimeMillis() % 4000L) / 4000f else 0.3f
        goldM.setTranslate(ph * 1200 * U, ph * 420 * U);   // along the gradient, exactly one mirrored repeat (2 x 600,210): the loop has no seam
        goldSh!!.setLocalMatrix(goldM); pt.color = -1; pt.shader = goldSh
    }

    // Apple material: the blurred, dimmed video under the shape, then the theme's tint. Without blur: the tint nearly solid
    // the view whose onDraw is running (Ui, Banner or CatchupBar): mat() records its shapes for BlurLayer under them
    fun beginShapes(v: View) { blur.cur = blur.shapes.getOrPut(v) { BlurShapes() }.also { it.n = 0 }; a.blurLayer.invalidate() }
    val mPath = Path(); var matOx = 0f   // how far the canvas is shifted right while drawing (lists move right when side panels open)
    fun mat(c: Canvas, path: Path, tint: Int = T.panel) {
        if (T.fx && !snowSet) { path.computeBounds(snowR, true); a.snow.card(snowR.left + matOx, snowR.top, snowR.width(), snowR.height(), CR) }   // + the canvas shift
        snowSet = false   // snow piles on every card's top
        val b = blur.out
        if (!a.seeThru) p.color = withAlpha(tint, 0xFF)
        else if (b != null && a.blurOn) {
            blur.cur?.add(path, matOx)   // the blur itself is painted by BlurLayer underneath
            p.color = withAlpha(tint, (T.alpha * 255).toInt())
        } else p.color = withAlpha(tint, 0xED)
        p.shader = null; p.style = Paint.Style.FILL; c.drawPath(path, p)
    }
    private val snowR = RectF(); private var snowSet = false   // snowSet: the caller already told the snow layer the card's real shape
    fun snowCard(x: Float, y: Float, w: Float, h: Float, r: Float) { if (T.fx) { a.snow.card(x + matOx, y, w, h, r); snowSet = true } }
    fun matRect(c: Canvas, x: Float, y: Float, w: Float, h: Float, r: Float) {
        snowCard(x, y, w, h, r)
        mPath.reset(); mPath.addRoundRect(RectF(x, y, x + w, y + h), r, r, Path.Direction.CW); mat(c, mPath)
    }
    // floating glass card (main menu, channel list, side panels, guide): 36 px from the screen edges, 40 px corners,
    // a faint light edge so it reads as a separate layer over the video
    val PT: Float get() = 100 * U   // top of every menu card and page (main menu, Settings, Remote Buttons, Reminders...): they all open at the same height
    val FR: Float get() = 22 * U   // corner radius of every focus highlight, tile and button (Apple TV-like round corners)
    val M: Float get() = 36 * U; val GAP: Float get() = 20 * U; val CR: Float get() = 40 * U
    fun glass(c: Canvas, x: Float, y: Float, w: Float, h: Float, r: Float = CR) {
        matRect(c, x, y, w, h, r)
        p.shader = null; p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * U; p.color = 0x1AFFFFFF
        c.drawRoundRect(RectF(x + 0.75f * U, y + 0.75f * U, x + w - 0.75f * U, y + h - 0.75f * U), r, r, p); p.style = Paint.Style.FILL
    }
    // rows that scroll inside a card: clipped to it and faded out over the top and bottom 44 px (end with endScroll)
    val fadeOut = Paint().apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT) }
    val scrollR = RectF()
    fun beginScroll(c: Canvas, x: Float, y: Float, w: Float, h: Float) {
        scrollR.set(x, y, x + w, y + h); c.saveLayer(scrollR, null)
        val cp = Path(); cp.addRoundRect(scrollR, CR, CR, Path.Direction.CW); c.clipPath(cp)
    }
    fun endScroll(c: Canvas) {
        val f = 44 * U; val r = scrollR
        fadeOut.shader = LinearGradient(0f, r.top, 0f, r.top + f, 0xFF000000.toInt(), 0, Shader.TileMode.CLAMP); c.drawRect(r.left, r.top, r.right, r.top + f, fadeOut)
        fadeOut.shader = LinearGradient(0f, r.bottom - f, 0f, r.bottom, 0, 0xFF000000.toInt(), Shader.TileMode.CLAMP); c.drawRect(r.left, r.bottom - f, r.right, r.bottom, fadeOut)
        fadeOut.shader = null; c.restore()
    }
    // a card with a popover pointer on its left, aimed at the selected row (centre y = py)
    fun matCard(c: Canvas, x: Float, y: Float, w: Float, h: Float, py: Float) {
        snowCard(x, y, w, h, FR + 12 * U)   // the card without its pointer
        mPath.reset(); mPath.addRoundRect(RectF(x, y, x + w, y + h), FR + 12 * U, FR + 12 * U, Path.Direction.CW)   // button radius FR + padding 12: concentric corners
        val tri = Path(); tri.moveTo(x + 2 * U, py - 36 * U); tri.lineTo(x - 27 * U, py); tri.lineTo(x + 2 * U, py + 36 * U); tri.close()
        mPath.op(tri, Path.Op.UNION); mat(c, mPath)   // no outline: the selected row's outline alone shows where focus is
    }
    // the same card with the pointer underneath, aimed at the tile it belongs to (centre x = px)
    fun matCardDown(c: Canvas, x: Float, y: Float, w: Float, h: Float, px: Float) {
        snowCard(x, y, w, h, FR + 12 * U)
        mPath.reset(); mPath.addRoundRect(RectF(x, y, x + w, y + h), FR + 12 * U, FR + 12 * U, Path.Direction.CW)
        val tri = Path(); tri.moveTo(px - 36 * U, y + h - 2 * U); tri.lineTo(px, y + h + 27 * U); tri.lineTo(px + 36 * U, y + h - 2 * U); tri.close()
        mPath.op(tri, Path.Op.UNION); mat(c, mPath)
    }
    fun progress(c: Canvas, x: Float, y: Float, w: Float, pr: Prog, h: Float = 5f) {
        val f = ((System.currentTimeMillis() - pr.start).toFloat() / maxOf(1L, pr.stop - pr.start)).coerceIn(0f, 1f)
        rr(c, x, y, w, h * U, h * U / 2, TRACK)
        if (T.gold) { accentPaint(p); c.drawRoundRect(RectF(x, y, x + w * f, y + h * U), h * U / 2, h * U / 2, p); p.shader = null }
        else rr(c, x, y, w * f, h * U, h * U / 2, BARC)
    }
    fun minLeft(pr: Prog) = ((pr.stop - System.currentTimeMillis() + 59_999L) / 60_000L).coerceAtLeast(0)
    fun isLive(pr: Prog): Boolean { val n = System.currentTimeMillis(); return n >= pr.start && n < pr.stop }
    // LIVE badge; returns its width
    fun live(c: Canvas, x: Float, cy: Float, sz: Float): Float {
        val w = tw("LIVE", sz, 700) + 18 * U; val h = sz * U * 1.55f
        rr(c, x, cy - h / 2, w, h, 6 * U, LIVE_RED); txt(c, "LIVE", x + 9 * U, cy, w, sz, WHITE, 700); return w
    }
    // "Update" capsule at the right end of the Settings row, like a tvOS/iOS notification badge, in the theme's colour; returns its width
    fun updBadge(c: Canvas, right: Float, cy: Float, on: Boolean): Float {
        val w = tw("Update", 22f, 700) + 28 * U; val h = 38 * U; val acc = T.accent or 0xFF000000.toInt()
        val light = (0.299f * Color.red(acc) + 0.587f * Color.green(acc) + 0.114f * Color.blue(acc)) > 140   // yellow, orange, teal, green, gold: dark text
        // on a coloured focus (Christmas, Halloween, New Year's gold) the badge would vanish, so it flips: white (gold: dark) with the colour as text
        val (bg, fg) = when {
            on && T.party -> 0xFF1C1C1E.toInt() to acc
            on && T.redFocus -> WHITE to acc
            T.party -> GOLDF to 0xFF1C1C1E.toInt()
            else -> acc to (if (light) 0xFF1C1C1E.toInt() else WHITE)
        }
        rr(c, right - w, cy - h / 2, w, h, h / 2, bg)
        txt(c, "Update", right - w / 2, cy, w, 22f, fg, 700, Paint.Align.CENTER); return w
    }
    fun tile(c: Canvas, ch: Ch, x: Float, y: Float, w: Float, h: Float, bg: Int = FILL) { rr(c, x, y, w, h, 10 * U, bg); drawLogo(c, ch, x, y, w, h) }
    fun drawLogo(c: Canvas, ch: Ch, x: Float, y: Float, w: Float, h: Float) {
        val b = a.logoOf(ch)
        if (b == null) { txt(c, ch.name, x + w / 2, y + h / 2, w - 12 * U, 22f, WHITE, 700, Paint.Align.CENTER); return }
        val s = minOf(w * 0.76f / b.width, h * 0.76f / b.height); val bw = b.width * s; val bh = b.height * s
        p.shader = null; p.color = -1; c.drawBitmap(b, null, RectF(x + (w - bw) / 2, y + (h - bh) / 2, x + (w + bw) / 2, y + (h + bh) / 2), p)
    }
    // the channel that is playing: a small red dot inside the top-right corner of its logo box (list and guide)
    fun hdr(c: Canvas, s: String, x: Float, cy: Float, maxW: Float) {
        tp.letterSpacing = 0.05f; txt(c, s.uppercase(), x, cy, maxW, 22f, TER, 600); tp.letterSpacing = 0f
    }
    fun split2(s: String, maxW: Float, sz: Float, w: Int): List<String> {
        if (tw(s, sz, w) <= maxW) return listOf(s)
        val i = s.lastIndexOf(' ', s.length / 2 + 2).let { if (it <= 0) s.indexOf(' ') else it }
        return if (i <= 0) listOf(s) else listOf(s.substring(0, i), s.substring(i + 1))
    }
    // wraps text into a box of this size; what does not fit is cut at the last whole line and ends in "..."
    fun fitText(s: String, wPx: Int, hPx: Float, spacing: Float): StaticLayout {
        var sl = StaticLayout(s, tp, wPx, Layout.Alignment.ALIGN_NORMAL, spacing, 0f, false)
        val lh = sl.getLineBottom(0).toFloat(); val fit = maxOf(1, (hPx / lh).toInt())
        if (sl.lineCount > fit) {
            val t = s.substring(0, sl.getLineEnd(fit - 1)).trimEnd().dropLast(2).trimEnd() + "…"
            sl = StaticLayout(t, tp, wPx, Layout.Alignment.ALIGN_NORMAL, spacing, 0f, false)
        }
        return sl
    }
    fun para(c: Canvas, s: String, x: Float, y: Float, w: Float, h: Float, sz: Float, color: Int, spacing: Float = 1.2f) {
        tp.textSize = tsz(sz); tp.typeface = REG; tp.color = color; tp.shader = null; tp.textAlign = Paint.Align.LEFT
        val sl = fitText(s, w.toInt(), h, spacing); c.save(); c.translate(x, y); sl.draw(c); c.restore()
    }
    // OK held = the focused item sinks most of the way back toward the page and its shadow shrinks, then springs up with a
    // small bounce when let go (tvOS press). pressV runs 0..1 on a slightly underdamped spring, stepped once per frame in onDraw
    var pressV = 0f; var pressVel = 0f; var pressAt = 0L
    fun stepPress(now: Long) {
        val t = if (okDown) 1f else 0f
        if (!animOn()) { pressV = t; pressVel = 0f; pressAt = now; return }
        val dt = if (pressAt == 0L) 0f else minOf(0.032f, (now - pressAt) / 1000f); pressAt = now
        pressVel += (900f * (t - pressV) - 42f * pressVel) * dt; pressV += pressVel * dt
        if (Math.abs(t - pressV) < 0.002f && Math.abs(pressVel) < 0.05f) { pressV = t; pressVel = 0f } else postInvalidateOnAnimation()
    }
    // the focused item grows a little (tvOS focus); draw it inside this
    // seasonal themes have no shadow: on their tinted menus it showed as a dark smudge (neutral themes hide it)
    // base = the item's own unfocused fill: kept under it while the highlight is still sliding there (button bars lost their grey)
    inline fun lift(c: Canvas, on: Boolean, x: Float, y: Float, w: Float, h: Float, sc: Float, px: Float = x + w / 2, base: Int = 0, body: () -> Unit) {
        if (on) { glide(x, y, w, h); val dx = gDx; val dy = gDy; gDx = 0f; gDy = 0f
            if (base != 0 && (dx != 0f || dy != 0f)) rr(c, x, y, w, h, FR, base)
            c.save(); val s = 1f + (sc - 1f) * gK - (sc - 1f) * 0.7f * pressV; c.scale(s, s, px + dx, y + dy + h / 2)
            if (!T.seasonal) rr(c, x + dx + 2 * U, y + dy + 10 * U, w - 4 * U, h, FR, ((0x40 * (1f - 0.7f * pressV.coerceIn(0f, 1f))).toInt() shl 24))
            gRx = x; gRy = y; gRw = w; gDx = dx; gDy = dy }
        body(); if (on) { c.restore(); gDx = 0f; gDy = 0f }
    }
    // focus glide: when focus moves to another item of the same list or button row, the highlight slides there from the old one
    // (critically damped spring: quick start, soft stop, no bounce) instead of jumping; the labels stay put.
    // Pressing quickly never leaves it more than about one item behind: it speeds up to keep up, like tvOS.
    // Same x + width = a vertical list, same y + height = a row of buttons; anything else is a new place and does not slide.
    // While it slides, rr/outline calls on the focused item's own rect are drawn at the moving position (gDx, gDy).
    var gMode = -1; var gX = 0f; var gY = 0f; var gW = 0f; var gH = 0f; var gAt = 0L
    var gOx = 0f; var gOy = 0f; var gChain = false; var gN = 0; var gStepAt = 0L
    var gFull = 0f; var gK = 1f   // distance the current slide started from; how far it has come (0..1), so the item grows as the highlight arrives
    var gRx = 0f; var gRy = 0f; var gRw = 0f; var gDx = 0f; var gDy = 0f
    fun glide(x: Float, y: Float, w: Float, h: Float) {
        val now = SystemClock.uptimeMillis(); val m = mode * 100 + setCat
        val col = Math.abs(x - gX) < 1f && Math.abs(w - gW) < 1f; val row = Math.abs(y - gY) < 1f && Math.abs(h - gH) < 1f
        val before = Math.hypot(gOx.toDouble(), gOy.toDouble())
        if (!animOn() || m != gMode || !(col || row)) { gOx = 0f; gOy = 0f; gChain = false }
        else {   // a step to another item (small moves = the list's own scrolling animation, followed as is)
            if (col && Math.abs(y - gY) > h * 0.5f) { gOy = (gOy + gY - y).coerceIn(-1.6f * h, 1.6f * h); gAt = now - 16 }   // moves on this frame already
            if (row && Math.abs(x - gX) > w * 0.5f) { gOx = (gOx + gX - x).coerceIn(-1.6f * w, 1.6f * w); gAt = now - 16 }
        }
        gMode = m; gX = x; gY = y; gW = w; gH = h
        // a new step: from rest the item grows as the highlight arrives; pressed again while moving, it stays big (no throb per key)
        val d0 = Math.hypot(gOx.toDouble(), gOy.toDouble()).toFloat(); if (d0 > before + 0.5) { gChain = before > 0.5; gN = if (now - gStepAt < 320) minOf(gN + 1, 5) else 0; gStepAt = now; gFull = d0 }   // gN: presses in quick succession
        if (gOx == 0f && gOy == 0f) { gAt = 0L; gDx = 0f; gDy = 0f; gK = 1f; return }
        val dt = if (gAt == 0L) 0.016f else minOf(0.05f, (now - gAt) / 1000f); gAt = now
        // starts at full speed and slows as it lands (speed = distance left), so a press while moving just carries on:
        // held or fast presses glide in one smooth motion instead of stopping at every item. 90 % there: keys 0.12 s, rows 0.22 s
        val tau = (if (h < 80 * U) 0.052f else 0.095f) / (1f + 0.3f * gN);   // each quick press after another is faster, up to 2.5x
        val f = Math.exp((-dt / tau).toDouble()).toFloat()
        gOx *= f; gOy *= f
        if (Math.abs(gOx) < 0.5f) gOx = 0f
        if (Math.abs(gOy) < 0.5f) gOy = 0f
        if (gOx == 0f && gOy == 0f) gAt = 0L else postInvalidateOnAnimation()
        gDx = gOx; gDy = gOy
        gK = if (gChain) 1f else if (gFull > 0f) (1f - Math.hypot(gOx.toDouble(), gOy.toDouble()).toFloat() / gFull).coerceIn(0f, 1f) else 1f
    }
    // logo box in lists with a sliding highlight: one solid colour whether focused or not, so nothing changes colour or shows through
    // while the highlight passes (a fade tied to the slide looked washed out on the red highlight)
    val LOGO_BG = 0xFF2C2C2E.toInt()
    fun gOff(x: Float, y: Float, w: Float) = Math.abs(x - gRx) < 1f && Math.abs(y - gRy) < 1f && Math.abs(w - gRw) < 1f
    // white focus pill (or a faint one) with an icon and a label, used for every button row
    fun button(c: Canvas, x: Float, y: Float, w: Float, h: Float, on: Boolean, ic: String?, label: String, sz: Float = 29f, sc: Float = 1.06f) {
        lift(c, on, x, y, w, h, sc, base = 0x24FFFFFF) {
            rr(c, x, y, w, h, FR, if (on) FOC else 0x24FFFFFF)
            val col = if (on) ONF else WHITE; val lw = tw(label, sz, 600); val iw = if (ic != null) 30 * U + 14 * U else 0f
            val sx = x + (w - lw - iw) / 2
            if (ic != null) icon(c, ic, sx + 15 * U, y + h / 2, 30 * U, col)
            txt(c, label, sx + iw, y + h / 2, w - iw, sz, col, 600)
        }
    }
    // the option buttons: equal size, icon above a label that may wrap to two lines; the focused one is white and a bit bigger
    val fadeP = Paint()
    fun optButtons(c: Canvas, x: Float, y: Float, maxW: Float, opts: List<Pair<String, String>>, focus: Int) {
        val pitch = 176 * U; val bw = 166 * U; val bh = rowH() - 30 * U   // the card around them: 12 px padding on every side
        val full = opts.size * pitch + 14 * U; val off = maxOf(0f, minOf(full - maxW, (focus + 1) * pitch + 14 * U - maxW))
        c.save(); c.clipRect(x, y - 20 * U, x + maxW, y + rowH() + 20 * U)
        val moreR = off + maxW < full - 1f; val moreL = off > 0.5f
        for ((k, o) in opts.withIndex()) {
            val bx = x + 12 * U + k * pitch - off; val by = y + 14 * U; val on = k == focus
            if (bx > x + maxW) break
            // the end buttons grow inwards, so a focused one keeps the card's 12 px padding (growing from the middle ate it)
            val px = when (k) { 0 -> bx; opts.lastIndex -> bx + bw; else -> bx + bw / 2 }
            lift(c, on, bx, by, bw, bh, 1.08f, px, FILL) {
                rr(c, bx, by, bw, bh, FR, if (on) FOC else FILL)
                val col = if (on) ONF else WHITE
                // positions follow the button height, so two-line labels stay inside the button at any row size / font size
                val ls = split2(o.first, bw - 16 * U, 23f, 600)
                if (ls.size == 1) { icon(c, o.second, bx + bw / 2, by + bh * 0.32f, 32 * U, col); txt(c, ls[0], bx + bw / 2, by + bh * 0.74f, bw - 12 * U, 23f, col, 600, Paint.Align.CENTER) }
                else {
                    icon(c, o.second, bx + bw / 2, by + bh * 0.22f, 26 * U, col)
                    txt(c, ls[0], bx + bw / 2, by + bh * 0.56f, bw - 12 * U, 22f, col, 600, Paint.Align.CENTER); txt(c, ls[1], bx + bw / 2, by + bh * 0.80f, bw - 12 * U, 22f, col, 600, Paint.Align.CENTER)
                }
            }
        }
        // dark fade where buttons are cut off, so it is clear there are more (inside the card's rounded edge)
        val fw = 110 * U; val fy0 = y + 2 * U; val fy1 = y + rowH() - 4 * U
        if (moreR || moreL) { val cp = Path(); cp.addRoundRect(RectF(x, fy0, x + maxW, fy1), FR + 12 * U, FR + 12 * U, Path.Direction.CW); c.clipPath(cp) }
        if (moreR) { fadeP.shader = LinearGradient(x + maxW - fw, 0f, x + maxW, 0f, 0, 0xB3000000.toInt(), Shader.TileMode.CLAMP); c.drawRect(x + maxW - fw, fy0, x + maxW, fy1, fadeP) }
        if (moreL) { fadeP.shader = LinearGradient(x, 0f, x + fw, 0f, 0xB3000000.toInt(), 0, Shader.TileMode.CLAMP); c.drawRect(x, fy0, x + fw, fy1, fadeP) }
        c.restore()
    }
    // status pills at the bottom: time · date · playlist on the left, category · count on the right
    fun status(c: Canvas, x: Float, left: List<String>, right: List<String>?) {
        val ph = 58 * U; val py = height - M - ph; val sep = "   ·   "
        fun pill(px: Float, parts: List<String>, firstBold: Boolean): Float {
            var w = 56 * U; parts.forEachIndexed { i, s -> w += tw(s, 25f, if (i == 0 && firstBold) 600 else 500) + (if (i > 0) tw(sep, 25f) else 0f) }
            val x0 = if (px < 0) width - matOx - M - w else px   // 36 px from the screen's right edge, whatever the list's shift
            matRect(c, x0, py, w, ph, ph / 2); p.style = Paint.Style.STROKE; p.strokeWidth = U; p.color = HAIR; c.drawRoundRect(RectF(x0, py, x0 + w, py + ph), ph / 2, ph / 2, p); p.style = Paint.Style.FILL
            var tx = x0 + 28 * U
            parts.forEachIndexed { i, s ->
                if (i > 0) { txt(c, sep, tx, py + ph / 2, 999f, 25f, TER); tx += tw(sep, 25f) }
                val b = i == 0 && firstBold; txt(c, s, tx, py + ph / 2, 999f, 25f, if (b) WHITE else SEC, if (b) 600 else 500); tx += tw(s, 25f, if (b) 600 else 500)
            }
            return w
        }
        pill(x + GAP, left.filter { it.isNotEmpty() }, true)
        if (right != null) pill(-1f, right, false)
    }
    // 3rd chip of the status pill: favorites and search mix playlists, so show where the selected channel comes from; otherwise the active playlist
    fun srcName(ch: Ch?): String =
        if (a.multi()) (ch?.let { k -> a.sources.find { it.id == k.src }?.name } ?: "") else (a.sources.getOrNull(a.act)?.name ?: "")

    fun days() = a.days()
    // the show on at the same clock time on the chosen day (today: the live one), else the next one; the first show of another
    // day is often last night's, which made Tomorrow look like today's evening
    fun defProg(): Int {
        val ch = a.shown.getOrNull(sel) ?: return 0; val dys = days(); val day = dys[dayI.coerceIn(0, dys.lastIndex)]
        val t = day + (System.currentTimeMillis() - a.dayStart()); val l = a.dayProgs(ch, day)
        val k = l.indexOfFirst { t >= it.start && t < it.stop }; if (k >= 0) return k
        return l.indexOfFirst { it.start >= t }.let { if (it >= 0) it else maxOf(0, l.lastIndex) }
    }
    fun reset(keep: Boolean) {
        val k = a.shown.indexOf(a.cur); val mx = maxOf(0, a.shown.size - 1)
        sel = if (keep) sel.coerceIn(0, mx) else if (k >= 0) k else 0
        dayI = a.todayIdx(); progI = defProg(); touch(); invalidate()
    }
    fun show(m: Int) {
        if (visibility != View.VISIBLE) blur.shapes[this]?.n = 0   // forget the last menu's blur shapes, or BlurLayer flashes them for one frame
        // a screen change while up crossfades (the mode setter); coming up from the video it slides in a little and fades in
        val appear = visibility != View.VISIBLE || outMode != 0; if (appear) { fromMode = 0; outMode = 0 }   // reopened while sliding out: comes back in
        mode = m; side = false; visibility = View.VISIBLE; a.banner.visibility = View.GONE; touch(); requestFocus(); blur.start()
        if (appear) { shiftD = -1f; slideIn(m) }
        a.pip.place()
    }
    // Apple-like entrance: 260 ms, fast start and a long soft stop; the view and its blur move together (both are GPU layers, no redraws)
    fun slideIn(m: Int) {
        val dx = when (m) { 4 -> 0f; 11 -> 30 * U; else -> -30 * U }; val dy = if (m == 4) 30 * U else 0f
        for ((v, to) in listOf(this to a.uiAlpha(), a.blurLayer to 1f)) {
            v.animate().cancel()
            if (!animOn()) { v.alpha = to; v.translationX = 0f; v.translationY = 0f; continue }
            v.alpha = 0f; v.translationX = dx; v.translationY = dy
            v.animate().alpha(to).translationX(0f).translationY(0f).setDuration(320).setInterpolator(EASE).start()
        }
    }
    // the way back: the last screen keeps being drawn (outMode, keys already go to the video) as it slides and fades away
    var outMode = 0
    fun slideOut(m: Int) {
        val dx = when (m) { 4 -> 0f; 11 -> 30 * U; else -> -30 * U }; val dy = if (m == 4) 30 * U else 0f
        outMode = m
        for (v in listOf(this, a.blurLayer)) {
            v.animate().cancel()
            v.animate().alpha(0f).translationX(dx).translationY(dy).setDuration(240).setInterpolator(EASE_IN).withEndAction {
                if (v === this) { if (outMode != 0 && mode == 0) visibility = View.GONE; outMode = 0 }
                if (mode == 0) { v.translationX = 0f; v.translationY = 0f; if (v === a.blurLayer) v.alpha = 1f }
            }.start()
        }
    }
    fun open(m: Int) { dimAt = 0L; oi = 0; col = 1; cardY = -1f; show(m); reset(false) }
    fun openGuide(ch: Ch) { open(2); val k = a.shown.indexOf(ch); if (k >= 0) { sel = k; progI = defProg(); invalidate() } }
    fun openMenu() { menuSel = 0; show(3); invalidate() }
    fun openThemes() { themeSel = a.themeIdx; show(6); invalidate() }
    fun openSettings() { setCat = -1; setSel = 0; show(8); invalidate() }
    fun openFavGroups() { grpSel = 0; grpGrab = false; show(9); invalidate() }
    fun openKeys() { rbSel = 0; rbSub = 0; show(12); invalidate() }
    fun openLog() { logSel = 0; show(10); invalidate() }
    fun openAbout() { show(13); invalidate() }
    fun openSub() { quickHide = false; backMode = if (mode == 2) 2 else 1; subI = 0; subOpen = false; show(4); invalidate() }
    // from the player (OK, or a channel change): Back closes it, and UP/DOWN keep changing channels while no list is open
    fun openSubPlayer(quick: Boolean) {
        if (mode == 4 && backMode == 0) { if (!quick) { quickHide = false; okIdle = true }; touch(); invalidate(); return }   // already up: just refresh for the new channel
        quickHide = quick; okIdle = !quick; backMode = 0; subI = -3; subOpen = false; show(4); touch(); invalidate()
    }
    fun openInfo(ch: Ch, pr: Prog?) { infoRow = 0; laterSel = 0; infoBack = if (mode == 4 || mode == 2 || mode == 14 || mode == 16 || mode == 17) mode else 1; infoCh = ch; infoProg = pr; infoBtn = 0; show(5); invalidate() }
    // "Starting now" card for a reminder (replaces whatever screen was open); it closes itself after 20 s
    val remHide = Runnable { if (mode == 11) close() }
    fun showReminder(ch: Ch, pr: Prog) {
        remCh = ch; remProg = pr; remBtn = 0; remAt = SystemClock.uptimeMillis(); show(11); removeCallbacks(remHide); postDelayed(remHide, 20_000L); invalidate()
    }
    fun close() {
        if (mode == 6) a.setTheme(a.themeIdx, false)   // leaving the theme list without pressing OK: drop the preview
        a.settingsDone()
        val was = mode
        grpGrab = false; plGrab = false; plMenu = -1; plAsk = -1; mode = 0; sideLvl = 0; removeCallbacks(idle); removeCallbacks(remHide); a.snow.close(); if (a.searching && a.searchQ == null) a.endSearch()   // a search being zapped through stays until a category is picked
        if (animOn() && visibility == View.VISIBLE && was != 0) slideOut(was) else visibility = View.GONE
        a.pip.place()
    }
    // side panels: Favorites is its own entry above the playlists (row 0); its categories are the favorite groups.
    // The playlists' own category lists leave out Favorites (groups[0]), so their rows are groups shifted by one.
    fun plItems() = listOf("★ Favorites") + a.sources.map { it.name }
    fun plActive() = if (a.grp == 0) 0 else a.act + 1
    fun catItems() = if (a.grp == 0) listOf("All Favorites") + a.favGroupNames().map { it.second } else a.groups.drop(1)
    fun catActive() = if (a.grp == 0) (a.favGroupNames().indexOfFirst { it.first == a.favGrp } + 1) else maxOf(0, a.grp - 1)
    fun openSide(pl: Boolean = false) {
        sideLvl = if (pl) 2 else 1; sideFocus = if (pl) 2 else 1
        catSel = catActive(); plSel = plActive(); touch(); if (pl) plTip()
    }
    // the first 3 times the Playlists panel opens, a notice says what holding OK does there
    fun plTip() { val n = a.prefs.getInt("plTip", 0); if (n < 3 && a.sources.isNotEmpty()) { a.prefs.edit().putInt("plTip", n + 1).apply(); a.flash("Playlists: hold OK on a playlist to move or remove it", 4000) } }

    override fun onDraw(c: Canvas) { beginShapes(this); drawScreens(c) }
    override fun onVisibilityChanged(v: View, vis: Int) { super.onVisibilityChanged(v, vis); @Suppress("SENSELESS_COMPARISON") if (a != null) a.blurLayer.invalidate() }   // a is still null while View's constructor runs
    fun drawMode(c: Canvas, m: Int, w: Float, h: Float, shift: Float) {
        when (m) {
            1 -> { c.save(); c.translate(shift, 0f); matOx = shift; drawChannels(c, w - shift, h); matOx = 0f; c.restore() }
            2 -> { val g = shift - M; c.save(); c.translate(g, 0f); matOx = g; drawGuide(c, w - g, h); matOx = 0f; c.restore() }   // the guide sits at 0 and moves only for the panels
            3 -> drawMenu(c, w, h)
            4 -> { drawSub(c, w, h); tick(1000) }
            5 -> drawInfo(c, w, h)
            6 -> { c.save(); c.translate(M, 0f); matOx = M; drawThemes(c, w - M, h); matOx = 0f; c.restore() }
            8 -> { c.save(); c.translate(M, 0f); matOx = M; drawSettings(c, w - M, h); matOx = 0f; c.restore() }
            9 -> drawFavGroups(c, w, h)
            10 -> drawErrLog(c, w, h)
            12 -> { c.save(); c.translate(M, 0f); matOx = M; drawKeys(c, w - M, h); matOx = 0f; c.restore() }
            13 -> { c.save(); c.translate(M, 0f); matOx = M; drawAbout(c, w - M, h); matOx = 0f; c.restore() }
            14 -> drawReminders(c, w, h)
            15 -> drawHidden(c, w, h)
            16 -> drawGrid(c, w, h)
            17 -> drawSearch(c, w, h)
            11 -> { drawReminder(c, w); tick(250) }
        }
    }
    val EASE = android.view.animation.PathInterpolator(0.2f, 0.8f, 0.2f, 1f)
    val EASE_IN = android.view.animation.PathInterpolator(0.4f, 0f, 0.9f, 0.6f)   // leaving: starts gently, gone quickly
    fun drawScreens(c: Canvas) {
        U = width / 1920f
        if (mode == 0) { if (outMode != 0) { val w0 = width.toFloat(); val h0 = height.toFloat(); drawMode(c, outMode, w0, h0, if (shiftD < 0f) M else shiftD) }; return }   // sliding out (its snow already fell)
        a.snow.begin()
        val w = width.toFloat(); val h = height.toFloat()
        val pw = w * 0.19f; val cw = 420 * U; val panels = sideLvl > 0 && (mode == 1 || mode == 2)
        val shift = if (panels) M + (if (sideLvl >= 2) pw + GAP else 0f) + cw + GAP else M   // lists move right of the floating panels, nothing overlaps
        // the panels slide in from the left edge and the list makes room, eased in time (about 0.25 s)
        val tNow = SystemClock.uptimeMillis()
        if (shiftD < 0f || !animOn() || (mode != 1 && mode != 2)) shiftD = shift
        else if (shiftD != shift) {
            val k = 1f - Math.exp(-minOf(tNow - shiftAt, 50L) / 70.0).toFloat()
            shiftD += (shift - shiftD) * k; if (Math.abs(shift - shiftD) < 0.5f) shiftD = shift else postInvalidateOnAnimation()
        }
        shiftAt = tNow; stepPress(tNow)
        if (fromMode != 0 && fadeT0 == 0L) fadeT0 = SystemClock.uptimeMillis()
        val ft = if (fromMode != 0) (SystemClock.uptimeMillis() - fadeT0) / 240f else 1f
        if (ft < 1f) {   // crossfade: the old screen fades out (without blur behind it), the new one fades in
            val e = ft * ft * (3 - 2 * ft); val keep = blur.cur
            blur.cur = null; c.saveLayerAlpha(0f, 0f, w, h, ((1 - e) * 255).toInt()); drawMode(c, fromMode, w, h, shiftD); c.restore(); blur.cur = keep
            c.saveLayerAlpha(0f, 0f, w, h, (e * 255).toInt()); drawMode(c, mode, w, h, shiftD); c.restore()
            postInvalidateOnAnimation()
        } else { fromMode = 0; drawMode(c, mode, w, h, shiftD) }
        if (mode == 1 || mode == 2) {   // drawn while open or still sliding out
            val cx = shiftD - GAP - cw; val px = cx - GAP - pw
            if (px + pw > M - GAP + 1) drawPanel(c, px, pw, h, "Playlists", plItems(), plSel, plActive(), sideFocus == 2, plGrab, true,
                if (plGrab) listOf("UP / DOWN" to "move", "OK" to "drop") else listOf("OK" to "hold for options"))
            if (cx + cw > M - GAP + 1) drawPanel(c, cx, cw, h, if (a.grp == 0) "Favorite Groups" else "Categories", catItems(), catSel, catActive(), sideFocus == 1, null, true)
            if ((plMenu >= 0 || plAsk >= 0) && sideLvl >= 2) drawPlCard(c, px + pw + 14 * U, panSelY["Playlists"] ?: h / 2, h)
        }
        if (T.gold && animOn()) tick(66)   // the gold shimmer moves only while a screen is open
        a.snow.end()
    }

    // small card beside the selected playlist: Move / Remove / Cancel, or the "Remove?" question (starts on Cancel)
    fun drawPlCard(c: Canvas, x: Float, cy: Float, h: Float) {
        val ask = plAsk >= 0; val name = plItems().getOrNull(plSel) ?: ""
        val rows = if (ask) listOf("trash" to "Remove", "x" to "Cancel") else listOf("move" to "Move", "trash" to "Remove", "x" to "Cancel")
        val f = if (ask) plAsk else plMenu
        val w = (if (ask) 440 else 300) * U; val rh = 72 * U; val head = if (ask) 104 * U else 0f; val ch = head + rows.size * rh + 24 * U
        val y = (cy - head - rh / 2 - 12 * U).coerceIn(M, h - M - ch)
        glass(c, x, y, w, ch, 28 * U)
        if (ask) {
            txt(c, "Remove $name?", x + 28 * U, y + 44 * U, w - 56 * U, 30f, WHITE, 700)
            txt(c, "Favorites from it stay saved", x + 28 * U, y + 82 * U, w - 56 * U, 23f, SEC)
        }
        rows.forEachIndexed { k, (ic, lab) ->
            val ry = y + head + 12 * U + k * rh; val on = k == f; val col = if (on) ONF else if (ic == "trash") 0xFFFF6961.toInt() else WHITE
            lift(c, on, x + 12 * U, ry + 4 * U, w - 24 * U, rh - 8 * U, 1.03f) {
                if (on) rr(c, x + 12 * U, ry + 4 * U, w - 24 * U, rh - 8 * U, FR, FOC)
                icon(c, ic, x + 46 * U, ry + rh / 2, 28 * U, col)
                txt(c, lab, x + 76 * U, ry + rh / 2, w - 110 * U, 29f, col, if (on) 600 else 500)
            }
        }
    }

    // ---------- channel list ----------
    fun drawChannels(c: Canvas, w: Float, h: Float) {
        val rh = rowH(); val lw = 520 * U; val logoW = 196 * U; val tx = logoW + 8 * U; val tmax = lw - logoW - 28 * U
        glass(c, 0f, M, lw, h - 2 * M)
        if (a.shown.isEmpty()) txt(c, "No channels", 30 * U, h / 2, lw - 60 * U, 29f, SEC)
        beginScroll(c, 0f, M, lw, h - 2 * M)
        // the selected row and the options stay fixed in the bottom slot; the list scrolls through it and never wraps visually
        val n = a.shown.size; val vp = if (REVERSED) n - 1 - sel else sel
        stepJump(); val top = listTop(vp) + jd; val di = Math.floor(top.toDouble()).toInt(); val df = top - di   // rows slide through the highlight
        val hy = (vp - listTop(vp) + jh) * rh   // the highlight's row: fixed in slot S, except at the start of the list
        if (n > 0) {   // the highlight and the selected logo's grey box stay put; the logos slide through them
            val y = hy; if (T.redFocus) rr(c, 12 * U, y + 6 * U, lw - 24 * U, rh - 12 * U, FR, FOC) else { rr(c, 12 * U, y + 6 * U, lw - 24 * U, rh - 12 * U, FR, SELBG); outline(c, 12 * U, y + 6 * U, lw - 24 * U, rh - 12 * U, FR) }
            rr(c, 22 * U, y + 16 * U, logoW - 30 * U, rh - 32 * U, 10 * U, FILL)
        }
        for (s in 0..8) {
            val vi = s + di; val y = (s - df) * rh
            if (isSeam(vi)) { seam(c, 24 * U, lw - 24 * U, y + rh / 2, 36 * U); continue }
            val ch = visCh(vi) ?: continue; val pr = a.now(ch); val on = vi == vp
            tile(c, ch, 22 * U, y + 16 * U, logoW - 30 * U, rh - 32 * U, if (ch == a.cur && !on) FILL else 0)   // 10 px inside the row highlight; the playing logo carries its own grey box, the selected one sits in the fixed box
            val tag = if (a.multi()) (a.sources.find { it.id == ch.src }?.let { "  ·  ${it.name}" } ?: "") else ""
            val nm = "${ch.num}: ${ch.name}$tag"
            stxt(c, nm, tx, y + rh * 0.24f - 9 * U, tmax, 23f, if (on && T.redFocus) ONF2 else SEC, 400, on)
            stxt(c, pr?.title ?: "", tx, y + rh * 0.50f - 11 * U, tmax, 31f, WHITE, 600, on)
            if (pr != null) {
                txt(c, "${a.hm.format(pr.start)} – ${a.hm.format(pr.stop)}" + (if (on) "  ·  ${minLeft(pr)} min left" else ""), tx, y + rh * 0.74f - 8 * U, tmax, 22f, if (on && T.redFocus) ONF2 else SEC)
                progress(c, tx, y + rh * 0.84f, lw - logoW - 36 * U, pr)
            }
        }
        endScroll(c)
        a.shown.getOrNull(sel)?.let { ch ->
            val cx = lw + 34 * U; val opts = a.chOptions(ch, false); val full = opts.size * 176 * U + 14 * U
            val cwid = minOf(w - cx - 36 * U, full)
            matCard(c, cx, hy + 2 * U, cwid, rh - 6 * U, hy + rh / 2)
            optButtons(c, cx, hy, cwid, opts, oi)
        }
        val now = System.currentTimeMillis()
        if (sideLvl == 0) status(c, lw, listOf(a.hm.format(now), a.dm.format(now), srcName(a.shown.getOrNull(sel))), listOf(a.searchQ?.let { "Search “$it”" } ?: a.groups.getOrElse(a.grp) { "" }, "${a.shown.size} channels"))
        a.season().let { if (it != 0 && a.shown.isNotEmpty()) deco(c, it, -rh * 0.14f, hy + 6 * U - rh * 0.26f, rh * 0.52f) }
    }

    // ---------- guide: days | channels | shows (later above, earlier below) + a card with the selected show and its buttons ----------
    var cardY = -1f   // top of the guide card (animated)
    fun drawGuide(c: Canvas, w: Float, h: Float) {
        val rh = rowH(); val dw = 164 * U; val cw = 200 * U; val px = dw + cw + GAP; val pw = 454 * U; val bx = px + pw   // px: the shows card, GAP right of the logos card; dw: date pills (20 px edge + 132 + 12 px gap)
        val dys = days(); val ch = a.shown.getOrNull(sel); val now = System.currentTimeMillis()
        val dp = if (ch != null) a.dayProgs(ch, dys[dayI.coerceIn(0, dys.lastIndex)]) else emptyList()
        val all = if (ch != null) a.progsOf(ch) else emptyList(); val at = dp.getOrNull(progI)?.let { all.indexOf(it) } ?: -1
        val ti = a.todayIdx()
        // dates: small glass pills stacked 10 px apart, the selected day level with the selected row
        val pw0 = 132 * U; val ph0 = 76 * U; val px0 = 20 * U; val pc = S * rh + rh / 2
        for (j in -12..4) {
            val cyp = pc + j * (ph0 + 10 * U); if (cyp - ph0 / 2 < M || cyp + ph0 / 2 > h - M) continue
            val day = dys.getOrNull(dayI - dir * j) ?: continue; val i = dys.indexOf(day); val on = j == 0
            glass(c, px0, cyp - ph0 / 2, pw0, ph0, ph0 / 2)
            if (on) rr(c, px0, cyp - ph0 / 2, pw0, ph0, ph0 / 2, FOC)   // the chosen day: a solid white pill, like the selected show in the TV Guide grid
            val lb = if (i == ti) "Today" else if (i == ti + 1) "Tomorrow" else if (i == ti - 1) "Yesterday" else wd.format(day)
            txt(c, lb, px0 + pw0 / 2, cyp - 14 * U, pw0 - 16 * U, 22f, if (on) ONF else SEC, 600, Paint.Align.CENTER)
            txt(c, dd.format(day), px0 + pw0 / 2, cyp + 15 * U, pw0 - 16 * U, 28f, if (on) ONF else WHITE, 700, Paint.Align.CENTER)
        }
        // channels and shows are two floating cards: the shows all belong to the selected channel (named in the shows card's
        // header), so they must not read as the shows of the logos beside them; the other logos also fade while browsing shows
        val gh = h - 2 * M; val hd = 86 * U
        // the other logos dim smoothly (250 ms) when focus moves to the shows, and brighten again when it comes back
        val now0 = SystemClock.uptimeMillis(); val dimTo = if (col == 2) 1f else 0f
        val dt = minOf(now0 - dimAt, 100L) / 250f   // at most one slow frame's worth per draw, so an idle gap never skips the fade
        if (!animOn() || dimAt == 0L) dimV = dimTo else dimV = if (dimV < dimTo) minOf(dimTo, dimV + dt) else maxOf(dimTo, dimV - dt)
        dimAt = now0; if (dimV != dimTo) postInvalidateOnAnimation()
        val dimA = (255 - (255 - 46) * dimV).toInt()
        glass(c, dw, M, cw, gh)
        beginScroll(c, dw, M, cw, gh)
        rr(c, dw + 12 * U, S * rh + 6 * U, cw - 24 * U, rh - 12 * U, FR, SELBG)
        if (col == 1) outline(c, dw + 12 * U, S * rh + 6 * U, cw - 24 * U, rh - 12 * U, FR)
        if (a.shown.isNotEmpty()) rr(c, dw + 22 * U, S * rh + 16 * U, cw - 44 * U, rh - 32 * U, 10 * U, FILL)   // the selected logo's grey box stays put while logos slide
        stepJump(); val di = Math.floor(jd.toDouble()).toInt(); val df = jd - di   // jump scroll, as in the channel list
        for (s in 0..8) {
            val y = (s - df) * rh; val on = s + di == S
            val vi = (if (REVERSED) a.shown.size - 1 - sel else sel) - S + s + di
            if (isSeam(vi)) { if (dimA < 255) c.saveLayerAlpha(dw, y, dw + cw, y + rh, dimA); seam(c, dw + 22 * U, dw + cw - 22 * U, y + rh / 2, 32 * U); if (dimA < 255) c.restore() }
            visCh(vi)?.let { cc ->
                val playing = cc == a.cur; val dim = dimA < 255 && !on && !playing   // the playing channel never fades
                if (dim) c.saveLayerAlpha(dw, y, dw + cw, y + rh, dimA)
                tile(c, cc, dw + 22 * U, y + 16 * U, cw - 44 * U, rh - 32 * U, if (playing && !on) FILL else 0)   // 10 px inside the outline; the playing logo carries its own grey box, the selected one sits in the fixed box
                if (dim) c.restore()
            }
        }
        endScroll(c)
        glass(c, px, M, pw, gh)
        beginScroll(c, px, M + hd, pw, gh - hd)
        rr(c, px + 12 * U, S * rh + 6 * U, pw - 24 * U, rh - 12 * U, FR, SELBG)
        if (col == 2) outline(c, px + 12 * U, S * rh + 6 * U, pw - 24 * U, rh - 12 * U, FR)
        for (s in 0..7) {
            val y = s * rh; val on = s == S
            (if (at >= 0) all.getOrNull(at - (s - S)) else dp.getOrNull(progI - (s - S)))?.let { pr ->   // later shows above the selected one, earlier below (across midnight too)
                if (!on && pr.start > now) rr(c, px + 12 * U, y + 6 * U, pw - 24 * U, rh - 12 * U, FR, FILL)   // upcoming shows are a little lighter, as in the stable build
                val lv = isLive(pr); val past = pr.stop <= now; var lx = px + 28 * U; val ty = y + rh * 0.30f
                if (lv) lx += live(c, lx, ty, 15f) + 10 * U
                val tl = "${a.hm.format(pr.start)} – ${a.hm.format(pr.stop)}"; txt(c, tl, lx, ty, pw - 60 * U, 22f, SEC); lx += tw(tl, 22f) + 10 * U
                if (ch != null && pr.start > now && a.hasRem(ch, pr)) { icon(c, "bell", lx + 10 * U, ty, 20 * U, 0, true); lx += 28 * U }
                if (on && pr.start > now) { val m = (pr.start - now) / 60000; if (m < 120) txt(c, "·  in ${m + 1} min", lx, ty, 200 * U, 22f, SEC) }
                val cu = ch != null && pr.start <= now && a.canCatchup(ch, pr)
                if (cu) icon(c, "back15", px + pw - 36 * U, y + rh * 0.56f, 24 * U, SEC)
                stxt(c, pr.title, px + 28 * U, y + rh * 0.56f, pw - 56 * U - (if (cu) 30 * U else 0f), 29f, if (past) SEC else WHITE, 600, on && col == 2)
                if (lv) progress(c, px + 28 * U, y + rh * 0.84f, pw - 60 * U, pr)
            }
        }
        if (dp.isEmpty()) txt(c, "No guide data", px + 28 * U, S * rh + rh / 2, pw - 56 * U, 29f, SEC)
        endScroll(c)
        if (ch != null) {   // header: which channel these shows are on
            fill(c, px, M + hd, pw, U, HAIR)
            tile(c, ch, px + 22 * U, M + 18 * U, 80 * U, 50 * U)
            tp.letterSpacing = 0.05f; txt(c, "SHOWS ON", px + 118 * U, M + 28 * U, pw - 140 * U, 20f, SEC, 600); tp.letterSpacing = 0f
            txt(c, "${ch.num}: ${ch.name}", px + 118 * U, M + 58 * U, pw - 140 * U, 27f, WHITE, 700)
        }
        // the card: about the selected show, with its buttons on the selected row's line (only once the show column is focused)
        val pr = dp.getOrNull(progI); val cx = bx + 34 * U; val rowY = S * rh
        // with the side panels open there is no room for it: it is left out instead of being squeezed into a sliver
        if (w - bx - 70 * U < 600 * U) cardY = -1f   // reappears in place, without sliding in from an old spot
        if (pr != null && ch != null && w - bx - 70 * U >= 600 * U) {
            // never wider than its row of buttons; a long description makes it taller instead
            val gOpts = a.chOptions(ch, true, pr); val cw2 = minOf(w - bx - 70 * U, gOpts.size * 176 * U + 14 * U)
            val ds = a.descOf(ch, pr)
            val dh = if (ds.isEmpty()) 0f else { tp.textSize = tsz(25f); tp.typeface = REG; StaticLayout(ds, tp, (cw2 - 72 * U).toInt(), Layout.Alignment.ALIGN_NORMAL, 1.25f, 0f, false).height.toFloat() }
            // the card's bottom always lines up with the bottom of the button bar; when the buttons appear the card grows upward
            val btns = col == 2; val cb = rowY + rh - 4 * U
            val extra = maxOf(0f, dh - (if (btns) 102 * U else 84 * U))
            val want = maxOf(M, (if (btns) rowY - 300 * U else cb - 284 * U) - extra)
            if (cardY < 0f || !animOn()) cardY = want
            else if (Math.abs(cardY - want) > 0.5f) { cardY += (want - cardY) * 0.3f; postInvalidateOnAnimation() } else cardY = want
            val cy = cardY
            matCard(c, cx, cy, cw2, cb - cy, rowY + rh / 2)
            var sx = cx + 36 * U; val sy = cy + 45 * U
            val parts = mutableListOf<String>()
            when {
                pr.start > now -> parts.add("Starts in ${(pr.start - now) / 60000 + 1} min".let { if (pr.start - now > 120 * 60000L) "Upcoming" else it })
                isLive(pr) -> { sx += live(c, sx, sy, 17f) + 12 * U; parts.add("${minLeft(pr)} min left") }
                else -> parts.add("Ended")
            }
            val dayLb = (dayI - ti).let { if (it == 0) "Today" else if (it == 1) "Tomorrow" else if (it == -1) "Yesterday" else wd.format(pr.start) }
            parts.add("$dayLb ${a.hm.format(pr.start)} – ${a.hm.format(pr.stop)}"); parts.add("${(pr.stop - pr.start) / 60000} min")
            parts.forEachIndexed { i, s -> if (i > 0) { txt(c, "·", sx, sy, 30 * U, 23f, TER); sx += 22 * U }; txt(c, s, sx, sy, 600 * U, 23f, if (i == 0) WHITE else SEC, if (i == 0) 600 else 400); sx += tw(s, 23f, if (i == 0) 600 else 400) + 14 * U }
            if (pr.start > now && a.hasRem(ch, pr)) {
                tp.letterSpacing = 0.03f; val rw = tw("REMINDER SET", 23f, 700); val rx = cx + cw2 - 36 * U - rw
                txt(c, "REMINDER SET", rx, sy, rw + 4 * U, 23f, 0, 700, accent = true); tp.letterSpacing = 0f; icon(c, "bell", rx - 20 * U, sy, 22 * U, 0, true)
            }
            txt(c, pr.title, cx + 36 * U, cy + 98 * U, cw2 - 72 * U, 42f, WHITE, 700)
            var gx = cx + 36 * U; val gy = cy + 151 * U
            if (ch.group.isNotEmpty()) {
                val gw = tw(ch.group, 23f, 600) + 22 * U
                p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * U; p.color = 0x73FFFFFF; c.drawRoundRect(RectF(gx, gy - 17 * U, gx + gw, gy + 17 * U), 6 * U, 6 * U, p); p.style = Paint.Style.FILL
                txt(c, ch.group, gx + 11 * U, gy, gw, 23f, WHITE, 600); gx += gw + 12 * U
            }
            txt(c, "${ch.num}: ${ch.name}", gx, gy, cx + cw2 - 36 * U - gx, 23f, SEC)
            if (ds.isNotEmpty()) para(c, ds, cx + 36 * U, cy + 186 * U, cw2 - 72 * U, (if (btns) rowY - 12 * U else cb - 14 * U) - (cy + 186 * U), 25f, 0xCCFFFFFF.toInt(), 1.25f)
            if (btns) { fill(c, cx + 24 * U, rowY - 2 * U, cw2 - 48 * U, U, HAIR); optButtons(c, cx, rowY, cw2, gOpts, oi) }
        }
        if (sideLvl == 0) status(c, bx, listOf(a.hm.format(now), a.dm.format(now), srcName(ch)), null)
        a.season().let { if (it != 0 && ch != null) deco(c, it, dw - rh * 0.14f, S * rh + 6 * U - rh * 0.26f, rh * 0.52f) }   // always on the logo box's corner, as in the channel list
    }

    // ---------- channel settings: Apple TV player style; LEFT / RIGHT between Program Info (subI -1) and the 4 settings, OK opens the list above ----------
    val subScrim = Paint()
    // the card after a channel change (bottom left, video stays visible): logo, which search you are flipping through if any,
    // channel, the show on now with LIVE, time left and a progress bar, and the two buttons that do more
    fun drawZapCard(c: Canvas, ch: Ch, w: Float, h: Float) {
        val pr = a.now(ch); val cw = 980 * U; val ch0 = 176 * U; val x = M; val y = h - M - ch0; val pad = 28 * U
        if (T.fx) a.snow.card(x + matOx, y, cw, ch0, 34 * U)
        glass(c, x, y, cw, ch0, 34 * U)
        tile(c, ch, x + pad, y + (ch0 - 99 * U) / 2, 176 * U, 99 * U, 0x1AFFFFFF)
        val tx = x + pad + 176 * U + 28 * U; val tr = x + cw - pad; var lx = tx
        // top line: search capsule, channel number and name; the key hints on the right
        val okL = a.keys.keys(KEY_ACTS[1]).firstOrNull()?.let { keyLabel(it) }
        val hint = (if (okL != null) listOf(okL to "Settings") else emptyList()) + listOf("Hold OK" to "Menu")
        val hw = keyHintW(hint); keyHint(c, hint, tr - hw, y + 40 * U, hw + 4 * U)
        val sq = a.searchQ; val si = if (sq != null) a.shown.indexOfFirst { it.url == ch.url } else -1
        if (si >= 0) { val tag = "Search “$sq” · ${si + 1} of ${a.shown.size}"; val tgw = minOf(tw(tag, 21f, 600) + 26 * U, tr - hw - 30 * U - lx - 120 * U)
            rr(c, lx, y + 22 * U, tgw, 36 * U, 18 * U, 0x2EFFFFFF); txt(c, tag, lx + 13 * U, y + 40 * U, tgw - 26 * U, 21f, WHITE, 600); lx += tgw + 14 * U }
        txt(c, "${ch.num}  ${ch.name}", lx, y + 40 * U, tr - hw - 30 * U - lx, 24f, SEC, 500)
        // the show: title, then LIVE, time left and the bar
        txt(c, pr?.title ?: ch.name, tx, y + 88 * U, tr - tx, 36f, WHITE, 700)
        if (pr != null) {
            val ly = y + 136 * U; var bx = tx + live(c, tx, ly, 17f) + 14 * U
            val t = "${minLeft(pr)} min left  ·  ends ${a.hm.format(pr.stop)}"; txt(c, t, bx, ly, 420 * U, 23f, SEC); bx += tw(t, 23f) + 24 * U
            if (tr - bx > 80 * U) progress(c, bx, ly - 3 * U, tr - bx, pr, 6f)
        } else txt(c, "No TV guide for this channel", tx, y + 136 * U, tr - tx, 23f, SEC)
    }
    fun drawSub(c: Canvas, w: Float, h: Float) {
        val ch = a.cur ?: a.shown.getOrNull(sel) ?: return
        if (quickHide && backMode == 0) { drawZapCard(c, ch, w, h); return }   // after a channel change: just the small card
        subScrim.shader = LinearGradient(0f, h, 0f, h * 0.5f, intArrayOf(0xEB000000.toInt(), 0xB3000000.toInt(), 0), floatArrayOf(0f, 0.48f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, h * 0.5f, w, h, subScrim)
        // after a channel change (quickHide) only the info shows; after OK the four compact setting tiles sit on the right
        val y = h - 280 * U; val pr = a.now(ch); val tiles = !quickHide
        tile(c, ch, 90 * U, y, 170 * U, 96 * U, 0x1AFFFFFF)
        val labels = listOf("Audio", "Subtitles", "Aspect Ratio", "Audio Delay"); val short = listOf("Audio", "Subtitles", "Aspect", "Delay")
        val ics = listOf("audio", "cc", "aspect", "delay")
        val tw0 = 150 * U; val th0 = 118 * U; val pitch = tw0 + 12 * U; val pi = backMode == 0   // Program Info tile only from the player: the lists already have their own Program Info button
        val px0 = w - 90 * U - (if (pi) 7 else 4) * pitch + 12 * U; val ty0 = y - 6 * U
        // after a channel change: which buttons open the settings and the menu, so nobody has to guess
        val okL = a.keys.keys(KEY_ACTS[1]).firstOrNull()?.let { keyLabel(it) }
        val hint = (if (okL != null) listOf(okL to "Settings") else emptyList()) + listOf("Hold OK" to "Menu")
        val hintW = keyHintW(hint)
        if (!tiles) keyHint(c, hint, w - 90 * U - hintW, y + 22 * U, hintW + 4 * U)
        val tmw = (if (tiles) px0 - 40 * U else w - 90 * U - hintW - 50 * U) - 290 * U
        txt(c, "${ch.num}: ${ch.name}", 290 * U, y - 21 * U, tmw, 26f, SEC)
        val lines = split2(pr?.title ?: ch.name, tmw, 46f, 700)   // long titles wrap onto a second line
        lines.forEachIndexed { k, l -> txt(c, l, 290 * U, y + 22 * U + k * 50 * U, tmw, 46f, WHITE, 700) }
        if (pr != null) {
            val my = y + 22 * U + (lines.size - 1) * 50 * U + 52 * U
            val ml = "${minLeft(pr)} min left"; txt(c, ml, 290 * U, my, tmw, 26f, WHITE, 600)
            txt(c, "·  ends ${a.hm.format(pr.stop)}", 290 * U + tw(ml, 26f, 600) + 14 * U, my, tmw, 26f, SEC)
            val ly = h - 70 * U; txt(c, a.hm.format(pr.start), 90 * U, ly, 120 * U, 24f, SEC, 600)
            val lw = tw("LIVE", 18f, 700) + 18 * U; val e = a.hm.format(pr.stop); val ew = tw(e, 24f, 600)
            val bx0 = 90 * U + tw(a.hm.format(pr.start), 24f, 600) + 22 * U; val bx1 = w - 90 * U - lw - 22 * U - ew - 22 * U
            progress(c, bx0, ly - 4 * U, bx1 - bx0, pr, 8f); txt(c, e, bx1 + 22 * U, ly, ew + 4 * U, 24f, SEC, 600); live(c, w - 90 * U - lw, ly, 18f)
        }
        val pinned = a.pip.ch
        if (tiles && pi) {   // from the player only: Program Info (subI -3), TV Guide (-2) and Pin (-1) come first
            for ((k, t) in listOf(Triple("info", "Program", "Info"), Triple("grid", "TV", "Guide"), Triple("pin", if (pinned == null) "Pin" else "Pinned", pinned?.name ?: "Channel")).withIndex()) {
                val sel = subI == k - 3 && !subOpen; val x = px0 + k * pitch; if (T.fx) a.snow.card(x + matOx, ty0, tw0, th0, FR)
                lift(c, sel, x, ty0, tw0, th0, 1.06f, base = 0x1FFFFFFF) {
                    rr(c, x, ty0, tw0, th0, FR, if (sel) FOC else 0x1FFFFFFF)
                    val col = if (sel) ONF else WHITE
                    icon(c, t.first, x + tw0 / 2, ty0 + 29 * U, 34 * U, col)
                    txt(c, t.second, x + tw0 / 2, ty0 + 66 * U, tw0 - 16 * U, 26f, col, 700, Paint.Align.CENTER)
                    txt(c, t.third, x + tw0 / 2, ty0 + 96 * U, tw0 - 16 * U, 19f, if (sel) ONF2 else SEC, 400, Paint.Align.CENTER)
                }
            }
        }
        if (tiles) for (i in 0..3) {
            val (opts, cs) = a.subOpts(i); val x = px0 + (if (pi) i + 3 else i) * pitch; val sel = i == subI && !subOpen
            val one = opts.size <= 1; val on = sel && !one   // nothing to choose: dimmed like a greyed-out Settings row, and no white focus
            if (T.fx) a.snow.card(x + matOx, ty0, tw0, th0, FR)
            lift(c, sel, x, ty0, tw0, th0, 1.06f, base = if (i == subI) SELBG else 0x1FFFFFFF) {
                rr(c, x, ty0, tw0, th0, FR, if (on) FOC else if (sel) 0x3DFFFFFF else if (i == subI) SELBG else 0x1FFFFFFF)
                val col = if (on) ONF else if (one && !sel) 0x66FFFFFF else WHITE
                icon(c, ics[i], x + tw0 / 2, ty0 + 29 * U, 34 * U, col)
                txt(c, opts.getOrElse(cs) { "" }, x + tw0 / 2, ty0 + 66 * U, tw0 - 16 * U, 26f, col, 700, Paint.Align.CENTER)
                txt(c, if (one && sel) "Only one" else short[i], x + tw0 / 2, ty0 + 96 * U, tw0 - 16 * U, 19f, if (on) ONF2 else if (one && !sel) 0x55FFFFFF else SEC, 400, Paint.Align.CENTER)
            }
            if (subOpen && i == subI) {
                // the list above the tile: the highlighted value sits in the middle, its neighbours fade out; a tick marks the one in use
                // up to 5 options: all of them in order, the card as tall as they are; more (audio delay): a wheel with the choice in the middle
                val n = opts.size; val wheel = n > 5; val nr = if (wheel) 5 else n; val dly = i == 3
                val qw = 430 * U; val qh = (90 + nr * 66 + (if (dly) 84 else 16)) * U; val qx = (x + tw0 - qw).coerceAtLeast(40 * U); val qy = ty0 - qh - 34 * U
                matCardDown(c, qx, qy, qw, qh, x + tw0 / 2)
                txt(c, labels[i], qx + 30 * U, qy + 46 * U, qw - 200 * U, 28f, WHITE, 700)
                if (dly) txt(c, "this channel", qx + qw - 30 * U, qy + 46 * U, 160 * U, 20f, SEC, 400, Paint.Align.RIGHT)
                val alphaJ = intArrayOf(0x59, 0x99, 0xFF, 0x99, 0x59)
                for (j in 0 until nr) {
                    val k = if (wheel) oj + (2 - j) * dir else if (dir > 0) n - 1 - j else j   // UP moves to the row above, as in the wheel
                    if (k < 0 || k >= n) continue
                    val f = k == oj; val ry = qy + 90 * U + j * 66 * U
                    if (f) rr(c, qx + 16 * U, ry, qw - 32 * U, 60 * U, FR, FOC)
                    val col = if (f) ONF else if (wheel) withAlpha(-1, alphaJ[j]) else WHITE
                    txt(c, opts[k], qx + 38 * U, ry + 30 * U, qw - 120 * U, if (f) 36f else 29f, col, if (f) 700 else 600)
                    if (k == cs) icon(c, "check", qx + qw - 54 * U, ry + 30 * U, 26 * U, col)
                }
                if (dly) {   // the only list whose keys need explaining
                    fill(c, qx + 30 * U, qy + qh - 74 * U, qw - 60 * U, U, HAIR)
                    keyHint(c, listOf("UP / DOWN" to "50 ms", "◀ ▶" to "1 s"), qx + 30 * U, qy + qh - 40 * U, qw - 60 * U)
                }
            }
        }
        if (tiles && pi && subOpen && subI == -1) {   // the pinned channel's list above the Pin tile
            val tcx = px0 + 2 * pitch + tw0 / 2; val qw = 420 * U; val qh = (24 + 3 * 82) * U; val qx = tcx - qw / 2; val qy = ty0 - qh - 34 * U
            matCardDown(c, qx, qy, qw, qh, tcx)
            val cn = listOf("Top Right", "Bottom Right", "Bottom Left", "Top Left")[a.pip.corner]
            for ((j, t) in listOf("Swap", "Corner", "Unpin").withIndex()) {
                val f = j == oj; val ry = qy + 12 * U + j * 82 * U; val col = if (f) ONF else WHITE
                if (f) rr(c, qx + 12 * U, ry, qw - 24 * U, 72 * U, FR, FOC)
                txt(c, t, qx + 36 * U, ry + 36 * U, 160 * U, 29f, col, if (f) 700 else 600)
                if (j == 1) {   // where it is now: the name and a small screen with the picture's corner filled
                    val ix = qx + qw - 36 * U - 46 * U; val iy = ry + 21 * U
                    txt(c, cn, ix - 14 * U, ry + 36 * U, 190 * U, 21f, if (f) ONF2 else SEC, 600, Paint.Align.RIGHT)
                    p.style = Paint.Style.STROKE; p.strokeWidth = 3 * U; p.color = col; c.drawRoundRect(RectF(ix, iy, ix + 46 * U, iy + 30 * U), 6 * U, 6 * U, p); p.style = Paint.Style.FILL
                    val k = a.pip.corner; val dx = ix + (if (k <= 1) 27 else 6) * U; val dy = iy + (if (k == 0 || k == 3) 6 else 15) * U
                    rr(c, dx, dy, 13 * U, 9 * U, 2 * U, col)
                }
            }
        }
    }

    // seasonal icons are painted PNGs (res/drawable-nodpi/se_<id>.png, made from LiteTV assets/Seasonal deco); each image has
    // 14% padding around the icon box so shadows and sparkles fit, and the slant is already in the art
    private val DECO_RES = mapOf(1 to R.drawable.se_1, 20 to R.drawable.se_20, 2 to R.drawable.se_2, 3 to R.drawable.se_3, 4 to R.drawable.se_4, 5 to R.drawable.se_5, 6 to R.drawable.se_6, 7 to R.drawable.se_7, 8 to R.drawable.se_8, 9 to R.drawable.se_9, 10 to R.drawable.se_10, 11 to R.drawable.se_11, 12 to R.drawable.se_12, 13 to R.drawable.se_13, 14 to R.drawable.se_14, 15 to R.drawable.se_15, 16 to R.drawable.se_16, 17 to R.drawable.se_17, 18 to R.drawable.se_18, 19 to R.drawable.se_19, 21 to R.drawable.se_21, 22 to R.drawable.se_22, 23 to R.drawable.se_23, 24 to R.drawable.se_24)
    private val decoBmp = HashMap<Int, Bitmap?>()
    private val decoPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    fun deco(c: Canvas, id: Int, x: Float, y: Float, s: Float) {
        val b = decoBmp.getOrPut(id) {
            DECO_RES[id]?.let { android.graphics.BitmapFactory.decodeResource(resources, it) }
        } ?: return
        c.drawBitmap(b, null, RectF(x - 0.14f * s, y - 0.14f * s, x + 1.14f * s, y + 1.14f * s), decoPaint)
    }

    // LiteTV logo (2026 redesign "Graphite"): dark graphite tile, white rounded L, play triangle in the theme's accent
    fun drawMark(c: Canvas, x: Float, y: Float, s: Float) {
        val k = s / 100f; p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, y, 0f, y + s, 0xFF3A3A3F.toInt(), 0xFF141416.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(RectF(x, y, x + s, y + s), 22 * k, 22 * k, p); p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.2f * k; p.color = 0x2EFFFFFF   // faint edge so the dark tile shows on the dark menu
        c.drawRoundRect(RectF(x + 0.6f * k, y + 0.6f * k, x + s - 0.6f * k, y + s - 0.6f * k), 21.4f * k, 21.4f * k, p); p.style = Paint.Style.FILL; p.color = -1
        c.drawRoundRect(RectF(x + 27 * k, y + 26 * k, x + 41 * k, y + 76 * k), 7 * k, 7 * k, p)
        c.drawRoundRect(RectF(x + 27 * k, y + 62 * k, x + 75 * k, y + 76 * k), 7 * k, 7 * k, p)
        val t = Path(); t.moveTo(x + 50 * k, y + 29 * k); t.lineTo(x + 72 * k, y + 42 * k); t.lineTo(x + 50 * k, y + 55 * k); t.close()
        // the triangle takes the theme's accent (New Year: the moving gold), lighter at the top like the icon
        if (T.gold) accentPaint(p) else p.shader = LinearGradient(x + 50 * k, y + 29 * k, x + 72 * k, y + 55 * k, blend(T.accent, -1, 0.3f), T.accent, Shader.TileMode.CLAMP)
        p.style = Paint.Style.FILL_AND_STROKE; p.strokeWidth = 5 * k; p.strokeJoin = Paint.Join.ROUND; c.drawPath(t, p)
        p.shader = null; p.style = Paint.Style.FILL; p.strokeJoin = Paint.Join.MITER
    }

    // ---------- main menu (OK) ----------
    // main menu: one compact floating card on the left, as tall as its contents and centred: logo, the items,
    // Settings after a hairline, and the Now Playing card closing it at the bottom like a mini player
    fun drawMenu(c: Canvas, w: Float, h: Float) {
        val items = a.menuItems(); val ch = a.cur; val pr = ch?.let { a.now(it) }
        val cw = 520 * U; val npH = 140 * U
        val listTop = 34 * U + 76 * U + 30 * U
        // rows shrink to keep the card on screen (8 items with Now Playing ran off the bottom at 90 px)
        val fixed = listTop + 29 * U + (if (ch != null) 22 * U + npH else 0f) + 20 * U
        val pitch = minOf(90 * U, (h - PT - M - fixed) / (items.size - 1 + 80f / 90f)); val ih = pitch * 80f / 90f
        val ch0 = listTop + (items.size - 1) * pitch + 14 * U + U + 14 * U + ih + (if (ch != null) 22 * U + npH else 0f) + 20 * U
        val x = M; val y = PT
        glass(c, x, y, cw, ch0); a.snow.garland(x, y, cw)   // Christmas: a garland on the menu (Snow draws it)
        val ms = 76 * U; drawMark(c, x + 40 * U, y + 34 * U, ms)
        val lx = x + 40 * U + ms + 22 * U; val ly = y + 34 * U + ms / 2
        txt(c, "Lite", lx, ly, 200 * U, 44f, WHITE, 700); txt(c, "TV", lx + tw("Lite", 44f, 700), ly, 200 * U, 44f, 0, 700, accent = true)
        txt(c, a.verLabel, lx + tw("LiteTV", 44f, 700) + 20 * U, ly + 7 * U, 160 * U, 22f, TER)   // the version lives here (not in Settings)
        val bx = x + 20 * U; val bw = cw - 40 * U
        val sepY = y + listTop + (items.size - 1) * pitch + 4 * U
        fill(c, x + 40 * U, sepY, cw - 80 * U, U, HAIR)
        items.forEachIndexed { i, it ->
            val top = if (i == items.lastIndex) sepY + 15 * U else y + listTop + i * pitch; val on = i == menuSel
            lift(c, on, bx, top, bw, ih, 1.04f) {
                if (on) rr(c, bx, top, bw, ih, FR, FOC)
                val col = if (on) ONF else WHITE
                icon(c, it.first, bx + 43 * U, top + ih / 2, 34 * U, col)
                var lw = bw - 110 * U
                if (it.first == "gear" && (a.updState == 3 || a.updState == 5)) lw -= updBadge(c, bx + bw - 24 * U, top + ih / 2, on) + 16 * U
                stxt(c, it.second, bx + 86 * U, top + ih / 2, lw, 34f, col, if (on) 600 else 500, on)
            }
        }
        // now playing (display only)
        if (ch == null) return
        val ny = y + ch0 - 20 * U - npH
        rr(c, bx, ny, bw, npH, 22 * U, 0x14FFFFFF)
        tile(c, ch, bx + 20 * U, ny + 28 * U, 150 * U, 84 * U)
        val tx = bx + 20 * U + 150 * U + 24 * U; val tmw = bx + bw - 20 * U - tx
        hdr(c, "Now Playing", tx, ny + 36 * U, tmw)
        txt(c, pr?.title ?: ch.name, tx, ny + 70 * U, tmw, 28f, WHITE, 600)
        txt(c, ch.name + (if (pr != null) "  ·  ${minLeft(pr)} min left" else ""), tx, ny + 102 * U, tmw, 21f, SEC)
        if (pr != null) progress(c, tx, ny + 122 * U, tmw, pr)
    }

    // the round ‹ before a page title: not selectable, it shows that BACK goes up a level (like Apple's back button)
    // a page's title row, the same on every page: the round ‹ in the rows' icon column, the title on the labels' line, 72 px below the card top
    fun head(c: Canvas, x: Float, top: Float, title: String, maxW: Float, back: Boolean = true) {
        if (back) backMark(c, x + 36 * U, top + 72 * U, 56 * U, bkF)
        txt(c, title, x + (if (back) 110 else 40) * U, top + 72 * U, maxW, 44f, WHITE, 700)
    }
    var bkF = false   // the page's back button has the focus (UP from the first row; OK on it = BACK). About has nothing else, so it starts there
    fun backMark(c: Canvas, x: Float, cy: Float, d: Float = 56 * U, on: Boolean = false) {
        val r = if (on) d / 2 * 1.08f else d / 2   // focused (Settings): white and a little bigger, like every focused item
        p.shader = null; if (on && T.party) sparkle(c) { c.drawCircle(x + d / 2, cy, r, it) } else { p.color = if (on) FOC else 0x24FFFFFF; c.drawCircle(x + d / 2, cy, r, p) }; icon(c, "back", x + d / 2 - 2 * U, cy, d * 0.5f, if (on) ONF else WHITE)
    }
    // ---------- settings: the categories, or one category's rows; the selected row shows what it does under its name ----------
    class SRow(val icon: String, val label: String, val value: String, val desc: String, val enabled: Boolean, val opens: Boolean)
    fun settingRows(): List<SRow> {
        if (setCat < 0) return SET_CATS.mapIndexed { k, s -> SRow(s.icon, s.name, a.catValue(k), s.desc, true, true) }
        val items = a.settingsItems()
        return SET_CATS[setCat].rows.map { i -> SRow(SETTING_ICONS.getOrElse(i) { "info" }, items[i].first, items[i].second, a.settingDesc(i), a.settingEnabled(i), i == 0 || i == 13 || i == 14 || i == 16 || i == 17) }
    }
    fun drawSettings(c: Canvas, w: Float, h: Float) {
        val pw = 680 * U; val t0 = PT + 136 * U; val foot = h - M - 20 * U; val ih = 80 * U; val bx = 20 * U; val bw = pw - 40 * U
        val rows = settingRows(); setSel = setSel.coerceIn(0, rows.lastIndex); val sel = if (bkF) -1 else setSel   // -1: the back button has the focus
        val sr = rows[setSel]; val dsz = 24f
        tp.textSize = tsz(dsz); tp.typeface = REG
        val dw = (pw - 150 * U - (if (sr.value.isNotEmpty()) tw(sr.value + "  ›", 29f, 600) + 30 * U else 0f)).toInt()
        val dl = fitText(sr.desc, maxOf(1, dw), tsz(dsz) * 1.15f * 3.2f, 1.15f)
        val dh = dl.height + 16 * U
        val ys = FloatArray(rows.size); var yy = 0f
        for (i in rows.indices) { ys[i] = yy; yy += ih + (if (i == sel) dh else 0f) }
        // the card is as tall as its rows (six categories in a full-height card looked empty)
        glass(c, 0f, PT, pw, minOf(h - PT - M, t0 + yy + 24 * U - PT))
        head(c, 0f, PT, if (setCat < 0) "Settings" else SET_CATS[setCat].name, pw - 150 * U)
        val scroll = maxOf(0f, ys[setSel] + ih + dh + 20 * U - (foot - t0))
        c.save(); c.clipRect(0f, t0 - 12 * U, pw - U, foot); c.translate(0f, t0 - scroll)
        rows.forEachIndexed { i, r ->
            val y = ys[i]; val on = i == sel; val en = r.enabled   // rows that do not apply right now are dimmed
            val rh = ih + (if (on) dh else 0f)
            lift(c, on, bx, y + 3 * U, bw, rh - 6 * U, 1.025f) {
                if (on) {
                    rr(c, bx, y + 3 * U, bw, rh - 6 * U, FR, FOC)
                    tp.textSize = tsz(dsz); tp.typeface = REG; tp.color = withAlpha(ONF, 0xA8); tp.shader = null; tp.textAlign = Paint.Align.LEFT
                    c.save(); c.translate(110 * U, y + ih - 14 * U); dl.draw(c); c.restore()
                }
                val col = if (on) ONF else if (en) WHITE else 0x66FFFFFF
                rr(c, 40 * U, y + ih / 2 - 24 * U, 48 * U, 48 * U, 12 * U, if (on) 0x14000000 else 0x1AFFFFFF)
                icon(c, r.icon, 64 * U, y + ih / 2, 28 * U, col)
                txt(c, r.label, 110 * U, y + ih / 2, pw * 0.5f, 31f, col, if (on) 600 else 500)
                if (r.value.isNotEmpty() || r.opens) txt(c, r.value + (if (r.opens) "  ›" else ""), pw - 40 * U, y + ih / 2, pw * 0.42f, 29f,
                    if (on) ONF else if (en) SEC else 0x55FFFFFF, if (on) 600 else 400, Paint.Align.RIGHT)
            }
        }
        c.restore()
    }
    // ---------- about (mode 13): in the settings panel; QR codes open the GitHub page and Ko-fi on a phone ----------
    private val aboutQr by lazy { listOf(a.qr("https://github.com/hertn/LiteTV", 300), a.qr("https://ko-fi.com/hertn", 300)) }
    fun drawAbout(c: Canvas, w: Float, h: Float) {
        val pw = 680 * U; val x = 40 * U; val iw = pw - 80 * U
        glass(c, 0f, PT, pw, h - PT - M)
        val ms = 96 * U; backMark(c, 36 * U, PT + 72 * U, 56 * U, true); val mx = 110 * U; drawMark(c, mx, PT + 72 * U - ms / 2, ms)
        val lx = mx + ms + 24 * U; val ly = PT + 72 * U + 6 * U
        txt(c, "Lite", lx, ly - 18 * U, 300 * U, 52f, WHITE, 700); txt(c, "TV", lx + tw("Lite", 52f, 700), ly - 18 * U, 200 * U, 52f, 0, 700, accent = true)
        txt(c, "Version " + a.verLabel.removePrefix("v"), lx, ly + 28 * U, iw - ms, 26f, SEC)
        var y = PT + 72 * U + 64 * U
        para(c, "A lightweight live TV app for Android TV. Add your own M3U playlists and TV guides; LiteTV doesn't include any channels.", x, y, iw, 110 * U, 27f, WHITE); y += 110 * U
        // facts list, label left and value right, like Apple TV's Settings > General > About
        val facts = listOf("Made By" to "hertn", "Price" to "Free, no ads", "Licence" to "Open source (MIT)",
            "Library" to "${a.sources.size} playlists · ${a.sources.toList().flatMap { a.epgUrls(it) }.distinct().size} TV guides",
            "This TV" to "${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}")
        val fh = 54 * U; y += 4 * U
        facts.forEachIndexed { i, (k, v) ->
            if (i > 0) fill(c, x, y, iw, U, HAIR)
            txt(c, k, x, y + fh / 2, iw * 0.4f, 26f, SEC); txt(c, v, x + iw, y + fh / 2, iw * 0.6f, 26f, WHITE, 500, Paint.Align.RIGHT)
            y += fh
        }
        // two QR cards side by side above the key hint
        val gap = 22 * U; val cw = (iw - gap) / 2; val chh = 330 * U; val cy = h - M - 36 * U - chh
        listOf(Triple("Help & Source", "github.com/hertn/LiteTV", 0), Triple("Support LiteTV", "ko-fi.com/hertn", 1)).forEach { (t, u, k) ->
            val cx = x + k * (cw + gap)
            val qs = 200 * U; val qx = cx + (cw - qs) / 2; val qy = cy + 24 * U
            rr(c, qx - 8 * U, qy - 8 * U, qs + 16 * U, qs + 16 * U, 12 * U, WHITE)
            p.shader = null; c.drawBitmap(aboutQr[k], null, RectF(qx, qy, qx + qs, qy + qs), p)
            txt(c, t, cx + cw / 2, qy + qs + 42 * U, cw - 20 * U, 27f, WHITE, 600, Paint.Align.CENTER)
            txt(c, u, cx + cw / 2, qy + qs + 76 * U, cw - 20 * U, 21f, TER, 400, Paint.Align.CENTER)
        }
    }
    // ---------- remote buttons (mode 12): every action with its buttons; OK on a row adds a button or resets it ----------
    class KRow(val label: String, val chips: List<String>, val act: KeyAct?, val desc: String, val sec: String? = null, val reset: Boolean = false)
    fun keyRows(): List<KRow> {
        val r = ArrayList<KRow>()
        KEY_ACTS.forEachIndexed { i, k ->
            val chips = a.keys.keys(k).map { keyLabel(it) }.distinct() + (if (k.fixed.isNotEmpty()) listOf(k.fixed) else emptyList())
            r.add(KRow(k.name, chips.ifEmpty { listOf("—") }, k, k.desc, if (i == 0) "Watching TV" else null))
        }
        r.add(KRow("Channel Number", listOf("0–9"), null, "Type a channel number to jump to it. 0 on its own goes back to the previous channel."))
        r.add(KRow("Exit App", listOf("Hold Back"), null, "Hold Back for a second to close LiteTV completely. While a channel is pinned, the first hold unpins it instead."))
        r.add(KRow("Move / Choose", listOf("▲▼◀▶", "OK"), null, "The arrows move and OK chooses, in every list and menu.", "In Menus and Lists"))
        r.add(KRow("Go Back", listOf("Back"), null, "Back closes the screen you are on."))
        r.add(KRow("Reset All Buttons", emptyList(), null, "Puts every button back to how it came.", "", true))
        return r
    }
    fun chip(c: Canvas, s: String, xr: Float, cy: Float, on: Boolean): Float {   // a key cap ending at xr; returns its left edge
        val cw0 = maxOf(48 * U, tw(s, 22f, 700) + 26 * U); val x = xr - cw0
        rr(c, x, cy - 21 * U, cw0, 42 * U, 10 * U, if (on) 0x1F000000 else 0x24FFFFFF)
        txt(c, s, x + cw0 / 2, cy, cw0, 22f, if (on) ONF else WHITE, 700, Paint.Align.CENTER); return x
    }
    fun drawKeys(c: Canvas, w: Float, h: Float) {
        val pw = w * 0.46f; val t0 = PT + 136 * U; val bot = h - M - 20 * U; val ih = 76 * U; val sh = 58 * U
        val rows = keyRows(); rbSel = rbSel.coerceIn(0, rows.lastIndex); val cur = rows[rbSel]
        glass(c, 0f, PT, pw, h - PT - M); head(c, 0f, PT, "Remote Buttons", pw - 150 * U)
        val ys = FloatArray(rows.size); var yy = 0f
        rows.forEachIndexed { i, r -> if (r.sec != null) yy += if (r.sec.isEmpty()) 20 * U else sh; ys[i] = yy; yy += ih }
        val scroll = maxOf(0f, ys[rbSel] + ih + 20 * U - (bot - t0))
        c.save(); c.clipRect(0f, t0, pw - U, bot); c.translate(0f, t0 - scroll)
        rows.forEachIndexed { i, r ->
            val y = ys[i]; val on = i == rbSel && rbSub == 0 && !bkF; val mark = i == rbSel && !bkF
            if (!r.sec.isNullOrEmpty()) hdr(c, r.sec.uppercase(), 40 * U, y - 22 * U, pw - 80 * U)
            lift(c, on, 20 * U, y + 3 * U, pw - 40 * U, ih - 6 * U, 1.025f) {
                if (mark) rr(c, 20 * U, y + 3 * U, pw - 40 * U, ih - 6 * U, FR, if (on) FOC else SELBG)
                val col = if (on) ONF else WHITE
                var xr = pw - 36 * U
                for (ch in r.chips.reversed()) xr = chip(c, ch, xr, y + ih / 2, on) - 10 * U
                txt(c, r.label, 40 * U, y + ih / 2, xr - 60 * U, 30f, if (r.act == null && !r.reset && !on) SEC else col, if (on) 600 else 500)
            }
        }
        c.restore()
        // right: what the selected row does; after OK a small menu, then the wait for a button
        val cx = pw + GAP; val cwid = minOf(680 * U, w - cx - M); val cy = PT
        if (cwid < 300 * U) return
        when (rbSub) {
            0 -> {
                val ch0 = 330 * U; glass(c, cx, cy, cwid, ch0)
                hdr(c, cur.label.uppercase(), cx + 36 * U, cy + 50 * U, cwid - 72 * U)
                para(c, cur.desc, cx + 36 * U, cy + 78 * U, cwid - 72 * U, 150 * U, 27f, 0xD9FFFFFF.toInt(), 1.25f)
                fill(c, cx + 36 * U, cy + ch0 - 92 * U, cwid - 72 * U, U, HAIR)
                val hint = when { cur.reset -> listOf("OK" to "reset every button"); cur.act != null -> listOf("OK" to "add a button or reset to default"); else -> listOf("" to "This one can't be changed") }
                keyHint(c, hint, cx + 36 * U, cy + ch0 - 46 * U, cwid - 72 * U)
            }
            1 -> {
                val act = cur.act ?: return; val ch0 = 270 * U; glass(c, cx, cy, cwid, ch0)
                hdr(c, cur.label.uppercase(), cx + 36 * U, cy + 50 * U, cwid - 72 * U)
                val opts = listOf("Add Button…" to "", "Reset to Default" to (act.def.map { keyLabel(it) }.distinct() + (if (act.fixed.isNotEmpty()) listOf(act.fixed) else emptyList())).joinToString(" · "))
                opts.forEachIndexed { j, (l, v) ->
                    val oy = cy + 84 * U + j * 82 * U; val on = j == rbOpt
                    lift(c, on, cx + 14 * U, oy, cwid - 28 * U, 74 * U, 1.025f) {
                        if (on) rr(c, cx + 14 * U, oy, cwid - 28 * U, 74 * U, FR, FOC)
                        txt(c, l, cx + 36 * U, oy + 37 * U, cwid * 0.5f, 30f, if (on) ONF else WHITE, if (on) 600 else 500)
                        if (v.isNotEmpty()) txt(c, v, cx + cwid - 36 * U, oy + 37 * U, cwid * 0.45f, 24f, if (on) ONF2 else SEC, 400, Paint.Align.RIGHT)
                    }
                }
            }
            else -> {
                val ch0 = 330 * U; glass(c, cx, cy, cwid, ch0)
                hdr(c, cur.label.uppercase(), cx + 36 * U, cy + 50 * U, cwid - 72 * U)
                txt(c, "Press the button you want to use", cx + 36 * U, cy + 108 * U, cwid - 72 * U, 36f, WHITE, 700)
                para(c, "It is added to the buttons already there. If another action uses it, it moves here.", cx + 36 * U, cy + 146 * U, cwid - 72 * U, 90 * U, 25f, SEC, 1.25f)
                keyHint(c, listOf("BACK" to "cancel"), cx + 36 * U, cy + ch0 - 46 * U, cwid - 72 * U)
            }
        }
    }

    // button hints: the remote's keys as caps like on Remote Buttons (Back is a "‹" cap), what they do in grey
    fun hintCaps(k: String) = when (k) { "" -> emptyList(); "UP / DOWN" -> listOf("▲", "▼"); "OK or BACK" -> listOf("OK", "BACK"); else -> listOf(k) }
    fun capW(s: String) = if (s == "BACK") 48 * U else maxOf(48 * U, tw(s, 22f, 700) + 26 * U)
    fun keyHintW(keys: List<Pair<String, String>>) = keys.map { (k, v) -> hintCaps(k).sumOf { (capW(it) + 8 * U).toDouble() }.toFloat() + 4 * U + tw(v, 24f) }.sum() + 36 * U * (keys.size - 1)
    fun keyHint(c: Canvas, keys: List<Pair<String, String>>, x: Float, cy: Float, maxW: Float) {
        var tx = x
        for ((k, v) in keys) {
            val caps = hintCaps(k)
            for (s in caps) {
                val cw0 = capW(s); rr(c, tx, cy - 21 * U, cw0, 42 * U, 10 * U, 0x24FFFFFF)
                if (s == "BACK") icon(c, "back", tx + cw0 / 2, cy, 26 * U, WHITE) else txt(c, s, tx + cw0 / 2, cy, cw0, 22f, WHITE, 700, Paint.Align.CENTER)
                tx += cw0 + 8 * U
            }
            if (caps.isNotEmpty()) tx += 4 * U
            txt(c, v, tx, cy, maxW, 24f, SEC); tx += tw(v, 24f) + 36 * U
        }
    }

    // a full-height list panel (categories, playlists, favorite groups, error log): heading, rows, white focus, tick = in use
    // floating = a glass card 36 px from the screen edges (Categories / Playlists beside the lists)
    fun drawPanel(c: Canvas, x: Float, pw: Float, h: Float, title: String, items: List<String>, selI: Int, activeI: Int, focused: Boolean, grab: Boolean?, floating: Boolean = false, hint: List<Pair<String, String>>? = null) {
        val rh = h / 11f; val rows = 10; val ins = if (floating) 12 * U else 14 * U
        if (floating) glass(c, x, M, pw, h - 2 * M) else { matRect(c, x, 0f, pw, h, 0f); fill(c, x + pw - U, 0f, U, h, HAIR) }
        hdr(c, title, x + ins + 26 * U, if (floating) M + 52 * U else rh * 0.62f - 9 * U, pw - 60 * U)
        if (hint != null && focused && items.size <= rows - 2) keyHint(c, hint, x + ins + 26 * U, h - M - 50 * U, pw - 2 * ins - 40 * U)   // in the empty space at the foot
        if (floating) beginScroll(c, x, M, pw, h - 2 * M)
        // the selector moves freely; the list scrolls only to keep one row beyond it in view (floating: the 10th row peeks under the card edge)
        val vis = if (floating) rows - 1 else rows; val maxTop = maxOf(0, items.size - vis)
        var top = (panTop[title] ?: 0).coerceIn(0, maxTop)
        if (selI < top + 1) top = maxOf(0, selI - 1)
        if (selI > top + vis - 2) top = minOf(maxTop, selI - vis + 2)
        panTop[title] = top; panSelY[title] = rh * (selI - top + 1) + rh / 2
        for (k in 0 until rows) {
            val i = top + k; val lab = items.getOrNull(i) ?: break; val y = rh * (k + 1); val on = i == selI
            val bx = x + ins; val bw = pw - 2 * ins; val bh = rh - 12 * U
            lift(c, on && focused, bx, y + 6 * U, bw, bh, 1.03f) {
                if (on) rr(c, bx, y + 6 * U, bw, bh, FR, if (focused) FOC else SELBG)
                val col = if (on && focused) ONF else WHITE
                val handle = if (!on || !focused || hint == null || i == 0) null else if (grab == true) "updown" else "grip"   // row 0 (Favorites) never moves
                if (grab == true && on) outline(c, bx, y + 6 * U, bw, bh, FR, T.accent or 0xFF000000.toInt(), 4f)   // picked up: the theme colour round it
                stxt(c, lab, bx + 26 * U, y + rh / 2, bw - (if (handle != null && i == activeI) 130 else 90) * U, 31f, col, if (on) 600 else 500, on && focused)
                if (handle != null) icon(c, handle, bx + bw - 40 * U, y + rh / 2, 28 * U, col)
                if (i == activeI) icon(c, "check", bx + bw - (if (handle != null) 84 else 40) * U, y + rh / 2, 28 * U, col)
            }
        }
        if (floating) endScroll(c)
    }

    // ---------- TV guide grid (main menu, OK tiles, the Guide button): every channel of the current list, 2 hours across ----------
    // the window scrolls: ◀ ▶ go show by show and move the window when a show starts outside it; ▲ ▼ change channel.
    // OK: a show on now = watch it, a later show = reminder on/off, an earlier one = catch-up if the channel has it; hold OK = Program Info.
    var gW0 = 0L; var gT = 0L; var gTop = 0; var gBack = 0
    val G_SPAN = 2 * 3600_000L; val G_ROWS = 6
    fun half(t: Long) = t - Math.floorMod(t, 1_800_000L)   // down to the half hour (local time zones are whole or half hours)
    fun openGrid(ch: Ch, back: Int) {
        gBack = back; val now = System.currentTimeMillis(); gW0 = half(now - 15 * 60_000L); gT = now   // at least 15 min of the past shows, so the now line is never hidden at the left edge
        show(16); val k = a.shown.indexOf(ch); sel = if (k >= 0) k else sel.coerceIn(0, maxOf(0, a.shown.size - 1)); gTop = -1; invalidate()
    }
    // the show at the focus time (or the first one after it) on a channel
    fun gridProg(ch: Ch): Prog? { val l = a.progsOf(ch); return l.firstOrNull { it.start <= gT && it.stop > gT } ?: l.firstOrNull { it.start > gT } }
    fun gridCh(): Ch? = a.shown.getOrNull(sel)
    fun gridOk(hold: Boolean) {
        val ch = gridCh() ?: return; val pr = gridProg(ch); val now = System.currentTimeMillis()
        if (hold || pr == null) { openInfo(ch, pr); return }
        when {
            pr.start <= now && pr.stop > now -> { close(); if (ch != a.cur || a.catchupProg != null) a.play(ch); a.switchTo(ch) }
            pr.start > now -> a.toggleRem(ch, pr)
            a.canCatchup(ch, pr) -> { close(); a.playCatchup(ch, pr); a.switchTo(ch) }
            else -> openInfo(ch, pr)
        }
        invalidate()
    }
    fun gridKey(up: Boolean, dn: Boolean, lf: Boolean, rt: Boolean) {
        val ch = gridCh() ?: return; val pr = gridProg(ch); val l = a.progsOf(ch); val lo = a.dayOf0()
        when {
            up -> sel = wrap(sel + dir); dn -> sel = wrap(sel - dir)
            rt -> { val nx = if (pr != null) l.firstOrNull { it.start >= pr.stop } else null
                gT = nx?.start ?: (maxOf(gT, gW0) + 1_800_000L); if (gT >= gW0 + G_SPAN) gW0 = half(gT) }
            lf -> { val pv = if (pr != null) l.lastOrNull { it.stop <= pr.start } else null
                gT = maxOf(lo, pv?.start ?: (gT - 1_800_000L)); if (gT < gW0) gW0 = maxOf(lo, half(gT)) }
        }
        if (lf || rt) gT = maxOf(gT, gW0)   // a show that began before the window is reached at the window's edge
    }
    fun drawGrid(c: Canvas, w: Float, h: Float) {
        val ch = gridCh(); val pr = ch?.let { gridProg(it) }; val now = System.currentTimeMillis()
        // the focused show over the video, on a dark scrim from the left and the bottom
        val k = T.panel and 0xFFFFFF
        // fades out downward behind the grid card's top edge, so it never ends in a hard line beside the card
        infoScrim.shader = ComposeShader(LinearGradient(0f, 0f, w, 0f, intArrayOf(0xEB000000.toInt() or k, 0xBF000000.toInt() or k, 0x26000000 or k, 0), floatArrayOf(0f, 0.45f, 0.8f, 1f), Shader.TileMode.CLAMP),
            LinearGradient(0f, 300 * U, 0f, 520 * U, -1, 0, Shader.TileMode.CLAMP), PorterDuff.Mode.DST_IN)
        c.drawRect(0f, 0f, w, 520 * U, infoScrim)
        val x = 90 * U
        if (ch != null) {
            var mx = x; val my = 88 * U
            if (pr != null && isLive(pr)) mx += live(c, mx, my, 18f) + 14 * U
            val meta = "${ch.num} · ${ch.name}" + (if (pr != null) "   ·   ${a.hm.format(pr.start)} – ${a.hm.format(pr.stop)}" + (if (isLive(pr)) "   ·   ${minLeft(pr)} min left" else "") else "")
            txt(c, meta, mx, my, w - mx - x, 28f, SEC)
            txt(c, pr?.title ?: ch.name, x, 160 * U, w - 2 * x, 72f, WHITE, 700)
            if (pr != null) a.descOf(ch, pr).takeIf { it.isNotEmpty() }?.let { para(c, it, x, 210 * U, 1000 * U, 80 * U, 29f, 0xD1FFFFFF.toInt(), 1.2f) }
            val okLab = if (pr == null) "Info" else if (pr.start <= now && pr.stop > now) "Watch" else if (pr.start > now) (if (a.hasRem(ch, pr)) "Remove Reminder" else "Remind Me") else if (a.canCatchup(ch, pr)) "Watch Catch-up" else "Info"
            keyHint(c, listOf("OK" to okLab, "Hold OK" to "Info"), x, 334 * U, 900 * U)
            val lst = listOfNotNull(if (a.grp == 0) null else a.sources.getOrNull(a.act)?.name, a.groups.getOrElse(a.grp) { "" }.ifEmpty { null }).joinToString("  ·  ")
            tp.letterSpacing = 0.05f; txt(c, "TV GUIDE  ·  ${lst.uppercase()}", w - x, 338 * U, 800 * U, 21f, TER, 600, Paint.Align.RIGHT); tp.letterSpacing = 0f
        }
        // the grid card
        val gx = M; val gy = 380 * U; val gw = w - 2 * M; val gh = h - gy - M
        glass(c, gx, gy, gw, gh, 32 * U)
        val lw = 220 * U; val tx0 = gx + 24 * U + lw + 16 * U; val tw0 = gx + gw - 24 * U - tx0; val pxm = tw0 / G_SPAN
        fun X(t: Long) = tx0 + (t - gW0) * pxm
        // header: day, half-hour marks
        val dn = ((gW0 - a.dayOf0() + 3_600_000L) / 86_400_000L).toInt()
        val dl = (if (dn == 0) "Today" else if (dn == 1) "Tomorrow" else wd.format(gW0)) + " " + java.text.SimpleDateFormat("d.M.").format(gW0)
        rr(c, gx + 24 * U, gy + 22 * U, lw, 56 * U, 28 * U, 0x1FFFFFFF); txt(c, dl, gx + 24 * U + lw / 2, gy + 50 * U, lw - 20 * U, 24f, WHITE, 600, Paint.Align.CENTER)
        var t = gW0; while (t < gW0 + G_SPAN) { txt(c, a.hm.format(t), X(t) + 10 * U, gy + 50 * U, 140 * U, 26f, SEC, 600); t += 1_800_000L }
        // rows: the list's order (REVERSED: higher numbers above, like the channel list); the window never leaves empty rows
        val n = a.shown.size; val vp = if (REVERSED) n - 1 - sel else sel; val rows = minOf(G_ROWS, n)
        if (gTop < 0) gTop = (vp - rows / 2).coerceIn(0, maxOf(0, n - rows))
        if (vp < gTop) gTop = vp; if (vp >= gTop + rows) gTop = vp - rows + 1; gTop = gTop.coerceIn(0, maxOf(0, n - rows))
        val ry = gy + 96 * U; val rh = (gh - 96 * U - 22 * U) / G_ROWS
        c.save(); c.clipRect(gx, gy, gx + gw, gy + gh)
        for (r in 0 until rows) {
            val vi = gTop + r; val cc = a.shown[if (REVERSED) n - 1 - vi else vi]; val y = ry + r * rh; val rowSel = vi == vp
            tile(c, cc, gx + 24 * U, y + 6 * U, lw, rh - 12 * U, if (rowSel) 0x33FFFFFF else FILL)
            txt(c, "${cc.num}", gx + 36 * U, y + 24 * U, 80 * U, 18f, TER, 600)
            val ps = a.progsOf(cc).filter { it.stop > gW0 && it.start < gW0 + G_SPAN }
            if (ps.isEmpty()) { rr(c, tx0, y + 6 * U, tw0 - 8 * U, rh - 12 * U, 12 * U, 0x0FFFFFFF); txt(c, "No guide data", tx0 + 18 * U, y + rh / 2, tw0 - 40 * U, 24f, TER) }
            for (p in ps) {
                val x1 = maxOf(X(p.start), tx0); val x2 = minOf(X(p.stop), tx0 + tw0); if (x2 - x1 < 10 * U) continue
                val on = rowSel && p == pr; val air = p.start <= now && p.stop > now; val cw = x2 - x1 - 8 * U
                lift(c, on, x1, y + 6 * U, cw, rh - 12 * U, 1.04f) {
                    rr(c, x1, y + 6 * U, cw, rh - 12 * U, 12 * U, if (on) (if (air) withAlpha(FOC, 0xB3) else FOC) else if (air) 0x24FFFFFF else 0x0FFFFFFF)
                    if (air) {   // the part already played is lighter (the selected show: full white), so each live show shows how far in it is
                        c.save(); mPath.reset(); mPath.addRoundRect(RectF(x1, y + 6 * U, x1 + cw, y + rh - 6 * U), 12 * U, 12 * U, Path.Direction.CW); c.clipPath(mPath)
                        fill(c, x1, y + 6 * U, minOf(X(now), x1 + cw) - x1, rh - 12 * U, if (on) FOC else 0x1FFFFFFF); c.restore()
                    }
                    if (cw > 60 * U) {
                        val col = if (on) ONF else if (p.stop <= now) SEC else WHITE
                        txt(c, (if (p.start < gW0) "‹ " else "") + p.title, x1 + 18 * U, y + rh * 0.36f, cw - 30 * U, 27f, col, 600)
                        val rem = p.start > now && a.hasRem(cc, p)
                        txt(c, "${a.hm.format(p.start)} – ${a.hm.format(p.stop)}", x1 + 18 * U, y + rh * 0.68f, cw - 30 * U, 21f, if (on) ONF2 else SEC)
                        if (rem) { val bx = x1 + 18 * U + tw("${a.hm.format(p.start)} – ${a.hm.format(p.stop)}", 21f) + 22 * U; icon(c, "bell", bx, y + rh * 0.68f, 20 * U, if (on) ONF else 0, !on) }
                    }
                }
            }
        }
        c.restore()
        // now line
        if (now >= gW0 && now < gW0 + G_SPAN) {
            val nx = X(now); fill(c, nx - 1.5f * U, gy + 84 * U, 3 * U, rows * rh + 8 * U, 0xD9FFFFFF.toInt())
            rr(c, nx - 38 * U, gy + 62 * U, 76 * U, 30 * U, 15 * U, WHITE); txt(c, a.hm.format(now), nx, gy + 77 * U, 70 * U, 19f, 0xFF1C1C1E.toInt(), 700, Paint.Align.CENTER)
        }
        tick(30_000L)   // the now line moves
    }

    // ---------- reminders (main menu): a calendar strip of days on top, the chosen day's reminders below with their channel logos.
    // LEFT / RIGHT change the day, UP / DOWN choose a reminder, OK opens its Program Info (Watch, Remove Reminder, Guide).
    fun dayOf(t: Long): Long = java.util.Calendar.getInstance().apply { timeInMillis = t; set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }.timeInMillis
    // only the days that have a reminder (soonest first); with none, a single "Today" so the page can say so
    fun remDays(rs: List<RemItem>): List<Long> = rs.map { dayOf(it.prog.start) }.distinct().ifEmpty { listOf(dayOf(System.currentTimeMillis())) }
    fun openReminders() {
        val rs = a.reminderList(); val ds = remDays(rs)
        remDay = rs.firstOrNull()?.let { ds.indexOf(dayOf(it.prog.start)).coerceAtLeast(0) } ?: 0; remSel = 0; show(14); invalidate()
    }
    // the card is only as big as what it holds (one reminder looked lost in a full-screen card), centred down the screen
    fun drawReminders(c: Canvas, w: Float, h: Float) {
        val rs = a.reminderList(); val ds = remDays(rs); val di = remDay.coerceIn(0, ds.lastIndex); val now = System.currentTimeMillis()
        val day = rs.filter { dayOf(it.prog.start) == ds[di] }
        val gap = 10 * U; val pw = 157 * U; val ph = 120 * U; val nv = minOf(7, ds.size); val rh = 124 * U
        val cw = minOf(w - 2 * M, maxOf(900 * U, nv * (pw + gap) - gap + 80 * U)); val x = M; val ix = x + 40 * U; val iw = cw - 80 * U
        val top = 112 * U + ph + 34 * U + 30 * U; val bottom = 20 * U + 80 * U
        val fit = maxOf(1, ((h - PT - M - top - bottom) / rh).toInt()); val nr = minOf(fit, maxOf(1, day.size))
        val ch0 = top + (if (day.isEmpty()) 150 * U else nr * rh) + bottom; val y0 = PT
        glass(c, x, y0, cw, ch0)
        head(c, x, y0, "Reminders", iw - 300 * U)
        txt(c, "${rs.size} set", ix + iw, y0 + 76 * U, 300 * U, 24f, SEC, 500, Paint.Align.RIGHT)
        // day strip: only days that have reminders; weekday, date, and a dot per reminder
        val sy = y0 + 112 * U; val f0 = maxOf(0, di - 6)
        ds.forEachIndexed { i, d ->
            if (i < f0 || i > f0 + 6) return@forEachIndexed
            val px = ix + (i - f0) * (pw + gap); val on = i == di; val cnt = rs.count { dayOf(it.prog.start) == d }
            rr(c, px, sy, pw, ph, FR, if (on) SELBG else 0x14FFFFFF)
            if (on) outline(c, px, sy, pw, ph, FR)
            val dn = ((d - dayOf(now) + 3_600_000L) / 86_400_000L).toInt()   // days from today (+1 h: DST)
            val lb = if (dn == 0) "Today" else if (dn == 1) "Tomorrow" else wd.format(d)
            txt(c, lb, px + pw / 2, sy + 30 * U, pw - 12 * U, 20f, if (on) WHITE else SEC, 600, Paint.Align.CENTER)
            txt(c, java.text.SimpleDateFormat("d").format(d), px + pw / 2, sy + 68 * U, pw - 12 * U, 38f, if (cnt > 0 || on) WHITE else TER, 700, Paint.Align.CENTER)
            val nd = minOf(cnt, 4); val dx0 = px + pw / 2 - (nd - 1) * 7 * U
            for (k in 0 until nd) { p.shader = null; p.color = T.accent or 0xFF000000.toInt(); c.drawCircle(dx0 + k * 14 * U, sy + 100 * U, 4.5f * U, p) }
        }
        // that day's reminders
        val ry0 = sy + ph + 34 * U
        val dd0 = ((ds[di] - dayOf(now) + 3_600_000L) / 86_400_000L).toInt()
        val dayName = if (dd0 == 0) "Today" else if (dd0 == 1) "Tomorrow" else java.text.SimpleDateFormat("EEEE d.M.").format(ds[di])
        hdr(c, dayName, ix, ry0 + 4 * U, iw)
        if (day.isEmpty()) {
            txt(c, "No reminders", ix, ry0 + 80 * U, iw, 32f, SEC, 600)
            txt(c, "Set one on an upcoming show in Program Info (press DOWN to Later on) or in the TV Guide", ix, ry0 + 124 * U, iw, 24f, TER)
        }
        val sel0 = remSel.coerceIn(0, maxOf(0, day.lastIndex)); val first = maxOf(0, sel0 - fit + 1)
        for (j in first until minOf(day.size, first + fit)) {
            val it = day[j]; val y = ry0 + 30 * U + (j - first) * rh; val on = j == sel0 && !bkF; val pr = it.prog
            lift(c, on, ix - 16 * U, y, iw + 32 * U, rh - 12 * U, 1.025f) {
                if (on) rr(c, ix - 16 * U, y, iw + 32 * U, rh - 12 * U, FR, FOC)
                val col = if (on) ONF else WHITE; val col2 = if (on) ONF2 else SEC; val cy = y + (rh - 12 * U) / 2
                txt(c, a.hm.format(pr.start), ix + 4 * U, cy, 130 * U, 36f, col, 700)
                val lx = ix + 140 * U
                if (it.ch != null) tile(c, it.ch, lx, cy - 40 * U, 144 * U, 80 * U, LOGO_BG)
                else rr(c, lx, cy - 40 * U, 144 * U, 80 * U, 10 * U, FILL)
                val tx = lx + 144 * U + 26 * U; val tmw = ix + iw - tx
                txt(c, pr.title, tx, cy - 17 * U, tmw, 31f, col, 600)
                val mins = (pr.start - now) / 60_000L
                val until = if (pr.start <= now) "starting now" else if (mins < 60) "in ${mins + 1} min" else if (mins < 24 * 60) "in ${mins / 60} h ${mins % 60} min" else ""
                txt(c, listOfNotNull(it.ch?.name, "${a.hm.format(pr.start)} – ${a.hm.format(pr.stop)}", until.ifEmpty { null }).joinToString("   ·   "), tx, cy + 22 * U, tmw, 23f, col2)
            }
        }
        keyHint(c, listOf("◀ ▶" to "day", "OK" to "Watch or Remove"), ix, y0 + ch0 - 50 * U, iw)
    }
    // ---------- hidden channels (main menu): every hidden channel with its logo; OK unhides it ----------
    fun openHidden() { hidSel = 0; show(15); invalidate() }
    // a grid of logos, 3 across so the picture stays visible (scrolls down); the card fits the number of channels (centred down the screen)
    val HID_COLS = 3
    fun drawHidden(c: Canvas, w: Float, h: Float) {
        val hs = a.hiddenList(); val cols = maxOf(1, minOf(HID_COLS, hs.size)); val tw0 = 196 * U; val th0 = 110 * U; val gx = 18 * U; val rh = th0 + 64 * U
        val cw = minOf(w - 2 * M, maxOf(700 * U, cols * (tw0 + gx) - gx + 80 * U)); val x = M; val ix = x + 40 * U; val iw = cw - 80 * U
        val top = 120 * U; val bottom = 100 * U; val nrow = (hs.size + HID_COLS - 1) / HID_COLS
        val fit = maxOf(1, ((h - PT - M - top - bottom) / rh).toInt()); val vr = minOf(fit, maxOf(1, nrow))
        val ch0 = top + (if (hs.isEmpty()) 130 * U else vr * rh) + bottom; val y0 = PT
        glass(c, x, y0, cw, ch0)
        head(c, x, y0, "Hidden Channels", iw - 300 * U)
        txt(c, "${hs.size} hidden", ix + iw, y0 + 76 * U, 300 * U, 24f, SEC, 500, Paint.Align.RIGHT)
        if (hs.isEmpty()) {
            txt(c, "No hidden channels", ix, y0 + top + 30 * U, iw, 32f, SEC, 600)
            txt(c, "Hide one from the channel list with its Hide Channel button", ix, y0 + top + 74 * U, iw, 24f, TER)
            return
        }
        val sel0 = hidSel.coerceIn(0, hs.lastIndex); val fr = maxOf(0, sel0 / HID_COLS - fit + 1)
        for (j in fr * HID_COLS until minOf(hs.size, (fr + vr) * HID_COLS)) {
            val ch = hs[j]; val tx = ix + (j % HID_COLS) * (tw0 + gx); val ty = y0 + top + (j / HID_COLS - fr) * rh; val on = j == sel0 && !bkF
            lift(c, on, tx, ty, tw0, th0, 1.08f) { tile(c, ch, tx, ty, tw0, th0, if (on) SELBG else FILL); if (on) outline(c, tx, ty, tw0, th0, 10 * U) }   // no white focus: most logos are white
            txt(c, "${ch.num}: ${ch.name}", tx + tw0 / 2, ty + th0 + 30 * U, tw0 + gx - 6 * U, 22f, if (on) WHITE else SEC, if (on) 600 else 400, Paint.Align.CENTER)
        }
        keyHint(c, listOf("OK" to "Unhide"), ix, y0 + ch0 - 50 * U, iw)
    }
    // a page card like Reminders / Hidden Channels: sized to its rows (scrolls when they do not fit), centred down the screen,
    // notes and key hints inside the card at the bottom
    // movable: rows 0 until movable can be reordered (like the Playlists panel: a grip on the selected one, arrows + theme outline while picked up)
    fun drawListCard(c: Canvas, w: Float, h: Float, pw: Float, title: String, count: String, items: List<String>, selI: Int, grab: Boolean,
                     empty: String, notes: List<String>, hints: List<Pair<String, String>>, movable: Int = 0) {
        val x = M; val ix = x + 40 * U; val iw = pw - 80 * U; val rh = 80 * U; val top = 112 * U
        val bottom = notes.size * 40 * U + (if (items.isEmpty()) 0f else 100 * U) + (if (notes.isEmpty()) 0f else 20 * U)
        val fit = maxOf(1, ((h - PT - M - top - bottom) / rh).toInt()); val nr = minOf(fit, maxOf(1, items.size))
        val ch0 = top + (if (items.isEmpty()) 110 * U else nr * rh) + bottom; val y0 = PT
        glass(c, x, y0, pw, ch0)
        head(c, x, y0, title, iw - 300 * U)   // both list cards open from Settings
        txt(c, count, ix + iw, y0 + 76 * U, 300 * U, 24f, SEC, 500, Paint.Align.RIGHT)
        if (items.isEmpty()) txt(c, empty, ix, y0 + top + 30 * U, iw, 32f, SEC, 600)
        val sel0 = selI.coerceIn(0, maxOf(0, items.lastIndex)); val first = maxOf(0, sel0 - fit + 1)
        for (j in first until minOf(items.size, first + fit)) {
            val y = y0 + top + (j - first) * rh; val on = j == sel0 && !bkF; val bx = x + 14 * U; val bw = pw - 28 * U; val bh = rh - 12 * U
            val handle = if (!on || j >= movable) null else if (grab) "updown" else "grip"
            lift(c, on, bx, y, bw, bh, 1.025f) {
                if (on) rr(c, bx, y, bw, bh, FR, FOC)
                if (grab && on) outline(c, bx, y, bw, bh, FR, T.accent or 0xFF000000.toInt(), 4f)
                stxt(c, items[j], ix, y + bh / 2, iw - (if (handle != null) 50 * U else 0f), 31f, if (on) ONF else WHITE, if (on) 600 else 500, on)
                if (handle != null) icon(c, handle, bx + bw - 40 * U, y + bh / 2, 28 * U, ONF)
            }
        }
        var ny = y0 + top + (if (items.isEmpty()) 110 * U else nr * rh) + 20 * U
        for (n in notes) { txt(c, n, ix, ny, iw, 24f, SEC); ny += 40 * U }
        if (items.isNotEmpty()) keyHint(c, hints, ix, y0 + ch0 - 50 * U, iw)
    }
    // error log (mode 10): the last problems since the app started, newest first; UP / DOWN scroll, OK clears the log
    fun drawErrLog(c: Canvas, w: Float, h: Float) {
        val items = a.errLog.toList()
        drawListCard(c, w, h, w * 0.62f, "Error Log", if (items.isEmpty()) "" else "${items.size} entries", items, logSel, false,
            "No problems since the app started", emptyList(), listOf("UP / DOWN" to "scroll", "OK" to "clear the log"))   // Back needs no hint: it goes back everywhere
    }

    // favorite group order (mode 9): UP / DOWN choose a group, OK (or hold OK, as on Playlists) picks it up, UP / DOWN then moves it, OK drops it
    fun drawFavGroups(c: Canvas, w: Float, h: Float) {
        val gn = a.favGroupNames(); val names = gn.map { it.second }
        drawListCard(c, w, h, 680 * U, "Favorite Groups", if (names.isEmpty()) "" else "${names.size} groups", names, grpSel, grpGrab, "No favorites yet",
            if (names.isEmpty()) emptyList() else listOf(if (REVERSED) "Top of this list = bottom of the channel list" else "Top of this list = top of the channel list", "\"Other\" always stays last"),
            if (grpGrab) listOf("UP / DOWN" to "move", "OK" to "drop") else listOf("OK" to "move"), gn.count { it.first != "other" })
    }

    // ---------- themes: a colour list (and the seasonal themes) with a preview of where the colour shows ----------
    fun drawThemes(c: Canvas, w: Float, h: Float) {
        val pw = w * 0.24f
        // rows shrink a little when the list would run into the key hints (11 themes did: New Year's highlight covered them)
        val y0 = PT + 120 * U; val rh = minOf(78 * U, (h - M - 20 * U - y0 - 58 * U) / THEMES.size)
        glass(c, 0f, PT, pw, h - PT - M); head(c, 0f, PT, "Themes", pw - 150 * U)
        var y = y0
        THEMES.forEachIndexed { i, t ->
            if (i == FIRST_SEASONAL) { hdr(c, "Seasonal", 40 * U, y + 33 * U, pw - 100 * U); y += 58 * U }
            val on = i == themeSel && !bkF; val bx = 20 * U; val bw = pw - 40 * U
            lift(c, on, bx, y + 4 * U, bw, rh - 8 * U, 1.03f) {
                if (on) rr(c, bx, y + 4 * U, bw, rh - 8 * U, FR, FOC)
                val cy = y + rh / 2; val dx = bx + 41 * U
                if (t.seasonal) {   // half menu colour, half accent
                    c.save(); val cp = Path(); cp.addCircle(dx, cy, 15 * U, Path.Direction.CW); c.clipPath(cp)
                    fill(c, dx - 15 * U, cy - 15 * U, 30 * U, 30 * U, t.panel or 0xFF000000.toInt())
                    val tri = Path(); tri.moveTo(dx + 15 * U, cy - 15 * U); tri.lineTo(dx + 15 * U, cy + 15 * U); tri.lineTo(dx - 15 * U, cy + 15 * U); tri.close()
                    p.color = t.accent; c.drawPath(tri, p); c.restore()
                } else { p.shader = null; p.color = t.accent; c.drawCircle(dx, cy, 15 * U, p) }
                p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * U; p.color = 0x59FFFFFF; c.drawCircle(dx, cy, 15 * U, p); p.style = Paint.Style.FILL
                txt(c, t.name, bx + 78 * U, cy, bw - 140 * U, 31f, if (on) ONF else WHITE, if (on) 600 else 500)
                if (i == a.themeIdx) icon(c, "check", bx + bw - 40 * U, cy, 30 * U, if (on) ONF else WHITE)
            }
            y += rh
        }
        // preview: the only places the colour appears
        val x = pw + w * 0.04f; val pW = 760 * U; val pH = 520 * U; val py = PT   // only as big as its contents, top in line with the list
        glass(c, x, py, pW, pH)
        hdr(c, "Where the colour shows", x + 40 * U, py + 48 * U, pW - 80 * U)
        val labels = listOf("Chosen option", "Reminders", "App name in the menu")   // the playing channel is a red dot now, not the theme colour
        for (k in 0..2) {
            val ry = py + 90 * U + k * 106 * U; val cy = ry + 46 * U; val lx = x + 68 * U
            rr(c, x + 40 * U, ry, pW - 80 * U, 92 * U, 18 * U, 0x12FFFFFF)
            when (k) {
                0 -> { icon(c, "check", lx + 14 * U, cy, 30 * U, 0, true); txt(c, "All", lx + 54 * U, cy, 300 * U, 29f, WHITE, 600) }
                1 -> { icon(c, "bell", lx + 10 * U, cy, 24 * U, 0, true); tp.letterSpacing = 0.03f; txt(c, "REMINDER SET", lx + 32 * U, cy, 400 * U, 24f, 0, 700, accent = true); tp.letterSpacing = 0f }
                else -> { txt(c, "Lite", lx, cy, 200 * U, 34f, WHITE, 700); txt(c, "TV", lx + tw("Lite", 34f, 700), cy, 200 * U, 34f, 0, 700, accent = true) }
            }
            txt(c, labels[k], x + pW - 68 * U, cy, 500 * U, 24f, SEC, 500, Paint.Align.RIGHT)
        }
        val t = THEMES[themeSel.coerceIn(0, THEMES.lastIndex)]
        para(c, if (t.seasonal) "Seasonal themes also colour the menu background and the highlight, and bring effects of their own. " +
                "Progress bars stay " + (if (t.gold) "gold" else "white") + ", red for live."
            else "The selected row is outlined in it too. Everything else stays neutral: white focus, white progress bars, red for live.",
            x + 40 * U, py + 420 * U, pW - 80 * U, 80 * U, 24f, SEC)
    }

    // ---------- program info: full-screen page over the video ----------
    val infoScrim = Paint(); val infoScrim2 = Paint()
    fun infoButtons(ch: Ch, pr: Prog?): List<Pair<String, String>> {
        val now = System.currentTimeMillis(); val out = mutableListOf<Pair<String, String>>()
        val cu = pr != null && pr.stop <= now && a.canCatchup(ch, pr)
        out.add((if (cu) "Watch Catch-up" else if (pr != null && isLive(pr)) "Watch Live" else "Watch") to "play")
        if (pr != null && pr.start > now) out.add((if (a.hasRem(ch, pr)) "Remove Reminder" else "Remind Me") to "bell")
        out.add("Guide" to "grid")
        return out
    }
    fun laterProgs(ch: Ch, pr: Prog?): List<Prog> { val now = System.currentTimeMillis(); return a.progsOf(ch).filter { if (pr != null) it.start >= pr.stop else it.start > now }.take(4) }
    fun drawInfo(c: Canvas, w: Float, h: Float) {
        val ch = infoCh ?: return; val pr = infoProg; val now = System.currentTimeMillis(); val x = 120 * U
        val k = T.panel and 0xFFFFFF
        infoScrim.shader = LinearGradient(0f, 0f, w, 0f, intArrayOf(0xF2000000.toInt() or k, 0xD1000000.toInt() or k, 0x40000000 or k, 0x26000000 or k), floatArrayOf(0f, 0.42f, 0.78f, 1f), Shader.TileMode.CLAMP)
        infoScrim2.shader = LinearGradient(0f, h, 0f, h * 0.55f, intArrayOf(0xF7000000.toInt() or k, k), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, infoScrim); c.drawRect(0f, h * 0.55f, w, h, infoScrim2)
        txt(c, a.hm.format(now), w - 90 * U, 80 * U, 200 * U, 38f, WHITE, 600, Paint.Align.RIGHT)
        a.logoOf(ch)?.let { b -> val s = minOf(300 * U / b.width, 110 * U / b.height); p.shader = null; c.drawBitmap(b, null, RectF(x, 100 * U + (110 * U - b.height * s) / 2, x + b.width * s, 100 * U + (110 * U + b.height * s) / 2), p) }
        txt(c, pr?.title ?: ch.name, x, 284 * U, w - 2 * x, 84f, WHITE, 700)
        var mx = x; val my = 372 * U
        if (pr != null && pr.start > now) {
            val s = "Starts in ${(pr.start - now) / 60000 + 1} min".let { if (pr.start - now > 120 * 60000L) "Upcoming" else it }
            txt(c, s, mx, my, 400 * U, 28f, WHITE, 600); mx += tw(s, 28f, 600) + 16 * U
            if (a.hasRem(ch, pr)) { icon(c, "bell", mx + 11 * U, my, 22 * U, 0, true); tp.letterSpacing = 0.03f; txt(c, "REMINDER SET", mx + 28 * U, my, 300 * U, 21f, 0, 700, accent = true); mx += tw("REMINDER SET", 21f, 700) + 46 * U; tp.letterSpacing = 0f }
        } else if (pr != null && isLive(pr)) mx += live(c, mx, my, 18f) + 14 * U
        if (ch.group.isNotEmpty()) {
            val gw = tw(ch.group, 20f, 600) + 20 * U
            p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * U; p.color = 0x80FFFFFF.toInt(); c.drawRoundRect(RectF(mx, my - 18 * U, mx + gw, my + 18 * U), 6 * U, 6 * U, p); p.style = Paint.Style.FILL
            txt(c, ch.group, mx + 10 * U, my, gw, 20f, WHITE, 600); mx += gw + 14 * U
        }
        val meta = mutableListOf("${ch.num} · ${ch.name}")
        if (pr != null) { meta.add((if (pr.start > now + 86400000L || pr.stop < now - 3600000L) java.text.SimpleDateFormat("EEE d.M.").format(pr.start) + " " else "") + "${a.hm.format(pr.start)} – ${a.hm.format(pr.stop)}"); meta.add("${(pr.stop - pr.start) / 60000} min") }
        txt(c, meta.joinToString("   ·   "), mx, my, w - mx - x, 28f, SEC)
        if (pr != null && isLive(pr)) { progress(c, x, 416 * U, 520 * U, pr, 6f); txt(c, "${minLeft(pr)} min left", x + 540 * U, 419 * U, 300 * U, 24f, SEC) }
        if (pr != null) a.descOf(ch, pr).takeIf { it.isNotEmpty() }?.let { para(c, it, x, 450 * U, 980 * U, 135 * U, 31f, 0xD6FFFFFF.toInt(), 1.2f) }
        // buttons
        var bx = x; infoButtons(ch, pr).forEachIndexed { i, (lab, ic) ->
            val bw = maxOf(250 * U, tw(lab, 29f, 600) + 110 * U); button(c, bx, 600 * U, bw, 84 * U, i == infoBtn && infoRow == 0, ic, lab); bx += bw + 18 * U
        }
        // later on this channel: DOWN moves here, OK sets or removes a reminder for the focused show
        val later = laterProgs(ch, pr)
        if (later.isNotEmpty()) {
            hdr(c, "Later on ${ch.name}", x, 785 * U, w - 2 * x)
            later.forEachIndexed { i, lp ->
                val cx = x + i * 430 * U; val cy = 812 * U; val f = infoRow == 1 && i == laterSel.coerceIn(0, later.lastIndex)
                lift(c, f, cx, cy, 400 * U, 170 * U, 1.06f, base = 0x14FFFFFF) {
                    rr(c, cx, cy, 400 * U, 170 * U, FR, if (f) FOC else 0x14FFFFFF)
                    txt(c, a.hm.format(lp.start), cx + 24 * U, cy + 38 * U, 200 * U, 24f, if (f) ONF2 else SEC, 600)
                    if (a.hasRem(ch, lp)) { tp.letterSpacing = 0.03f; val rw = tw("REMINDER", 20f, 700); txt(c, "REMINDER", cx + 376 * U - rw, cy + 38 * U, rw + 4 * U, 20f, if (f) ONF else 0, 700, accent = !f); tp.letterSpacing = 0f; icon(c, "bell", cx + 362 * U - rw, cy + 38 * U, 22 * U, if (f) ONF else 0, !f) }
                    para(c, lp.title, cx + 24 * U, cy + 64 * U, 352 * U, 80 * U, 28f, if (f) ONF else WHITE, 1.1f)
                }
            }
            if (infoRow == 1) later.getOrNull(laterSel)?.let { lp -> keyHint(c, listOf("OK" to (if (a.hasRem(ch, lp)) "Remove Reminder" else "Remind Me")), x, 1022 * U, 600 * U) }
        }
    }

    // ---------- reminder card (top right) ----------
    fun drawReminder(c: Canvas, w: Float) {
        val ch = remCh ?: return; val pr = remProg ?: return
        val nx = w - 90 * U - 700 * U; val ny = 60 * U
        matRect(c, nx, ny, 700 * U, 250 * U, 24 * U)
        tile(c, ch, nx + 30 * U, ny + 30 * U, 150 * U, 84 * U, 0x1FFFFFFF)
        icon(c, "bell", nx + 215 * U, ny + 44 * U, 22 * U, 0, true)
        tp.letterSpacing = 0.05f; txt(c, "STARTING NOW", nx + 236 * U, ny + 44 * U, 400 * U, 21f, 0, 700, accent = true); tp.letterSpacing = 0f
        txt(c, pr.title, nx + 204 * U, ny + 82 * U, 470 * U, 32f, WHITE, 700)
        txt(c, "${ch.num} · ${ch.name} · ${a.hm.format(pr.start)} – ${a.hm.format(pr.stop)}", nx + 204 * U, ny + 117 * U, 470 * U, 22f, SEC)
        button(c, nx + 30 * U, ny + 150 * U, 310 * U, 72 * U, remBtn == 0, "play", "Watch", 28f, 1.05f)
        button(c, nx + 356 * U, ny + 150 * U, 314 * U, 72 * U, remBtn == 1, null, "Dismiss", 28f, 1.05f)
        val f = 1f - ((SystemClock.uptimeMillis() - remAt) / 20_000f).coerceIn(0f, 1f)
        rr(c, nx + 30 * U, ny + 232 * U, 640 * U, 8 * U, 4 * U, 0x1FFFFFFF); rr(c, nx + 30 * U, ny + 232 * U, 640 * U * f, 8 * U, 4 * U, 0x73FFFFFF)
    }

    // UP / DOWN in a menu list (everything but the channel lists, which have triple-tap): holding the key stops at the
    // first / last place, a fresh press there goes round to the other end. Places run 0..n-1, plus -1 = the back button.
    fun step(cur: Int, d: Int, n: Int, e: KeyEvent, back: Boolean): Int {
        val lo = if (back) -1 else 0; val hi = maxOf(lo, n - 1); val nx = cur + d
        return when { nx in lo..hi -> nx; e.repeatCount > 0 -> cur.coerceIn(lo, hi); d > 0 -> lo; else -> hi }
    }
    override fun onKeyDown(k: Int, e: KeyEvent): Boolean {
        val up = k == KeyEvent.KEYCODE_DPAD_UP; val dn = k == KeyEvent.KEYCODE_DPAD_DOWN
        val lf = k == KeyEvent.KEYCODE_DPAD_LEFT; val rt = k == KeyEvent.KEYCODE_DPAD_RIGHT
        val ok = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
        if (e.repeatCount == 0) okIdle = false   // any other button keeps the program info up the usual 30 s
        if (k == KeyEvent.KEYCODE_BACK) {
            when {
                side && (plMenu >= 0 || plAsk >= 0) -> { plMenu = -1; plAsk = -1 }
                side && plGrab -> plGrab = false
                side -> side = false
                mode == 4 -> { if (subOpen) subOpen = false else if (backMode == 0) close() else mode = backMode }
                mode == 5 -> if (infoBack == 4) close() else { mode = infoBack; if (mode == 14) remSel = remSel.coerceAtLeast(0) }   // from the OK tiles: back to the picture, not the tiles
                mode == 6 -> { a.setTheme(a.themeIdx, false); mode = 8 }   // back to settings, preview dropped
                mode == 8 -> if (setCat >= 0) { setSel = setCat; setCat = -1; bkF = false } else { a.settingsDone(); mode = 3 }   // a category: back to the categories
                mode == 9 -> { if (grpGrab) grpGrab = false else mode = 8 }   // drop the group, then back to settings
                mode == 10 || mode == 13 -> mode = 8                        // back to settings
                mode == 16 -> if (gBack == 0) close() else mode = gBack
                mode == 17 -> if (srch.acc != null) srch.acc = null else if (srch.foc == 0 && srch.q.isNotEmpty()) { srch.q = srch.q.dropLast(1); suggest() }   // BACK on the letters deletes one
                    else if (srch.foc > 0) { srch.foc = 0; srch.key = searchKeys(srch.num, searchMic()).indexOf(if (srch.num) "1" else "a").coerceAtLeast(0) } else { if (a.searchQ == null) a.endSearch(); mode = 3 }   // results: back to the letters, then the main menu
                mode == 14 || mode == 15 -> mode = 3                                      // back to the main menu
                mode == 12 -> if (rbSub > 0) rbSub = 0 else mode = 8          // back to settings
                else -> close()
            }
            touch(); invalidate(); return true
        }
        if (mode == 17) { val r = searchKey(k, e); touch(); invalidate(); return r }
        if (bkF && mode in listOf(6, 8, 9, 10, 12, 13, 14, 15)) {
            if (ok) { bkF = false; return onKeyDown(KeyEvent.KEYCODE_BACK, e) }
            if (!(up || dn) || mode == 13) { touch(); invalidate(); return true }   // UP / DOWN: the screen's list moves on from the back button
        }
        if (mode == 12 && rbSub == 2) {   // waiting for a button: whatever comes next is added to the selected action
            val act = keyRows().getOrNull(rbSel)?.act
            if (e.repeatCount == 0 && act != null) {
                if (!keyUsable(k)) a.flash("That button can't be used here", 2000)
                else { val from = a.keys.add(act, k); a.flash(keyLabel(k) + (if (from != null) " moved from ${from.name}" else " added"), 2500); rbSub = 0 }
            }
            touch(); invalidate(); return true
        }
        // three quick taps of UP / DOWN in the channel list (and the guide's channel column) jump to the top / bottom on screen.
        // Holding the key (repeats) never counts, so fast scrolling and a couple of ordinary taps still move row by row.
        if ((up || dn) && !side && (mode == 1 || mode == 2 && col == 1) && a.shown.isNotEmpty()) {
            tapN = if (e.repeatCount > 0) 0 else if (k == tapK && e.eventTime - tapAt < 180) tapN + 1 else 1
            tapK = k; tapAt = e.eventTime
            if (tapN >= 3) {
                tapN = 0
                val old = sel
                sel = if (up == REVERSED) a.shown.lastIndex else 0   // reversed order draws index 0 at the bottom
                jumpFrom(old)
                if (mode == 2) progI = defProg()
                touch(); invalidate(); return true
            }
        }
        if (!(up || dn || lf || rt || ok)) return super.onKeyDown(k, e)
        if ((up || dn) && !side && e.repeatCount > 0 && (mode == 1 || mode == 2 && col == 1)) {   // holding the key stops at the list's end; a fresh press goes round
            val nx = sel + if (up) dir else -dir
            if (nx < 0 || nx >= a.shown.size) { touch(); return true }
        }
        val ch = a.shown.getOrNull(sel)
        if (side) {
            val n = maxOf(1, if (sideFocus == 2) plItems().size else catItems().size)
            when {
                plAsk >= 0 -> when {
                    up || dn -> plAsk = step(plAsk, if (up) -1 else 1, 2, e, false)
                    ok -> { val id = a.sources.getOrNull(plSel - 1)?.id; val yes = plAsk == 0; plAsk = -1; if (yes && id != null) a.removePl(id) }
                    lf -> plAsk = -1
                }
                plMenu >= 0 -> when {
                    up || dn -> plMenu = step(plMenu, if (up) -1 else 1, 3, e, false)
                    ok -> { val k = plMenu; plMenu = -1; if (k == 0) plGrab = true else if (k == 1) plAsk = 1 }
                    lf -> plMenu = -1
                }
                (up || dn) && plGrab -> { val to = plSel + (if (up) -1 else 1); if (to in 1..a.sources.size) { a.movePl(plSel - 1, to - 1); plSel = to } }   // row 0 (Favorites) stays first
                up || dn -> { val d = if (up) -1 else 1; if (sideFocus == 2) plSel = step(plSel, d, n, e, false) else catSel = step(catSel, d, n, e, false) }
                plGrab -> if (ok) plGrab = false   // OK drops it; LEFT / RIGHT do nothing while it is held
                lf -> if (sideFocus == 1) { sideLvl = 2; sideFocus = 2; plSel = plActive(); plTip() }
                rt -> if (sideFocus == 2) { sideLvl = 1; sideFocus = 1 } else sideLvl = 0
                ok -> if (sideFocus == 2) {
                    if (plSel == 0) { a.favGrp = null; a.selectGroup(0) }
                    else { val i = plSel - 1; if (i == a.act) a.selectGroup(1) else a.activate(i, false) }   // a playlist opens on All
                    catSel = 0; sideFocus = 1
                } else {
                    sideLvl = 0
                    if (a.grp == 0) { a.favGrp = a.favGroupNames().getOrNull(catSel - 1)?.first; a.selectGroup(0) } else a.selectGroup(catSel + 1)
                }
            }
        } else if (mode == 11) {
            when {
                lf || rt -> remBtn = 1 - remBtn
                ok -> { val c0 = remCh; close(); if (remBtn == 0 && c0 != null) { a.play(c0); a.switchTo(c0) } }
            }
        } else if (mode == 3) {
            val n = a.menuItems().size
            when { up || dn -> menuSel = step(menuSel, if (up) -1 else 1, n, e, false); ok -> a.menuAct(menuSel) }
        } else if (mode == 4) {
            val n = if (subI == -1) 3 else if (subI < 0) 0 else a.subOpts(subI).first.size
            when {
                up -> if (subOpen && subI == -1) oj = step(oj, -1, n, e, false) else if (subOpen) oj = step(oj, dir, n, e, false) else if (backMode == 0) a.zap(dir)
                dn -> if (subOpen && subI == -1) oj = step(oj, 1, n, e, false) else if (subOpen) oj = step(oj, -dir, n, e, false) else if (backMode == 0) a.zap(-dir)
                lf -> if (quickHide && backMode == 0) open(1) else if (!subOpen) { subI = maxOf(if (backMode == 0) -3 else 0, subI - 1) } else if (subI == 3) oj = maxOf(0, oj - a.AVD_JUMP)
                rt -> if (quickHide && backMode == 0) open(2) else if (!subOpen) { subI = minOf(3, subI + 1) } else if (subI == 3) oj = minOf(n - 1, oj + a.AVD_JUMP)
                ok -> if (quickHide && a.held() && backMode == 0) a.pinFull()   // the "pinned" notice: OK watches it full screen
                else if (quickHide) quickHide = false   // after a channel change the first OK only picks the tiles up
                else if (subI == -3) { a.subCh()?.let { openInfo(it, a.now(it)) } }
                else if (subI == -2) { a.subCh()?.let { openGrid(it, 0) } }   // BACK closes it: the tiles do not come back
                else if (subI == -1) {   // Pin: pins the channel playing; once pinned, a list: Swap, Corner (each OK moves it on a corner, the list stays), Unpin
                    if (a.pip.ch == null) a.cur?.let { a.pinCh(it) }
                    else if (!subOpen) { oj = 0; subOpen = true }
                    else when (oj) { 0 -> { subOpen = false; a.swapPin() }; 1 -> a.pip.nextCorner(); else -> { subOpen = false; a.unpin() } }
                }
                else if (subOpen) { a.subApply(subI, oj); subOpen = false } else {
                    val o = a.subOpts(subI)
                    if (o.first.size > 1) { oj = o.second.coerceAtLeast(0); subOpen = true }   // one option: the tile is dimmed and says so
                }
            }
        } else if (mode == 5) {
            val c0 = infoCh; val p0 = infoProg; val bs = if (c0 != null) infoButtons(c0, p0) else emptyList()
            val later = if (c0 != null) laterProgs(c0, p0) else emptyList()
            when {
                dn -> if (later.isNotEmpty()) { infoRow = 1; laterSel = laterSel.coerceIn(0, later.lastIndex) }
                up -> infoRow = 0
                lf -> if (infoRow == 1) laterSel = maxOf(0, laterSel - 1) else infoBtn = maxOf(0, infoBtn - 1)
                rt -> if (infoRow == 1) laterSel = minOf(later.lastIndex, laterSel + 1) else infoBtn = minOf(bs.size - 1, infoBtn + 1)
                ok && infoRow == 1 -> if (c0 != null) later.getOrNull(laterSel)?.let { a.toggleRem(c0, it) }
                ok -> if (c0 != null) when (bs.getOrNull(infoBtn)?.second) {
                    "play" -> { close(); if (p0 != null && p0.stop <= System.currentTimeMillis() && a.canCatchup(c0, p0)) a.playCatchup(c0, p0) else if (c0 != a.cur || a.catchupProg != null) a.play(c0) }   // already watching it live: just close
                    "bell" -> if (p0 != null) a.toggleRem(c0, p0)
                    "grid" -> openGuide(c0)
                }
            }
        } else if (mode == 8) {
            val n = if (setCat < 0) SET_CATS.size else SET_CATS[setCat].rows.size
            when {
                up || dn -> { val r = step(if (bkF) -1 else setSel, if (up) -1 else 1, n, e, true); bkF = r < 0; if (r >= 0) setSel = r }   // -1: the back button
                setCat < 0 -> if (ok || rt) { if (SET_CATS[setSel].rows.size == 1) a.settingAct(SET_CATS[setSel].rows[0], 1) else { setCat = setSel; setSel = 0; bkF = false } }   // Remote Buttons opens its page
                else -> { val i = SET_CATS[setCat].rows[setSel.coerceIn(0, n - 1)]; if (lf) a.settingAct(i, -1) else if (rt || ok) a.settingAct(i, 1) }
            }
        } else if (mode == 9) {
            val gn = a.favGroupNames(); val n = gn.size; val mv = gn.count { it.first != "other" }   // "Other" is pinned last and cannot be moved
            when {
                up || dn -> {
                    val dl = if (up) -1 else 1
                    if (grpGrab) { val to = grpSel + dl; if (to in 0 until mv) { a.moveFavGroup(grpSel, to); grpSel = to } }
                    else { val r = step(if (bkF) -1 else grpSel, dl, n, e, true); bkF = r < 0; if (r >= 0) grpSel = r }
                }
                ok -> if (grpSel < mv) grpGrab = !grpGrab
            }
        } else if (mode == 12) {
            val rows = keyRows(); val r = rows.getOrNull(rbSel)
            when {
                (up || dn) && rbSub == 1 -> rbOpt = 1 - rbOpt
                up || dn -> { val r = step(if (bkF) -1 else rbSel, if (up) -1 else 1, rows.size, e, true); bkF = r < 0; if (r >= 0) rbSel = r }
                ok && rbSub == 1 -> if (rbOpt == 0) rbSub = 2 else { a.keys.reset(r?.act); a.flash("${r?.label} reset", 2000); rbSub = 0 }
                ok && r?.reset == true -> { a.keys.reset(null); a.flash("All buttons reset", 2000) }
                ok && r?.act != null -> { rbSub = 1; rbOpt = 0 }
            }
        } else if (mode == 13) {
            // About has nothing to select; only Back (handled above)
        } else if (mode == 16) {
            if (ok) gridOk(false) else gridKey(up, dn, lf, rt)
        } else if (mode == 15) {
            val hs = a.hiddenList()
            when {
                lf -> if (hidSel % HID_COLS > 0) hidSel--
                rt -> if (hidSel % HID_COLS < HID_COLS - 1 && hidSel + 1 < hs.size) hidSel++
                up -> if (bkF) { if (e.repeatCount == 0 && hs.isNotEmpty()) { bkF = false; hidSel = hs.size - 1 } }   // round to the last logo
                    else if (hidSel >= HID_COLS) hidSel -= HID_COLS else bkF = true
                dn -> if (bkF) { bkF = false; hidSel = 0 }
                    else if (hidSel + HID_COLS < hs.size) hidSel += HID_COLS else if (hidSel / HID_COLS < (hs.size - 1) / HID_COLS) hidSel = hs.size - 1   // short last row: go to its last logo
                    else if (e.repeatCount == 0) bkF = true   // the last row: a fresh press goes round to the back button
                ok -> hs.getOrNull(hidSel)?.let { a.unhide(it); hidSel = hidSel.coerceIn(0, maxOf(0, hs.size - 2)) }
            }
        } else if (mode == 14) {
            val rs = a.reminderList(); val ds = remDays(rs); remDay = remDay.coerceIn(0, ds.lastIndex)
            val day = rs.filter { dayOf(it.prog.start) == ds[remDay] }
            when {
                lf -> if (remDay > 0) { remDay--; remSel = 0 }
                rt -> if (remDay < ds.lastIndex) { remDay++; remSel = 0 }
                up || dn -> { val r = step(if (bkF) -1 else remSel.coerceAtLeast(0), if (up) -1 else 1, day.size, e, true); bkF = r < 0; if (r >= 0) remSel = r }
                ok -> day.getOrNull(remSel)?.let { r -> r.ch?.let { openInfo(it, r.prog) } ?: run { a.removeRem(r.key); remSel = 0 } }   // channel gone from the playlists: OK just removes it
            }
        } else if (mode == 10) {
            val n = a.errLog.size
            when { up || dn -> { val r = step(if (bkF) -1 else logSel, if (up) -1 else 1, n, e, true); bkF = r < 0; if (r >= 0) logSel = r }; ok -> { a.errLog.clear(); logSel = 0 } }
        } else if (mode == 6) {
            val n = THEMES.size
            when {
                up || dn -> { val r = step(if (bkF) -1 else themeSel, if (up) -1 else 1, n, e, true); bkF = r < 0; if (r >= 0 && r != themeSel) { themeSel = r; a.setTheme(themeSel, false) } }   // moving through the list previews each theme live
                ok -> a.setTheme(themeSel, true)
            }
        } else if (mode == 1) {
            when {
                up -> { val o = sel; sel = wrap(sel + dir); jumpFrom(o) }
                dn -> { val o = sel; sel = wrap(sel - dir); jumpFrom(o) }
                lf -> if (oi > 0) oi-- else if (e.repeatCount == 0) openSide()
                rt -> oi = minOf(oi + 1, a.nOpts(false) - 1)
                ok -> if (ch != null) a.chOption(ch, oi)
            }
        } else {
            val dys = days(); val dp = if (ch != null) a.dayProgs(ch, dys[dayI.coerceIn(0, dys.lastIndex)]) else emptyList()
            when {
                up || dn -> {
                    val sg = if (up) dir else -dir
                    when (col) {
                        0 -> { dayI = (dayI + sg).coerceIn(0, dys.lastIndex); progI = defProg() }
                        1 -> { val o = sel; sel = wrap(sel + sg); jumpFrom(o); progI = defProg() }
                        else -> {   // UP = later show (drawn above), DOWN = earlier show; past midnight the guide moves on to the next / previous day
                            val cp = dp.getOrNull(progI)
                            if (up && progI >= dp.lastIndex && cp != null && ch != null && dayI < dys.lastIndex) {
                                val k = a.dayProgs(ch, dys[dayI + 1]).indexOfFirst { it.start > cp.start }   // skips the show that runs over midnight (it is in both days)
                                if (k >= 0) { dayI++; progI = k }
                            } else if (!up && progI <= 0 && cp != null && ch != null && dayI > 0) {
                                val k = a.dayProgs(ch, dys[dayI - 1]).indexOfLast { it.start < cp.start }
                                if (k >= 0) { dayI--; progI = k }
                            } else progI = (progI + (if (up) 1 else -1)).coerceIn(0, maxOf(0, dp.size - 1))
                            val np = if (ch != null) a.dayProgs(ch, dys[dayI]).getOrNull(progI) else null
                            oi = oi.coerceIn(0, maxOf(0, a.nOpts(true, ch, np) - 1))
                        }
                    }
                }
                lf -> if (col == 2 && oi > 0) oi-- else if (e.repeatCount > 0) Unit else if (col > 0) col-- else openSide()
                rt -> if (col < 2) col++ else oi = minOf(oi + 1, a.nOpts(true, ch, dp.getOrNull(progI)) - 1)
                ok -> if (col == 0) col = 2 else if (ch != null) a.chOption(ch, oi, if (col == 2) dp.getOrNull(progI) else null)   // OK on a date goes to its shows
            }
        }
        touch(); invalidate(); return true
    }

    // ---------- blur: a small copy of the video frame, blurred, refreshed ~3 times a second while a screen is open ----------
    // Live blur of the video behind the menus, the way Apple's material does it: a blurred copy of what is behind,
    // colour boosted, then the tint on top. Copies are taken and blurred on a background thread (on Android 7-13
    // PixelCopy works before it returns, so on the main thread it would hold up the remote); between copies the
    // picture fades from the previous blurred frame to the newest at the screen's refresh rate, so it moves smoothly.
    inner class Blur {
        val bw = 192; val bh = 108   // copy size; stretched 10x to the screen, which the blur makes invisible (384x216 was 4x the work for the same look)
        val bg = android.os.HandlerThread("blur").apply { start() }; val bgH = Handler(bg.looper)
        val shapes = HashMap<View, BlurShapes>(); var cur: BlurShapes? = null
        var src: Bitmap? = null; @Volatile var out: Bitmap? = null; var prev: Bitmap? = null; var outAt = 0L; var period = 100f
        var ring = 0; var nF = 0; var fAt = 0L; var busy = false; var lastR = -99
        val bufs = arrayOfNulls<Bitmap>(3)   // 3 bitmaps in turn: never write one the screen may still be showing
        val shaders = HashMap<Bitmap, android.graphics.BitmapShader>(); val sm = Matrix()
        val px = IntArray(bw * bh); val tmp = IntArray(bw * bh)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
            val m = ColorMatrix(); m.setSaturation(1.8f); val dim = ColorMatrix(); dim.setScale(0.5f, 0.5f, 0.5f, 1f); m.postConcat(dim)   // option B "Apple Thick"
            colorFilter = ColorMatrixColorFilter(m)
        }
        val h = Handler(Looper.getMainLooper())
        val loop = Runnable { grab() }
        fun active() = a.blurOn && a.seeThru && (visibility == View.VISIBLE || a.banner.visibility == View.VISIBLE || a.catchupBar.visibility == View.VISIBLE || a.panelBox?.isAttachedToWindow == true)
        fun reset() { out = null; prev = null; h.removeCallbacks(loop); if (a.blurOn) start() }
        fun start() { if (a.blurOn && !busy) { h.removeCallbacks(loop); h.post(loop) } }
        fun shader(b: Bitmap, w: Float, ht: Float) = shaders.getOrPut(b) { android.graphics.BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
            .also { sm.setScale(w / bw, ht / bh); it.setLocalMatrix(sm) }
        // paints the blur into the given shapes (screen coordinates), fading from the previous frame to the newest
        fun paintShapes(c: Canvas, w: Float, ht: Float, list: Collection<BlurShapes>, v: View) {
            val o = out ?: return; val pv = prev
            val t = ((SystemClock.uptimeMillis() - outAt) / period).coerceIn(0f, 1f)
            val fading = pv != null && t < 1f
            for (sh in list) for (i in 0 until sh.n) {
                if (fading) { paint.shader = shader(pv!!, w, ht); paint.alpha = 255; c.drawPath(sh.ps[i], paint) }
                paint.shader = shader(o, w, ht); paint.alpha = if (fading) (t * 255).toInt() else 255; c.drawPath(sh.ps[i], paint)
            }
            paint.alpha = 255; paint.shader = null
            if (fading) v.postInvalidateOnAnimation()
        }
        // still used by the popups (MatBg), which live in their own window
        fun draw(c: Canvas, dst: RectF, v: View) {
            val o = out ?: return
            c.drawBitmap(o, null, dst, paint)
        }
        fun grab() {
            if (!active() || android.os.Build.VERSION.SDK_INT < 24) return
            val sv = a.pv.videoSurfaceView as? SurfaceView ?: return
            if (!sv.holder.surface.isValid) { h.postDelayed(loop, 500); return }
            val s = src ?: Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888).also { src = it }
            busy = true
            bgH.post {
                try {
                    android.view.PixelCopy.request(sv, s, { r ->   // runs on the blur thread
                        var o: Bitmap? = null
                        if (r == android.view.PixelCopy.SUCCESS) {
                            s.getPixels(px, 0, bw, 0, 0, bw, bh)
                            for (k in 0..2) boxBlur(px, tmp, bw, bh, 6)   // 3 passes ~ Gaussian, sigma ~6 px of 192 = ~60 px at 1080p (option B)
                            ring = (ring + 1) % 3; o = bufs[ring] ?: Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888).also { bufs[ring] = it }
                            o.setPixels(px, 0, bw, 0, 0, bw, bh)
                        }
                        h.post { done(r, o) }
                    }, bgH)
                } catch (e: Exception) { h.post { done(-1, null) } }
            }
        }
        fun done(r: Int, o: Bitmap?) {   // main thread
            busy = false
            if (r != lastR) { lastR = r; android.util.Log.i("LiteTVBlur", "PixelCopy result $r (0 = ok)") }
            if (o != null) {
                val now = SystemClock.uptimeMillis()
                if (outAt > 0) period = period * 0.8f + (now - outAt).coerceIn(30L, 400L) * 0.2f
                val first = out == null; prev = out; out = o; outAt = now
                if (first) { invalidate(); a.banner.invalidate(); a.catchupBar.invalidate() }   // switch from the plain tint to the see-through one
                nF++; if (now - fAt > 3000L) { if (now - fAt < 4000L) android.util.Log.i("LiteTVBlur", "${nF * 1000 / (now - fAt)} blur frames/s"); nF = 0; fAt = now }
                a.blurLayer.invalidate(); a.panelBox?.let { if (it.isAttachedToWindow) it.invalidate() }
            }
            // at most ~5 copies a second (the crossfade hides the gaps; 20/s maxed out a Chromecast HD); a failed copy waits half a second
            if (active()) h.postDelayed(loop, if (o != null) 200L else 500L)
        }
    }
}

// one horizontal + one vertical box blur pass of radius r over an ARGB pixel array (alpha is kept opaque)
fun boxBlur(px: IntArray, tmp: IntArray, w: Int, h: Int, r: Int) {
    val div = 2 * r + 1
    for (y in 0 until h) {
        var rs = 0; var gs = 0; var bs = 0; val row = y * w
        for (i in -r..r) { val c = px[row + i.coerceIn(0, w - 1)]; rs += (c shr 16) and 255; gs += (c shr 8) and 255; bs += c and 255 }
        for (x in 0 until w) {
            tmp[row + x] = (0xFF shl 24) or ((rs / div) shl 16) or ((gs / div) shl 8) or (bs / div)
            val a0 = px[row + (x - r).coerceIn(0, w - 1)]; val a1 = px[row + (x + r + 1).coerceIn(0, w - 1)]
            rs += ((a1 shr 16) and 255) - ((a0 shr 16) and 255); gs += ((a1 shr 8) and 255) - ((a0 shr 8) and 255); bs += (a1 and 255) - (a0 and 255)
        }
    }
    for (x in 0 until w) {
        var rs = 0; var gs = 0; var bs = 0
        for (i in -r..r) { val c = tmp[i.coerceIn(0, h - 1) * w + x]; rs += (c shr 16) and 255; gs += (c shr 8) and 255; bs += c and 255 }
        for (y in 0 until h) {
            px[y * w + x] = (0xFF shl 24) or ((rs / div) shl 16) or ((gs / div) shl 8) or (bs / div)
            val a0 = tmp[(y - r).coerceIn(0, h - 1) * w + x]; val a1 = tmp[(y + r + 1).coerceIn(0, h - 1) * w + x]
            rs += ((a1 shr 16) and 255) - ((a0 shr 16) and 255); gs += ((a1 shr 8) and 255) - ((a0 shr 8) and 255); bs += (a1 and 255) - (a0 and 255)
        }
    }
}

// the same material as the menus, for popups (search, playlist setup...): the blurred video under the panel + the theme tint,
// or the tint almost solid when blur is off; thin accent outline like the selected card
class MatBg(val a: MainActivity, val host: View, val r: Float) : android.graphics.drawable.Drawable() {
    val p = Paint(Paint.ANTI_ALIAS_FLAG); val path = Path(); val loc = IntArray(2)
    override fun draw(c: Canvas) {
        val k = a.px(1f); val rr = r * k; val bd = RectF(bounds)
        path.reset(); path.addRoundRect(bd, rr, rr, Path.Direction.CW)
        val b = a.ui.blur.out; p.style = Paint.Style.FILL
        if (!a.seeThru) p.color = withAlpha(T.panel, 0xFF)
        else if (b != null && a.blurOn) {
            host.getLocationOnScreen(loc); val sw = a.resources.displayMetrics.widthPixels.toFloat(); val sh = a.resources.displayMetrics.heightPixels.toFloat()
            c.save(); c.clipPath(path); a.ui.blur.draw(c, RectF(-loc[0].toFloat(), -loc[1].toFloat(), sw - loc[0], sh - loc[1]), host); c.restore()
            p.color = withAlpha(T.panel, (T.alpha * 255).toInt())
        } else p.color = withAlpha(T.panel, 0xED)
        c.drawPath(path, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * k; p.color = 0x1AFFFFFF   // the same faint edge as every card (the theme colour is not for borders)
        bd.inset(0.75f * k, 0.75f * k); c.drawRoundRect(bd, rr, rr, p)
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
    @Deprecated("") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

// the live blur under the menus, banner and catch-up bar, on its own layer: only this repaints while the blur moves;
// the screens on top redraw only when something on them changes (a key press)
class BlurLayer(ctx: Context, val a: MainActivity) : View(ctx) {
    val vis = ArrayList<BlurShapes>()
    override fun onDraw(c: Canvas) {
        val b = a.ui.blur; if (!a.blurOn || !a.seeThru || b.out == null) return
        vis.clear(); for ((v, sh) in b.shapes) if (v.visibility == View.VISIBLE) vis.add(sh)
        if (vis.isNotEmpty()) b.paintShapes(c, width.toFloat(), height.toFloat(), vis, this)
    }
}

// the blurred shapes one view drew last time (paths reused between draws, so redraws allocate nothing)
class BlurShapes { val ps = ArrayList<Path>(); var n = 0
    fun add(src: Path, dx: Float) { if (n == ps.size) ps.add(Path()); ps[n].set(src); ps[n].offset(dx, 0f); n++ } }
