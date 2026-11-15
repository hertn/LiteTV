@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.iptv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
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
    val ONF = 0xFF1C1C1E.toInt(); val ONF2 = 0x9E1C1C1E.toInt()   // text on the white focus highlight
    val SELBG = 0x24FFFFFF; val FILL = 0x14FFFFFF; val HAIR = 0x14FFFFFF; val TRACK = 0x38FFFFFF; val BARC = 0xEBFFFFFF.toInt()
    val GRAY: Int get() = SEC
    val OUTLINE: Int get() = withAlpha(T.accent, if (T.gold) 0xBF else 0xCC)   // selected row / card outline: the theme's accent
    val REG: Typeface = Typeface.DEFAULT
    val MED: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val BOLD: Typeface = Typeface.DEFAULT_BOLD

    val dd = java.text.SimpleDateFormat("dd.MM"); val wd = java.text.SimpleDateFormat("EEE")
    val S = 6; val dir = if (REVERSED) 1 else -1
    // mode: 0 hidden, 1 channel list, 2 date/channel/program, 3 main menu (OK), 4 channel settings, 5 program info, 6 theme list,
    // 8 settings, 9 favorite group order, 10 error log, 11 reminder card, 12 remote buttons
    var fromMode = 0; var fadeT0 = 0L
    var dimV = 0f; var dimAt = 0L   // guide logo dimming, animated
    var mode = 0; var sideLvl = 0; var sideFocus = 1; var catSel = 1; var plSel = 0   // sideLvl: 0 none, 1 categories, 2 playlists + categories
    var side: Boolean
        get() = sideLvl > 0
        set(v) { if (!v) sideLvl = 0 }
    var sel = 0; var oi = 0; var col = 1; var dayI = 0; var progI = 0; var menuSel = 0
    var subI = 0; var subOpen = false; var oj = 0
    var themeSel = 0; var setSel = 0; var grpSel = 0; var grpGrab = false; var logSel = 0
    var infoCh: Ch? = null; var infoProg: Prog? = null; var backMode = 1; var infoBtn = 0
    var rbSel = 0; var rbSub = 0; var rbOpt = 0   // remote buttons: row, 0 list / 1 row menu / 2 waiting for a button, row menu choice
    var remCh: Ch? = null; var remProg: Prog? = null; var remBtn = 0; var remAt = 0L
    var selSince = SystemClock.uptimeMillis()
    val blur = Blur()
    init { isFocusable = true; isFocusableInTouchMode = true; alpha = UI_ALPHA }

    // every key press (and every screen change) restarts the idle timer; the screens close themselves after 30 s of nothing
    val idle = Runnable { if (mode != 0) close() }
    var quickHide = false   // channel settings shown by a channel change: hides after 5 s unless a tile is used
    fun touch() { selSince = SystemClock.uptimeMillis(); removeCallbacks(idle); if (mode != 0 && mode != 11) postDelayed(idle, if (quickHide && mode == 4) 5000L else 30000L) }
    fun rowH() = height * ROW_SCALE / 7.5f
    fun wrap(i: Int): Int { val n = a.shown.size; return if (n == 0) 0 else ((i % n) + n) % n }
    fun chAt(i: Int): Ch? = a.shown.getOrNull(i)

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
        if (off < over) postInvalidateOnAnimation() else if (el <= 0) postInvalidateDelayed(-el + 16)
    }
    fun fill(c: Canvas, x: Float, y: Float, w: Float, h: Float, color: Int) { p.shader = null; p.color = color; c.drawRect(x, y, x + w, y + h, p) }
    fun rr(c: Canvas, x: Float, y: Float, w: Float, h: Float, r: Float, color: Int) { p.shader = null; p.style = Paint.Style.FILL; p.color = color; c.drawRoundRect(RectF(x, y, x + w, y + h), r, r, p) }
    fun outline(c: Canvas, x: Float, y: Float, w: Float, h: Float, r: Float) {
        p.shader = null; p.style = Paint.Style.STROKE; p.strokeWidth = 2 * U; p.color = OUTLINE
        c.drawRoundRect(RectF(x + U, y + U, x + w - U, y + h - U), r, r, p); p.style = Paint.Style.FILL
    }
    fun icon(c: Canvas, k: String, cx: Float, cy: Float, sz: Float, color: Int, accent: Boolean = false) {
        if (accent) accentPaint(p) else { p.shader = null; p.color = color }
        drawIcon(c, k, cx, cy, sz, p); p.shader = null
    }

    // accent colour; New Year = polished gold whose highlight sweeps across the screen every 4 s (still when animations are off)
    val goldM = Matrix(); var goldSh: LinearGradient? = null; var goldW = 0
    fun animOn() = try { android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f } catch (e: Exception) { true }
    fun accentPaint(pt: Paint) {
        if (!T.gold) { pt.shader = null; pt.color = T.accent; return }
        if (goldSh == null || goldW != width) {
            goldW = width
            goldSh = LinearGradient(0f, 0f, 600 * U, 210 * U,
                intArrayOf(0xFF9A7425.toInt(), 0xFFD4A940.toInt(), 0xFFFFF3C4.toInt(), 0xFFE8C66A.toInt(), 0xFFB8862B.toInt(), 0xFF9A7425.toInt()),
                floatArrayOf(0f, 0.22f, 0.36f, 0.5f, 0.75f, 1f), Shader.TileMode.MIRROR)
        }
        val ph = if (animOn()) (SystemClock.uptimeMillis() % 4000L) / 4000f else 0.3f
        goldM.setTranslate(ph * 1200 * U, 0f); goldSh!!.setLocalMatrix(goldM); pt.color = -1; pt.shader = goldSh
    }

    // Apple material: the blurred, dimmed video under the shape, then the theme's tint. Without blur: the tint nearly solid
    // the view whose onDraw is running (Ui, Banner or CatchupBar): mat() records its shapes for BlurLayer under them
    fun beginShapes(v: View) { blur.cur = blur.shapes.getOrPut(v) { BlurShapes() }.also { it.n = 0 }; a.blurLayer.invalidate() }
    val mPath = Path(); var matOx = 0f   // how far the canvas is shifted right while drawing (lists move right when side panels open)
    fun mat(c: Canvas, path: Path, tint: Int = T.panel) {
        val b = blur.out
        if (!a.seeThru) p.color = withAlpha(tint, 0xFF)
        else if (b != null && a.blurOn) {
            blur.cur?.add(path, matOx)   // the blur itself is painted by BlurLayer underneath
            p.color = withAlpha(tint, (T.alpha * 255).toInt())
        } else p.color = withAlpha(tint, 0xED)
        p.shader = null; p.style = Paint.Style.FILL; c.drawPath(path, p)
    }
    fun matRect(c: Canvas, x: Float, y: Float, w: Float, h: Float, r: Float) {
        mPath.reset(); mPath.addRoundRect(RectF(x, y, x + w, y + h), r, r, Path.Direction.CW); mat(c, mPath)
    }
    // floating glass card (main menu, channel list, side panels, guide): 36 px from the screen edges, 40 px corners,
    // a faint light edge so it reads as a separate layer over the video
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
        mPath.reset(); mPath.addRoundRect(RectF(x, y, x + w, y + h), 26 * U, 26 * U, Path.Direction.CW)   // 26 = button radius 14 + padding 12
        val tri = Path(); tri.moveTo(x + 2 * U, py - 36 * U); tri.lineTo(x - 27 * U, py); tri.lineTo(x + 2 * U, py + 36 * U); tri.close()
        mPath.op(tri, Path.Op.UNION); mat(c, mPath)   // no outline: the selected row's outline alone shows where focus is
    }
    // the same card with the pointer underneath, aimed at the tile it belongs to (centre x = px)
    fun matCardDown(c: Canvas, x: Float, y: Float, w: Float, h: Float, px: Float) {
        mPath.reset(); mPath.addRoundRect(RectF(x, y, x + w, y + h), 26 * U, 26 * U, Path.Direction.CW)
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
    // the focused item grows a little (tvOS focus); draw it inside this
    inline fun lift(c: Canvas, on: Boolean, x: Float, y: Float, w: Float, h: Float, sc: Float, body: () -> Unit) {
        if (on) { c.save(); c.scale(sc, sc, x + w / 2, y + h / 2); rr(c, x + 2 * U, y + 10 * U, w - 4 * U, h, 16 * U, 0x40000000) }
        body(); if (on) c.restore()
    }
    // white focus pill (or a faint one) with an icon and a label, used for every button row
    fun button(c: Canvas, x: Float, y: Float, w: Float, h: Float, on: Boolean, ic: String?, label: String, sz: Float = 29f, sc: Float = 1.06f) {
        lift(c, on, x, y, w, h, sc) {
            rr(c, x, y, w, h, 14 * U, if (on) WHITE else 0x24FFFFFF)
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
            lift(c, on, bx, by, bw, bh, 1.08f) {
                rr(c, bx, by, bw, bh, 14 * U, if (on) WHITE else FILL)
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
        if (moreR || moreL) { val cp = Path(); cp.addRoundRect(RectF(x, fy0, x + maxW, fy1), 26 * U, 26 * U, Path.Direction.CW); c.clipPath(cp) }
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
    fun defProg(): Int {
        val ch = a.shown.getOrNull(sel) ?: return 0; val now = System.currentTimeMillis()
        val k = a.dayProgs(ch, days()[dayI.coerceIn(0, days().lastIndex)]).indexOfFirst { now >= it.start && now < it.stop }
        return if (k >= 0) k else 0
    }
    fun reset(keep: Boolean) {
        val k = a.shown.indexOf(a.cur); val mx = maxOf(0, a.shown.size - 1)
        sel = if (keep) sel.coerceIn(0, mx) else if (k >= 0) k else 0
        dayI = a.todayIdx(); progI = defProg(); touch(); invalidate()
    }
    fun show(m: Int) {
        if (visibility != View.VISIBLE) blur.shapes[this]?.n = 0   // forget the last menu's blur shapes, or BlurLayer flashes them for one frame
        // channel list <-> guide crossfade: remember the screen we leave so drawScreens can fade it out
        fromMode = if (visibility == View.VISIBLE && mode != m && mode in 1..2 && m in 1..2 && animOn()) mode else 0; fadeT0 = 0L   // the clock starts on the first drawn frame (the guide's first draw is slow)
        mode = m; side = false; visibility = View.VISIBLE; a.banner.visibility = View.GONE; touch(); requestFocus(); blur.start() }
    fun open(m: Int) { dimAt = 0L; oi = 0; col = 1; cardY = -1f; show(m); reset(false) }
    fun openGuide(ch: Ch) { open(2); val k = a.shown.indexOf(ch); if (k >= 0) { sel = k; progI = defProg(); invalidate() } }
    fun openMenu() { menuSel = 0; show(3); invalidate() }
    fun openThemes() { themeSel = a.themeIdx; show(6); invalidate() }
    fun openSettings() { setSel = 0; show(8); invalidate() }
    fun openFavGroups() { grpSel = 0; grpGrab = false; show(9); invalidate() }
    fun openKeys() { rbSel = 0; rbSub = 0; show(12); invalidate() }
    fun openLog() { logSel = 0; show(10); invalidate() }
    fun openAbout() { show(13); invalidate() }
    fun openSub() { quickHide = false; backMode = if (mode == 2) 2 else 1; subI = 0; subOpen = false; show(4); invalidate() }
    // from the player (OK, or a channel change): Back closes it, and UP/DOWN keep changing channels while no list is open
    fun openSubPlayer(quick: Boolean) {
        if (mode == 4 && backMode == 0) { if (!quick) quickHide = false; touch(); invalidate(); return }   // already up: just refresh for the new channel
        quickHide = quick; backMode = 0; subI = 0; subOpen = false; show(4); invalidate()
    }
    fun openInfo(ch: Ch, pr: Prog?) { backMode = if (mode == 2) 2 else 1; infoCh = ch; infoProg = pr; infoBtn = 0; show(5); invalidate() }
    // "Starting now" card for a reminder (replaces whatever screen was open); it closes itself after 20 s
    val remHide = Runnable { if (mode == 11) close() }
    fun showReminder(ch: Ch, pr: Prog) {
        remCh = ch; remProg = pr; remBtn = 0; remAt = SystemClock.uptimeMillis(); show(11); removeCallbacks(remHide); postDelayed(remHide, 20_000L); invalidate()
    }
    fun close() {
        if (mode == 6) a.setTheme(a.themeIdx, false)   // leaving the theme list without pressing OK: drop the preview
        a.settingsDone()
        grpGrab = false; mode = 0; sideLvl = 0; removeCallbacks(idle); removeCallbacks(remHide); visibility = View.GONE; if (a.searching) a.endSearch()
    }
    fun openSide(pl: Boolean = false) {
        sideLvl = if (pl) 2 else 1; sideFocus = if (pl) 2 else 1
        catSel = a.grp.coerceIn(0, maxOf(0, a.groups.size - 1)); plSel = a.act; touch()
    }

    override fun onDraw(c: Canvas) { beginShapes(this); drawScreens(c) }
    override fun onVisibilityChanged(v: View, vis: Int) { super.onVisibilityChanged(v, vis); @Suppress("SENSELESS_COMPARISON") if (a != null) a.blurLayer.invalidate() }   // a is still null while View's constructor runs
    fun drawMode(c: Canvas, m: Int, w: Float, h: Float, panels: Boolean, shift: Float) {
        when (m) {
            1 -> { c.save(); c.translate(shift, 0f); matOx = shift; drawChannels(c, w - shift, h); matOx = 0f; c.restore() }
            2 -> { val g = if (panels) shift else 0f; c.save(); c.translate(g, 0f); matOx = g; drawGuide(c, w - g, h); matOx = 0f; c.restore() }
            3 -> drawMenu(c, w, h)
            4 -> { drawSub(c, w, h); postInvalidateDelayed(1000) }
            5 -> drawInfo(c, w, h)
            6 -> { c.save(); c.translate(M, 0f); matOx = M; drawThemes(c, w - M, h); matOx = 0f; c.restore() }
            8 -> { c.save(); c.translate(M, 0f); matOx = M; drawSettings(c, w - M, h); matOx = 0f; c.restore() }
            9 -> drawFavGroups(c, w, h)
            10 -> drawErrLog(c, w, h)
            12 -> { c.save(); c.translate(M, 0f); matOx = M; drawKeys(c, w - M, h); matOx = 0f; c.restore() }
            13 -> { c.save(); c.translate(M, 0f); matOx = M; drawAbout(c, w - M, h); matOx = 0f; c.restore() }
            11 -> { drawReminder(c, w); postInvalidateDelayed(250) }
        }
    }
    fun drawScreens(c: Canvas) {
        U = width / 1920f
        val w = width.toFloat(); val h = height.toFloat()
        val pw = w * 0.19f; val cw = 420 * U; val panels = sideLvl > 0 && (mode == 1 || mode == 2)
        val shift = if (panels) M + (if (sideLvl >= 2) pw + GAP else 0f) + cw + GAP else M   // lists move right of the floating panels, nothing overlaps
        if (fromMode != 0 && fadeT0 == 0L) fadeT0 = SystemClock.uptimeMillis()
        val ft = if (fromMode != 0) (SystemClock.uptimeMillis() - fadeT0) / 300f else 1f
        if (ft < 1f) {   // crossfade: the old screen fades out (without blur behind it), the new one fades in
            val e = ft * ft * (3 - 2 * ft); val keep = blur.cur
            blur.cur = null; c.saveLayerAlpha(0f, 0f, w, h, ((1 - e) * 255).toInt()); drawMode(c, fromMode, w, h, panels, shift); c.restore(); blur.cur = keep
            c.saveLayerAlpha(0f, 0f, w, h, (e * 255).toInt()); drawMode(c, mode, w, h, panels, shift); c.restore()
            postInvalidateOnAnimation()
        } else { fromMode = 0; drawMode(c, mode, w, h, panels, shift) }
        if (panels) {
            var x = M
            if (sideLvl >= 2) { drawPanel(c, x, pw, h, "Playlists", a.sources.map { it.name }, plSel, a.act, sideFocus == 2, null, true); x += pw + GAP }
            drawPanel(c, x, cw, h, "Categories", a.groups, catSel, a.grp, sideFocus == 1, null, true)
        }
        if (T.gold && animOn()) postInvalidateDelayed(66)   // the gold shimmer moves only while a screen is open
    }

    // ---------- channel list ----------
    fun drawChannels(c: Canvas, w: Float, h: Float) {
        val rh = rowH(); val lw = 520 * U; val logoW = 196 * U; val tx = logoW + 8 * U; val tmax = lw - logoW - 28 * U
        glass(c, 0f, M, lw, h - 2 * M)
        if (a.shown.isEmpty()) txt(c, "No channels", 30 * U, h / 2, lw - 60 * U, 29f, SEC)
        beginScroll(c, 0f, M, lw, h - 2 * M)
        // the selected row and the options stay fixed in the bottom slot; the list scrolls through it and never wraps visually
        val n = a.shown.size; val vp = if (REVERSED) n - 1 - sel else sel
        for (s in 0..7) {
            val vi = vp - S + s; if (vi < 0 || vi >= n) continue
            val ch = a.shown[if (REVERSED) n - 1 - vi else vi]; val y = s * rh; val pr = a.now(ch); val on = s == S
            if (on) { rr(c, 12 * U, y + 6 * U, lw - 24 * U, rh - 12 * U, 14 * U, SELBG); outline(c, 12 * U, y + 6 * U, lw - 24 * U, rh - 12 * U, 14 * U) }
            tile(c, ch, 22 * U, y + 16 * U, logoW - 30 * U, rh - 32 * U, if (on || ch == a.cur) FILL else 0)   // 10 px inside the row highlight; only the selected and playing logos keep the grey box
            val tag = if (a.multi()) (a.sources.find { it.id == ch.src }?.let { "  ·  ${it.name}" } ?: "") else ""
            val nm = "${ch.num}: ${ch.name}$tag"
            stxt(c, nm, tx, y + rh * 0.24f - 9 * U, tmax, 23f, SEC, 400, on)
            stxt(c, pr?.title ?: "", tx, y + rh * 0.50f - 11 * U, tmax, 31f, WHITE, 600, on)
            if (pr != null) {
                txt(c, "${a.hm.format(pr.start)} – ${a.hm.format(pr.stop)}" + (if (on) "  ·  ${minLeft(pr)} min left" else ""), tx, y + rh * 0.74f - 8 * U, tmax, 22f, SEC)
                progress(c, tx, y + rh * 0.84f, lw - logoW - 36 * U, pr)
            }
        }
        endScroll(c)
        a.shown.getOrNull(sel)?.let { ch ->
            val cx = lw + 34 * U; val opts = a.chOptions(ch, false); val full = opts.size * 176 * U + 14 * U
            val cwid = minOf(w - cx - 36 * U, full)
            matCard(c, cx, S * rh + 2 * U, cwid, rh - 6 * U, S * rh + rh / 2)
            optButtons(c, cx, S * rh, cwid, opts, oi)
        }
        val now = System.currentTimeMillis()
        if (sideLvl == 0) status(c, lw, listOf(a.hm.format(now), a.dm.format(now), srcName(a.shown.getOrNull(sel))), listOf(a.groups.getOrElse(a.grp) { "" }, "${a.shown.size} channels"))
        a.season().let { if (it != 0 && a.shown.isNotEmpty()) deco(c, it, -rh * 0.14f, S * rh + 6 * U - rh * 0.26f, rh * 0.52f) }
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
            if (on) { rr(c, px0, cyp - ph0 / 2, pw0, ph0, ph0 / 2, SELBG); if (col == 0) outline(c, px0, cyp - ph0 / 2, pw0, ph0, ph0 / 2) }
            val lb = if (i == ti) "Today" else if (i == ti + 1) "Tomorrow" else if (i == ti - 1) "Yesterday" else wd.format(day)
            txt(c, lb, px0 + pw0 / 2, cyp - 14 * U, pw0 - 16 * U, 22f, if (on) WHITE else SEC, 600, Paint.Align.CENTER)
            txt(c, dd.format(day), px0 + pw0 / 2, cyp + 15 * U, pw0 - 16 * U, 28f, WHITE, 700, Paint.Align.CENTER)
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
        rr(c, dw + 12 * U, S * rh + 6 * U, cw - 24 * U, rh - 12 * U, 14 * U, SELBG)
        if (col == 1) outline(c, dw + 12 * U, S * rh + 6 * U, cw - 24 * U, rh - 12 * U, 14 * U)
        for (s in 0..7) {
            val y = s * rh; val on = s == S
            chAt(sel + dir * (S - s))?.let { cc ->
                val playing = cc == a.cur; val dim = dimA < 255 && !on && !playing   // the playing channel never fades
                if (dim) c.saveLayerAlpha(dw, y, dw + cw, y + rh, dimA)
                tile(c, cc, dw + 22 * U, y + 16 * U, cw - 44 * U, rh - 32 * U, if (on || playing) FILL else 0)   // 10 px inside the outline; only the selected and playing logos keep the grey box
                if (dim) c.restore()
            }
        }
        endScroll(c)
        glass(c, px, M, pw, gh)
        beginScroll(c, px, M + hd, pw, gh - hd)
        rr(c, px + 12 * U, S * rh + 6 * U, pw - 24 * U, rh - 12 * U, 14 * U, SELBG)
        if (col == 2) outline(c, px + 12 * U, S * rh + 6 * U, pw - 24 * U, rh - 12 * U, 14 * U)
        for (s in 0..7) {
            val y = s * rh; val on = s == S
            (if (at >= 0) all.getOrNull(at - (s - S)) else dp.getOrNull(progI - (s - S)))?.let { pr ->   // later shows above the selected one, earlier below (across midnight too)
                if (!on && pr.start > now) rr(c, px + 12 * U, y + 6 * U, pw - 24 * U, rh - 12 * U, 14 * U, FILL)   // upcoming shows are a little lighter, as in the stable build
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

    // ---------- channel settings: Apple TV player style; LEFT / RIGHT between the 4 settings, OK opens the list above ----------
    val subScrim = Paint()
    fun drawSub(c: Canvas, w: Float, h: Float) {
        val ch = a.cur ?: a.shown.getOrNull(sel) ?: return
        subScrim.shader = LinearGradient(0f, h, 0f, h * 0.5f, intArrayOf(0xEB000000.toInt(), 0xB3000000.toInt(), 0), floatArrayOf(0f, 0.48f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, h * 0.5f, w, h, subScrim)
        // after a channel change (quickHide) only the info shows; after OK the four compact setting tiles sit on the right
        val y = h - 280 * U; val pr = a.now(ch); val tiles = !quickHide
        tile(c, ch, 90 * U, y, 170 * U, 96 * U, 0x1AFFFFFF)
        val labels = listOf("Audio", "Subtitles", "Aspect Ratio", "Audio Delay"); val short = listOf("Audio", "Subtitles", "Aspect", "Delay")
        val ics = listOf("audio", "cc", "aspect", "delay")
        val tw0 = 150 * U; val th0 = 118 * U; val pitch = tw0 + 12 * U; val px0 = w - 90 * U - 4 * pitch + 12 * U; val ty0 = y - 6 * U
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
        if (tiles) for (i in 0..3) {
            val (opts, cs) = a.subOpts(i); val x = px0 + i * pitch; val sel = i == subI && !subOpen
            val one = opts.size <= 1; val on = sel && !one   // nothing to choose: dimmed like a greyed-out Settings row, and no white focus
            lift(c, sel, x, ty0, tw0, th0, 1.06f) {
                rr(c, x, ty0, tw0, th0, 14 * U, if (on) WHITE else if (sel) 0x3DFFFFFF else if (i == subI) SELBG else 0x1FFFFFFF)
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
                    if (f) rr(c, qx + 16 * U, ry, qw - 32 * U, 60 * U, 14 * U, WHITE)
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
    }

    // seasonal icons are painted PNGs (res/drawable-nodpi/se_<id>.png, made from LiteTV assets/Seasonal deco); each image has
    // 14% padding around the icon box so shadows and sparkles fit, and the slant is already in the art
    private val DECO_RES = mapOf(1 to R.drawable.se_1, 2 to R.drawable.se_2, 3 to R.drawable.se_3, 4 to R.drawable.se_4, 5 to R.drawable.se_5, 6 to R.drawable.se_6, 7 to R.drawable.se_7, 8 to R.drawable.se_8, 9 to R.drawable.se_9, 10 to R.drawable.se_10, 11 to R.drawable.se_11, 12 to R.drawable.se_12, 13 to R.drawable.se_13, 14 to R.drawable.se_14, 15 to R.drawable.se_15, 16 to R.drawable.se_16, 17 to R.drawable.se_17, 18 to R.drawable.se_18, 19 to R.drawable.se_19, 21 to R.drawable.se_21, 22 to R.drawable.se_22, 23 to R.drawable.se_23, 24 to R.drawable.se_24)
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
        val cw = 520 * U; val pitch = 90 * U; val ih = 80 * U; val npH = 140 * U
        val listTop = 34 * U + 76 * U + 30 * U
        val ch0 = listTop + (items.size - 1) * pitch + 14 * U + U + 14 * U + ih + (if (ch != null) 22 * U + npH else 0f) + 20 * U
        val x = M; val y = ((h - ch0) / 2).coerceAtLeast(M)
        glass(c, x, y, cw, ch0)
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
                if (on) rr(c, bx, top, bw, ih, 14 * U, WHITE)
                val col = if (on) ONF else WHITE
                icon(c, it.first, bx + 43 * U, top + ih / 2, 34 * U, col)
                stxt(c, it.second, bx + 86 * U, top + ih / 2, bw - 110 * U, 34f, col, if (on) 600 else 500, on)
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

    // ---------- settings: one line per setting; the selected row's explanation sits in the footer ----------
    fun drawSettings(c: Canvas, w: Float, h: Float) {
        val pw = 680 * U; val t0 = 128 * U; val foot = h - M - 20 * U; val ih = 80 * U; val sh = 60 * U
        glass(c, 0f, M, pw, h - 2 * M)
        txt(c, "Settings", 40 * U, 72 * U, pw - 80 * U, 44f, WHITE, 700)
        val items = a.settingsItems()
        // the selected row grows to show what it does under its name (no footer); the text shares the row with the value on the right
        val sv = items.getOrNull(setSel)?.second ?: ""; val dsz = 24f
        tp.textSize = tsz(dsz); tp.typeface = REG
        val dw = (pw - 150 * U - (if (sv.isNotEmpty()) tw(sv + "  ›", 29f, 600) + 30 * U else 0f)).toInt()
        val dl = fitText(a.settingDesc(setSel), maxOf(1, dw), tsz(dsz) * 1.15f * 3.2f, 1.15f)
        val dh = dl.height + 16 * U
        val ys = FloatArray(items.size); var yy = 0f   // top of every row inside the list (a section heading sits just above its first row)
        for (i in items.indices) { if (a.settingSection(i) != null) yy += sh; ys[i] = yy; yy += ih + (if (i == setSel) dh else 0f) }
        val scroll = maxOf(0f, ys[setSel.coerceIn(0, items.lastIndex)] + ih + dh + 20 * U - (foot - t0))
        c.save(); c.clipRect(0f, t0, pw - U, foot); c.translate(0f, t0 - scroll)
        items.forEachIndexed { i, row ->
            val y = ys[i]
            a.settingSection(i)?.let { sec -> hdr(c, sec, 40 * U, y - 22 * U, pw - 80 * U) }
            val on = i == setSel; val en = a.settingEnabled(i)   // rows that do not apply right now are dimmed
            val rh = ih + (if (on) dh else 0f)
            lift(c, on, 12 * U, y + 3 * U, pw - 24 * U, rh - 6 * U, 1.025f) {
                if (on) {
                    rr(c, 12 * U, y + 3 * U, pw - 24 * U, rh - 6 * U, 14 * U, WHITE)
                    tp.textSize = tsz(dsz); tp.typeface = REG; tp.color = withAlpha(ONF, 0xA8); tp.shader = null; tp.textAlign = Paint.Align.LEFT
                    c.save(); c.translate(110 * U, y + ih - 14 * U); dl.draw(c); c.restore()
                }
                val col = if (on) ONF else if (en) WHITE else 0x66FFFFFF
                rr(c, 40 * U, y + ih / 2 - 24 * U, 48 * U, 48 * U, 12 * U, if (on) 0x14000000 else 0x1AFFFFFF)
                icon(c, SETTING_ICONS.getOrElse(i) { "info" }, 64 * U, y + ih / 2, 28 * U, col)
                txt(c, row.first, 110 * U, y + ih / 2, pw * 0.5f, 31f, col, if (on) 600 else 500)
                val open = i == 0 || i == 13 || i == 15 || i == 16
                if (row.second.isNotEmpty() || open) txt(c, row.second + (if (open) "  ›" else ""), pw - 40 * U, y + ih / 2, pw * 0.42f, 29f,
                    if (on) ONF else if (en) SEC else 0x55FFFFFF, if (on) 600 else 400, Paint.Align.RIGHT)
            }
        }
        c.restore()
    }
    // ---------- about (mode 13): in the settings panel; QR codes open the GitHub page and Ko-fi on a phone ----------
    private val aboutQr by lazy { listOf(a.qr("https://github.com/hertn/LiteTV", 300), a.qr("https://ko-fi.com/hertn", 300)) }
    fun drawAbout(c: Canvas, w: Float, h: Float) {
        val pw = 680 * U; val x = 40 * U; val iw = pw - 80 * U
        glass(c, 0f, M, pw, h - 2 * M)
        val ms = 120 * U; drawMark(c, x, M + 36 * U, ms)
        val lx = x + ms + 26 * U; val ly = M + 36 * U + ms / 2
        txt(c, "Lite", lx, ly - 18 * U, 300 * U, 52f, WHITE, 700); txt(c, "TV", lx + tw("Lite", 52f, 700), ly - 18 * U, 200 * U, 52f, 0, 700, accent = true)
        txt(c, "Version " + a.verLabel.removePrefix("v"), lx, ly + 28 * U, iw - ms, 26f, SEC)
        var y = M + 36 * U + ms + 40 * U
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
        r.add(KRow("Exit App", listOf("Hold Back"), null, "Hold Back for a second to close LiteTV completely."))
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
        val pw = w * 0.46f; val t0 = 128 * U; val bot = h - M - 20 * U; val ih = 76 * U; val sh = 58 * U
        val rows = keyRows(); rbSel = rbSel.coerceIn(0, rows.lastIndex); val cur = rows[rbSel]
        glass(c, 0f, M, pw, h - 2 * M)
        txt(c, "Remote Buttons", 40 * U, 72 * U, pw - 80 * U, 44f, WHITE, 700)
        val ys = FloatArray(rows.size); var yy = 0f
        rows.forEachIndexed { i, r -> if (r.sec != null) yy += if (r.sec.isEmpty()) 20 * U else sh; ys[i] = yy; yy += ih }
        val scroll = maxOf(0f, ys[rbSel] + ih + 20 * U - (bot - t0))
        c.save(); c.clipRect(0f, t0, pw - U, bot); c.translate(0f, t0 - scroll)
        rows.forEachIndexed { i, r ->
            val y = ys[i]; val on = i == rbSel && rbSub == 0; val mark = i == rbSel
            if (!r.sec.isNullOrEmpty()) hdr(c, r.sec.uppercase(), 40 * U, y - 22 * U, pw - 80 * U)
            lift(c, on, 12 * U, y + 3 * U, pw - 24 * U, ih - 6 * U, 1.025f) {
                if (mark) rr(c, 12 * U, y + 3 * U, pw - 24 * U, ih - 6 * U, 14 * U, if (on) WHITE else SELBG)
                val col = if (on) ONF else WHITE
                var xr = pw - 36 * U
                for (ch in r.chips.reversed()) xr = chip(c, ch, xr, y + ih / 2, on) - 10 * U
                txt(c, r.label, 40 * U, y + ih / 2, xr - 60 * U, 30f, if (r.act == null && !r.reset && !on) SEC else col, if (on) 600 else 500)
            }
        }
        c.restore()
        // right: what the selected row does; after OK a small menu, then the wait for a button
        val cx = pw + GAP; val cwid = minOf(680 * U, w - cx - M); val cy = M
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
                        if (on) rr(c, cx + 14 * U, oy, cwid - 28 * U, 74 * U, 14 * U, WHITE)
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
    fun drawPanel(c: Canvas, x: Float, pw: Float, h: Float, title: String, items: List<String>, selI: Int, activeI: Int, focused: Boolean, grab: Boolean?, floating: Boolean = false) {
        val rh = h / 11f; val rows = 10; val ins = if (floating) 12 * U else 14 * U
        if (floating) glass(c, x, M, pw, h - 2 * M) else { matRect(c, x, 0f, pw, h, 0f); fill(c, x + pw - U, 0f, U, h, HAIR) }
        hdr(c, title, x + ins + 26 * U, if (floating) M + 52 * U else rh * 0.62f - 9 * U, pw - 60 * U)
        if (floating) beginScroll(c, x, M, pw, h - 2 * M)
        val top = maxOf(0, minOf(selI - 3, items.size - rows))
        for (k in 0 until rows) {
            val i = top + k; val lab = items.getOrNull(i) ?: break; val y = rh * (k + 1); val on = i == selI
            val bx = x + ins; val bw = pw - 2 * ins; val bh = rh - 12 * U
            lift(c, on && focused, bx, y + 6 * U, bw, bh, 1.03f) {
                if (on) rr(c, bx, y + 6 * U, bw, bh, 14 * U, if (focused) WHITE else SELBG)
                val col = if (on && focused) ONF else WHITE
                val pre = if (grab == true && on) "●  " else ""
                stxt(c, pre + lab, bx + 26 * U, y + rh / 2, bw - 90 * U, 31f, col, if (on) 600 else 500, on && focused)
                if (i == activeI) icon(c, "check", bx + bw - 40 * U, y + rh / 2, 28 * U, col)
            }
        }
        if (floating) endScroll(c)
    }

    // error log (mode 10): the last problems since the app started, newest first; UP / DOWN scroll, OK clears the log
    fun drawErrLog(c: Canvas, w: Float, h: Float) {
        val pw = w * 0.62f; val items = a.errLog.toList()
        drawPanel(c, M, pw, h, "Error Log", items, logSel.coerceIn(0, maxOf(0, items.lastIndex)), -1, true, null, true)
        if (items.isEmpty()) txt(c, "No problems since the app started", M + 40 * U, h / 2, pw - 80 * U, 29f, SEC)
        if (items.isNotEmpty()) keyHint(c, listOf("UP / DOWN" to "scroll", "OK" to "clear the log"), M + 40 * U, h - M - 40 * U, pw - 80 * U)   // Back needs no hint: it goes back everywhere
    }

    // favorite group order (mode 9): UP / DOWN choose a group, OK grabs it, UP / DOWN then moves it, OK drops it
    fun drawFavGroups(c: Canvas, w: Float, h: Float) {
        val names = a.favGroupNames().map { it.second }; val pw = w * 0.34f
        drawPanel(c, M, pw, h, "Favorite Groups", names, grpSel.coerceIn(0, maxOf(0, names.lastIndex)), -1, true, grpGrab, true)
        if (names.isEmpty()) { txt(c, "No favorites yet", M + 40 * U, h / 2, pw - 80 * U, 29f, SEC); return }
        val x = M + pw + 40 * U; val tw0 = w - x - 80 * U; var y = h * 0.3f
        fun line(k: String, v: String) { keyHint(c, listOf(k to v), x, y, tw0); y += 50 * U }
        if (grpGrab) { line("UP / DOWN", "move the group"); line("OK or BACK", "drop it here") }
        else { line("UP / DOWN", "choose a group"); line("OK", "grab it to move") }
        y += 20 * U
        txt(c, if (REVERSED) "Top of this list = bottom of the channel list" else "Top of this list = top of the channel list", x, y, tw0, 24f, SEC); y += 40 * U
        txt(c, "\"Other\" always stays last", x, y, tw0, 24f, SEC)
    }

    // ---------- themes: a colour list (and the seasonal themes) with a preview of where the colour shows ----------
    fun drawThemes(c: Canvas, w: Float, h: Float) {
        val pw = w * 0.24f
        // rows shrink a little when the list would run into the key hints (11 themes did: New Year's highlight covered them)
        val rh = minOf(78 * U, (h - M - 36 * U - 30 * U - (M - 4 * U) - 2 * 58 * U) / THEMES.size)
        glass(c, 0f, M, pw, h - 2 * M)
        var y = M - 4 * U
        THEMES.forEachIndexed { i, t ->
            if (i == 0 || i == FIRST_SEASONAL) { hdr(c, if (i == 0) "Themes" else "Seasonal", 40 * U, y + 33 * U, pw - 60 * U); y += 58 * U }
            val on = i == themeSel; val bx = 12 * U; val bw = pw - 24 * U
            lift(c, on, bx, y + 4 * U, bw, rh - 8 * U, 1.03f) {
                if (on) rr(c, bx, y + 4 * U, bw, rh - 8 * U, 14 * U, WHITE)
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
        keyHint(c, listOf("OK" to "use"), 40 * U, h - M - 36 * U, pw - 60 * U)
        // preview: the only places the colour appears
        val x = pw + w * 0.04f; val pW = 760 * U; val pH = 520 * U; val py = (h - pH) / 2   // only as big as its contents, centred beside the list
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
        para(c, (if (t.seasonal) "Seasonal themes also colour the menu background. " else "") +
            "The selected row is outlined in it too. Everything else stays neutral: white focus, " + (if (t.gold) "gold" else "white") + " progress bars, red for live.",
            x + 40 * U, py + 420 * U, pW - 80 * U, 80 * U, 24f, SEC)
    }

    // ---------- program info: full-screen page over the video ----------
    val infoScrim = Paint(); val infoScrim2 = Paint()
    fun infoButtons(ch: Ch, pr: Prog?): List<Pair<String, String>> {
        val now = System.currentTimeMillis(); val out = mutableListOf<Pair<String, String>>()
        val cu = pr != null && pr.stop <= now && a.canCatchup(ch, pr)
        out.add((if (cu) "Watch Catch-up" else if (pr != null && isLive(pr)) "Watch Live" else "Watch") to "play")
        if (pr != null && pr.start > now) out.add((if (a.hasRem(ch, pr)) "Remove Reminder" else "Remind Me") to "bell")
        out.add((if (ch.url in a.favs) "Unfavorite" else "Favorite") to "star")
        out.add("Guide" to "grid")
        return out
    }
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
            val bw = maxOf(250 * U, tw(lab, 29f, 600) + 110 * U); button(c, bx, 600 * U, bw, 84 * U, i == infoBtn, ic, lab); bx += bw + 18 * U
        }
        // later on this channel (display only)
        val later = if (pr != null) a.progsOf(ch).filter { it.start >= pr.stop }.take(4) else a.progsOf(ch).filter { it.start > now }.take(4)
        if (later.isNotEmpty()) {
            hdr(c, "Later on ${ch.name}", x, 785 * U, w - 2 * x)
            later.forEachIndexed { i, lp ->
                val cx = x + i * 430 * U; val cy = 812 * U
                rr(c, cx, cy, 400 * U, 170 * U, 16 * U, 0x14FFFFFF)
                txt(c, a.hm.format(lp.start), cx + 24 * U, cy + 38 * U, 200 * U, 24f, SEC, 600)
                if (a.hasRem(ch, lp)) { tp.letterSpacing = 0.03f; val rw = tw("REMINDER", 20f, 700); txt(c, "REMINDER", cx + 376 * U - rw, cy + 38 * U, rw + 4 * U, 20f, 0, 700, accent = true); tp.letterSpacing = 0f; icon(c, "bell", cx + 362 * U - rw, cy + 38 * U, 22 * U, 0, true) }
                para(c, lp.title, cx + 24 * U, cy + 64 * U, 352 * U, 80 * U, 28f, WHITE, 1.1f)
            }
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

    override fun onKeyDown(k: Int, e: KeyEvent): Boolean {
        val up = k == KeyEvent.KEYCODE_DPAD_UP; val dn = k == KeyEvent.KEYCODE_DPAD_DOWN
        val lf = k == KeyEvent.KEYCODE_DPAD_LEFT; val rt = k == KeyEvent.KEYCODE_DPAD_RIGHT
        val ok = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
        if (k == KeyEvent.KEYCODE_BACK) {
            when {
                side -> side = false
                mode == 4 -> { if (subOpen) subOpen = false else if (backMode == 0) close() else mode = backMode }
                mode == 5 -> mode = backMode
                mode == 6 -> { a.setTheme(a.themeIdx, false); mode = 8 }   // back to settings, preview dropped
                mode == 8 -> { a.settingsDone(); mode = 3 }
                mode == 9 -> { if (grpGrab) grpGrab = false else mode = 8 }   // drop the group, then back to settings
                mode == 10 || mode == 13 -> mode = 8                        // back to settings
                mode == 12 -> if (rbSub > 0) rbSub = 0 else mode = 3          // back to the main menu
                else -> close()
            }
            touch(); invalidate(); return true
        }
        if (mode == 12 && rbSub == 2) {   // waiting for a button: whatever comes next is added to the selected action
            val act = keyRows().getOrNull(rbSel)?.act
            if (e.repeatCount == 0 && act != null) {
                if (!keyUsable(k)) a.flash("That button can't be used here", 2000)
                else { val from = a.keys.add(act, k); a.flash(keyLabel(k) + (if (from != null) " moved from ${from.name}" else " added"), 2500); rbSub = 0 }
            }
            touch(); invalidate(); return true
        }
        if (!(up || dn || lf || rt || ok)) return super.onKeyDown(k, e)
        val ch = a.shown.getOrNull(sel)
        if (side) {
            val n = maxOf(1, if (sideFocus == 2) a.sources.size else a.groups.size)
            when {
                up -> if (sideFocus == 2) plSel = (plSel + n - 1) % n else catSel = (catSel + n - 1) % n
                dn -> if (sideFocus == 2) plSel = (plSel + 1) % n else catSel = (catSel + 1) % n
                lf -> if (sideFocus == 1) { sideLvl = 2; sideFocus = 2; plSel = a.act }
                rt -> if (sideFocus == 2) { sideLvl = 1; sideFocus = 1 } else sideLvl = 0
                ok -> if (sideFocus == 2) { a.activate(plSel, false); catSel = 1; sideFocus = 1 } else { sideLvl = 0; a.selectGroup(catSel) }
            }
        } else if (mode == 11) {
            when {
                lf || rt -> remBtn = 1 - remBtn
                ok -> { val c0 = remCh; close(); if (remBtn == 0 && c0 != null) { a.play(c0); a.switchTo(c0) } }
            }
        } else if (mode == 3) {
            val n = a.menuItems().size
            when { up -> menuSel = (menuSel + n - 1) % n; dn -> menuSel = (menuSel + 1) % n; ok -> a.menuAct(menuSel) }
        } else if (mode == 4) {
            val n = a.subOpts(subI).first.size
            when {
                up -> if (subOpen) oj = (oj + dir).coerceIn(0, maxOf(0, n - 1)) else if (backMode == 0) a.zap(dir)
                dn -> if (subOpen) oj = (oj - dir).coerceIn(0, maxOf(0, n - 1)) else if (backMode == 0) a.zap(-dir)
                lf -> if (quickHide && backMode == 0) open(1) else if (!subOpen) { subI = maxOf(0, subI - 1) } else if (subI == 3) oj = maxOf(0, oj - a.AVD_JUMP)
                rt -> if (quickHide && backMode == 0) open(2) else if (!subOpen) { subI = minOf(3, subI + 1) } else if (subI == 3) oj = minOf(n - 1, oj + a.AVD_JUMP)
                ok -> if (quickHide) quickHide = false   // after a channel change the first OK only picks the tiles up
                else if (subOpen) { a.subApply(subI, oj); subOpen = false } else {
                    val o = a.subOpts(subI)
                    if (o.first.size > 1) { oj = o.second.coerceAtLeast(0); subOpen = true }   // one option: the tile is dimmed and says so
                }
            }
        } else if (mode == 5) {
            val c0 = infoCh; val p0 = infoProg; val bs = if (c0 != null) infoButtons(c0, p0) else emptyList()
            when {
                lf -> infoBtn = maxOf(0, infoBtn - 1)
                rt -> infoBtn = minOf(bs.size - 1, infoBtn + 1)
                ok -> if (c0 != null) when (bs.getOrNull(infoBtn)?.second) {
                    "play" -> { close(); if (p0 != null && p0.stop <= System.currentTimeMillis() && a.canCatchup(c0, p0)) a.playCatchup(c0, p0) else a.play(c0) }
                    "bell" -> if (p0 != null) a.toggleRem(c0, p0)
                    "star" -> { a.toggleFav(c0.url); a.selectGroup(a.grp, true) }
                    "grid" -> openGuide(c0)
                }
            }
        } else if (mode == 8) {
            val n = a.settingsItems().size
            when { up -> setSel = (setSel + n - 1) % n; dn -> setSel = (setSel + 1) % n; lf -> a.settingAct(setSel, -1); rt -> a.settingAct(setSel, 1); ok -> a.settingAct(setSel, 1) }
        } else if (mode == 9) {
            val gn = a.favGroupNames(); val n = gn.size; val mv = gn.count { it.first != "other" }   // "Other" is pinned last and cannot be moved
            when {
                up || dn -> {
                    val dl = if (up) -1 else 1
                    if (grpGrab) { val to = grpSel + dl; if (to in 0 until mv) { a.moveFavGroup(grpSel, to); grpSel = to } }
                    else if (n > 0) grpSel = (grpSel + dl + n) % n
                }
                ok -> if (grpSel < mv) grpGrab = !grpGrab
            }
        } else if (mode == 12) {
            val rows = keyRows(); val r = rows.getOrNull(rbSel)
            when {
                (up || dn) && rbSub == 1 -> rbOpt = 1 - rbOpt
                up -> rbSel = (rbSel + rows.size - 1) % rows.size
                dn -> rbSel = (rbSel + 1) % rows.size
                ok && rbSub == 1 -> if (rbOpt == 0) rbSub = 2 else { a.keys.reset(r?.act); a.flash("${r?.label} reset", 2000); rbSub = 0 }
                ok && r?.reset == true -> { a.keys.reset(null); a.flash("All buttons reset", 2000) }
                ok && r?.act != null -> { rbSub = 1; rbOpt = 0 }
            }
        } else if (mode == 13) {
            // About has nothing to select; only Back (handled above)
        } else if (mode == 10) {
            val n = a.errLog.size
            when { up -> logSel = maxOf(0, logSel - 1); dn -> logSel = minOf(maxOf(0, n - 1), logSel + 1); ok -> { a.errLog.clear(); logSel = 0 } }
        } else if (mode == 6) {
            val n = THEMES.size
            when {
                up -> { themeSel = (themeSel + n - 1) % n; a.setTheme(themeSel, false) }   // moving through the list previews each theme live
                dn -> { themeSel = (themeSel + 1) % n; a.setTheme(themeSel, false) }
                ok -> a.setTheme(themeSel, true)
            }
        } else if (mode == 1) {
            when {
                up -> sel = wrap(sel + dir)
                dn -> sel = wrap(sel - dir)
                lf -> if (oi > 0) oi-- else openSide()
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
                        1 -> { sel = wrap(sel + sg); progI = defProg() }
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
                lf -> if (col == 2 && oi > 0) oi-- else if (col > 0) col-- else openSide()
                rt -> if (col < 2) col++ else oi = minOf(oi + 1, a.nOpts(true, ch, dp.getOrNull(progI)) - 1)
                ok -> if (ch != null) a.chOption(ch, oi, if (col == 2) dp.getOrNull(progI) else null)
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
