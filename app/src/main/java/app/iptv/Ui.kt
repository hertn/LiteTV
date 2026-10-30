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

class Ui(ctx: Context, val a: MainActivity) : View(ctx) {
    val d = ctx.resources.displayMetrics.density
    val fs: Float get() = a.fontScale   // text size setting (Settings > Font size); only text is scaled, not the layout
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
    // colors come from the active theme (getters, so a theme change shows up on the next redraw)
    val PUR: Int get() = C_PUR
    val ROW: Int get() = C_ROW
    val LOGOBG: Int get() = C_ROW
    val SEL: Int get() = C_SEL
    val PROG: Int get() = C_PROG
    val TILE: Int get() = C_TILE
    val CHIP = 0xFF000000.toInt()   // time / date / playlist bar is always black, whatever the theme
    val PAST: Int get() = C_PAST
    val INFO: Int get() = C_INFO
    val GRAY = 0xFFB8BECC.toInt()
    val BAR: Int get() = C_PUR
    val TRACK = 0x55FFFFFF.toInt()
    val LAV: Int get() = T.lav
    val LAVT: Int get() = T.lavT
    val dd = java.text.SimpleDateFormat("dd.MM"); val wd = java.text.SimpleDateFormat("EEE"); val wdd = java.text.SimpleDateFormat("EEE dd.MM")
    val S = 6; val dir = if (REVERSED) 1 else -1
    // mode: 0 hidden, 1 channel list, 2 date/channel/program, 3 main menu (OK), 4 channel settings, 5 program info, 6 theme list, 7 custom theme editor, 8 settings, 9 favorite group order, 10 error log
    var mode = 0; var sideLvl = 0; var sideFocus = 1; var catSel = 1; var plSel = 0   // sideLvl: 0 none, 1 categories, 2 playlists + categories
    var side: Boolean
        get() = sideLvl > 0
        set(v) { if (!v) sideLvl = 0 }
    var sel = 0; var oi = 0; var col = 1; var dayI = 0; var progI = 0; var menuSel = 0
    var subI = 0; var subOpen = false; var oj = 0
    var themeSel = 0; var cRow = 0; var setSel = 0; var grpSel = 0; var grpGrab = false; var logSel = 0
    var infoCh: Ch? = null; var infoProg: Prog? = null; var backMode = 1
    var selSince = SystemClock.uptimeMillis()
    var menuShader: Shader? = null; var menuShaderW = 0
    init { isFocusable = true; isFocusableInTouchMode = true; alpha = UI_ALPHA }

    // every key press (and every screen change) restarts the idle timer; the screens close themselves after 30 s of nothing
    val idle = Runnable { if (mode != 0) close() }
    fun touch() { selSince = SystemClock.uptimeMillis(); removeCallbacks(idle); if (mode != 0) postDelayed(idle, 30000L) }
    fun rowH() = height * ROW_SCALE / 7.5f
    fun wrap(i: Int): Int { val n = a.shown.size; return if (n == 0) 0 else ((i % n) + n) % n }
    fun chAt(i: Int): Ch? = a.shown.getOrNull(i)

    fun txt(c: Canvas, s: String, x: Float, y: Float, maxW: Float, sp: Float, color: Int, bold: Boolean = false, center: Boolean = false) {
        tp.textSize = sp * d * fs; tp.color = color; tp.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        tp.textAlign = if (center) Paint.Align.CENTER else Paint.Align.LEFT
        c.drawText(TextUtils.ellipsize(s, tp, maxOf(1f, maxW), TextUtils.TruncateAt.END).toString(), x, y, tp)
    }
    // like txt, but the selected item scrolls sideways (after 1 s without moving) when the text is too long
    fun stxt(c: Canvas, s: String, x: Float, y: Float, maxW: Float, sp: Float, color: Int, bold: Boolean, sel: Boolean, center: Boolean = false) {
        tp.textSize = sp * d * fs; tp.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        val tw = tp.measureText(s)
        if (!sel || tw <= maxW) { txt(c, s, if (center) x + maxW / 2 else x, y, maxW, sp, color, bold, center); return }
        val el = SystemClock.uptimeMillis() - selSince - 1000; val over = tw - maxW
        val off = if (el <= 0) 0f else minOf(over, el * 0.05f * d)
        tp.color = color; tp.textAlign = Paint.Align.LEFT
        c.save(); c.clipRect(x, y - 30 * d, x + maxW, y + 12 * d); c.drawText(s, x - off, y, tp); c.restore()
        if (off < over) postInvalidateOnAnimation() else if (el <= 0) postInvalidateDelayed(-el + 16)
    }
    fun fill(c: Canvas, x: Float, y: Float, w: Float, h: Float, color: Int) { p.color = color; c.drawRect(x, y, x + w, y + h, p) }
    fun border(c: Canvas, x: Float, y: Float, w: Float, h: Float) {
        p.style = Paint.Style.STROKE; p.strokeWidth = 3 * d; p.color = PUR
        c.drawRect(x + 1.5f * d, y + 1.5f * d, x + w - 1.5f * d, y + h - 1.5f * d, p); p.style = Paint.Style.FILL
    }
    fun progress(c: Canvas, x: Float, y: Float, w: Float, pr: Prog) {
        val f = ((System.currentTimeMillis() - pr.start).toFloat() / maxOf(1L, pr.stop - pr.start)).coerceIn(0f, 1f)
        fill(c, x, y, w, 4 * d, TRACK); fill(c, x, y, w * f, 4 * d, BAR)
    }
    fun drawLogo(c: Canvas, ch: Ch, x: Float, y: Float, w: Float, h: Float) {
        val b = a.logoOf(ch)
        if (b == null) { txt(c, ch.name, x + w / 2, y + h * 0.55f, w - 12 * d, 14f, -1, true, true); return }
        val s = minOf((w - 16 * d) / b.width, (h - 16 * d) / b.height); val bw = b.width * s; val bh = b.height * s
        c.drawBitmap(b, null, RectF(x + (w - bw) / 2, y + (h - bh) / 2, x + (w + bw) / 2, y + (h + bh) / 2), p)
    }
    fun split2(s: String): List<String> {
        val i = s.lastIndexOf(' ', s.length / 2 + 2).let { if (it <= 0) s.indexOf(' ') else it }
        return if (i <= 0) listOf(s) else listOf(s.substring(0, i), s.substring(i + 1))
    }
    // option tiles are as wide as their text needs (long ones wrap onto two lines)
    fun optBar(c: Canvas, x: Float, y: Float, w: Float, h: Float, labels: List<String>) {
        tp.textSize = 17 * d * fs; tp.typeface = Typeface.DEFAULT_BOLD
        val pad = 22 * d; val cap = w * 0.38f
        val lines = labels.map { s -> if (tp.measureText(s) + 2 * pad <= cap) listOf(s) else split2(s) }
        val ws = lines.map { ls -> (ls.maxOf { tp.measureText(it) } + 2 * pad).coerceIn(110 * d, cap) }
        var start = 0
        while (start < oi && ws.subList(start, oi + 1).sum() > w) start++
        var ix = x
        for (k in start until labels.size) {
            if (ix >= x + w) break
            val tw = ws[k]; fill(c, ix, y, minOf(tw, x + w - ix) - 2, h - 2, if (k == oi) PUR else TILE)
            val ls = lines[k]
            if (ls.size == 1) txt(c, ls[0], ix + pad, y + h * 0.58f, tw - 2 * pad, 17f, -1, true)
            else { txt(c, ls[0], ix + pad, y + h * 0.44f, tw - 2 * pad, 17f, -1, true); txt(c, ls[1], ix + pad, y + h * 0.70f, tw - 2 * pad, 17f, -1, true) }
            ix += tw
        }
    }
    // time / date / playlist bar: sits below the options bar with a gap, in the same near-black as the panels
    // 3rd chip of the time/date bar: favorites and search mix playlists, so show where the selected channel comes from; otherwise the active playlist
    fun srcName(ch: Ch?): String =
        if (a.multi()) (ch?.let { k -> a.sources.find { it.id == k.src }?.name } ?: "") else (a.sources.getOrNull(a.act)?.name ?: "")

    // wraps text into a box of this size; what does not fit is cut at the last whole line and ends in "..."
    fun fitText(s: String, wPx: Int, hPx: Float, spacing: Float): StaticLayout {
        var sl = StaticLayout(s, tp, wPx, Layout.Alignment.ALIGN_NORMAL, spacing, 0f, false)
        val lh = sl.getLineBottom(0).toFloat(); val fit = maxOf(1, (hPx / lh).toInt())
        if (sl.lineCount > fit) {
            val t = s.substring(0, sl.getLineEnd(fit - 1)).trimEnd().dropLast(2).trimEnd() + "\u2026"
            sl = StaticLayout(t, tp, wPx, Layout.Alignment.ALIGN_NORMAL, spacing, 0f, false)
        }
        return sl
    }

    fun chips(c: Canvas, items: List<String>, x: Float, right: Boolean) {
        tp.textSize = 14 * d * fs; tp.typeface = Typeface.DEFAULT_BOLD; val pad = 12 * d
        val ws = items.map { tp.measureText(it) + 2 * pad }; val tot = ws.sum()
        val gap = 6 * d; val y = rowH() * (S + 1) + gap; val hh = (height - y - gap).coerceIn(16 * d, 30 * d)
        var cx = if (right) x - tot else x
        fill(c, cx, y, tot, hh, CHIP)
        for ((i, s) in items.withIndex()) {
            txt(c, s, cx + pad, y + hh * 0.7f, ws[i], 14f, -1, true); cx += ws[i]
            if (i < items.size - 1) fill(c, cx - 1, y + 5 * d, 1f, hh - 10 * d, 0x66FFFFFF)
        }
    }

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
    fun show(m: Int) { mode = m; side = false; visibility = View.VISIBLE; a.banner.visibility = View.GONE; touch(); requestFocus() }
    fun open(m: Int) { oi = 0; col = 1; show(m); reset(false) }
    fun openGuide(ch: Ch) { open(2); val k = a.shown.indexOf(ch); if (k >= 0) { sel = k; progI = defProg(); invalidate() } }
    fun openMenu() { menuSel = 0; show(3); invalidate() }
    fun openThemes() { themeSel = a.themeIdx; show(6); invalidate() }
    fun openSettings() { setSel = 0; show(8); invalidate() }
    fun openFavGroups() { grpSel = 0; grpGrab = false; show(9); invalidate() }
    fun openLog() { logSel = 0; show(10); invalidate() }
    fun openSub() { backMode = if (mode == 2) 2 else 1; subI = 0; subOpen = false; show(4); invalidate() }
    fun openInfo(ch: Ch, pr: Prog?) { backMode = if (mode == 2) 2 else 1; infoCh = ch; infoProg = pr; show(5); invalidate() }
    fun close() {
        if (mode == 6) a.setTheme(a.themeIdx, false)   // leaving the theme list without pressing OK: drop the preview
        a.settingsDone()
        grpGrab = false; mode = 0; sideLvl = 0; removeCallbacks(idle); visibility = View.GONE; if (a.searching) a.endSearch()
    }
    fun openSide(pl: Boolean = false) {
        sideLvl = if (pl) 2 else 1; sideFocus = if (pl) 2 else 1
        catSel = a.grp.coerceIn(0, maxOf(0, a.groups.size - 1)); plSel = a.act; touch()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val gx = w * 0.06f   // gap on the left so the video shows beside the lists
        val pw = w * 0.19f; val cw = w * 0.285f; val panels = sideLvl > 0 && (mode == 1 || mode == 2)
        val shift = if (panels) (if (sideLvl >= 2) pw else 0f) + cw + 8 * d else gx   // lists move right when panels open, nothing overlaps
        when (mode) {
            1 -> { c.save(); c.translate(shift, 0f); drawChannels(c, w - gx, h); c.restore() }
            2 -> { val g2 = if (panels) gx else 2f; c.save(); c.translate(if (panels) shift else g2, 0f); drawGuide(c, w - g2, h); c.restore() }   // guide sits 2px from the screen edge (same gap as between channel rows)
            3 -> drawMenu(c, w, h)
            4 -> { c.save(); c.translate(gx, 0f); drawSub(c, w - gx); c.restore(); postInvalidateDelayed(700) }
            5 -> drawInfo(c, w, h)
            6, 7 -> drawThemes(c, w, h)
            8 -> drawSettings(c, w, h)
            9 -> drawFavGroups(c, w, h)
            10 -> drawErrLog(c, w, h)
        }
        if (panels) {
            var x = 0f
            if (sideLvl >= 2) { drawPanel(c, x, pw, h, "Playlists", a.sources.map { it.name }, plSel, a.act, sideFocus == 2); x += pw }
            drawPanel(c, x, cw, h, "Categories", a.groups, catSel, a.grp, sideFocus == 1)
        }
    }

    fun drawChannels(c: Canvas, w: Float, h: Float) {
        val rh = rowH(); val lw = w * 0.31f; val logoW = lw * 0.36f; val tx = logoW + 10 * d; val tmax = lw - tx - 8 * d
        if (a.shown.isEmpty()) { fill(c, 0f, 0f, lw, h, ROW); txt(c, "No channels", 20 * d, h / 2, lw - 40 * d, 18f, GRAY) }
        // the selected row and the options bar stay fixed at the bottom slot; the list scrolls through it and never wraps visually
        val n = a.shown.size; val vp = if (REVERSED) n - 1 - sel else sel
        for (s in 0..7) {
            val vi = vp - S + s; if (vi < 0 || vi >= n) continue
            val ch = a.shown[if (REVERSED) n - 1 - vi else vi]; val y = s * rh; val pr = a.now(ch); val on = s == S
            fill(c, 0f, y, logoW, rh - 2, LOGOBG); fill(c, logoW, y, lw - logoW, rh - 2, ROW); drawLogo(c, ch, 0f, y, logoW, rh)
            val tag = if (a.multi()) (a.sources.find { it.id == ch.src }?.let { "  \u00b7  ${it.name}" } ?: "") else ""
            stxt(c, "${ch.num}: ${ch.name}$tag", tx, y + rh * 0.24f, tmax, 13f, GRAY, false, on)
            stxt(c, pr?.title ?: "", tx, y + rh * 0.50f, tmax, 17f, -1, true, on)
            if (pr != null) { txt(c, "${a.hm.format(pr.start)} - ${a.hm.format(pr.stop)}", tx, y + rh * 0.74f, tmax, 13f, -1); progress(c, tx, y + rh * 0.84f, lw - tx - 10 * d, pr) }
            if (on) border(c, 0f, y, lw, rh)
        }
        a.shown.getOrNull(sel)?.let { optBar(c, lw, S * rh, w - lw, rh, a.chOptions(it, false)) }
        val now = System.currentTimeMillis()
        chips(c, listOf(a.hm.format(now), a.dm.format(now), srcName(a.shown.getOrNull(sel))).filter { it.isNotEmpty() }, lw + 8 * d, false)
        chips(c, listOf(a.groups.getOrElse(a.grp) { "" }, "${a.shown.size} channels"), w - 8 * d, true)
        a.season().let { if (it != 0 && a.shown.isNotEmpty()) deco(c, it, -rh * 0.14f, S * rh - rh * 0.39f, rh * 0.52f) }
    }

    // small "rewind" mark on programmes that can be rewatched
    fun rewindMark(c: Canvas, cx: Float, cy: Float, r: Float) {
        p.style = Paint.Style.FILL; p.color = LAV
        val q = Path()
        q.moveTo(cx, cy - r); q.lineTo(cx - r, cy); q.lineTo(cx, cy + r); q.close()
        q.moveTo(cx + r, cy - r); q.lineTo(cx, cy); q.lineTo(cx + r, cy + r); q.close()
        c.drawPath(q, p)
    }

    fun cell(c: Canvas, x: Float, y: Float, w: Float, h: Float, selected: Boolean, focused: Boolean, bg: Int = ROW) {
        fill(c, x, y, w - 2, h - 2, if (selected) SEL else bg); if (selected && focused) border(c, x, y, w - 2, h - 2)
    }

    fun drawGuide(c: Canvas, w: Float, h: Float) {
        val rh = rowH(); val dw = w * 0.115f; val cw = w * 0.115f; val px = dw + cw; val pw = w * 0.31f
        val dys = days(); val ch = a.shown.getOrNull(sel); val now = System.currentTimeMillis()
        val dp = if (ch != null) a.dayProgs(ch, dys[dayI.coerceIn(0, dys.lastIndex)]) else emptyList()
        for (s in 0..7) {
            val y = s * rh; val rel = dir * (S - s); val on = s == S
            dys.getOrNull(dayI + rel)?.let { day ->
                cell(c, 0f, y, dw, rh, on, col == 0)
                val i = dys.indexOf(day)
                txt(c, dd.format(day), dw / 2, y + rh * 0.52f, dw, 22f, -1, true, true)
                val ti = a.todayIdx()
                txt(c, if (i == ti) "Today" else if (i == ti + 1) "Tomorrow" else if (i == ti - 1) "Yesterday" else wd.format(day), dw / 2, y + rh * 0.78f, dw, 14f, GRAY, false, true)
            }
            chAt(sel + rel)?.let { cc ->
                cell(c, dw, y, cw, rh, false, false); fill(c, dw, y, cw - 2, rh - 2, LOGOBG); drawLogo(c, cc, dw, y, cw, rh)
                if (on && col == 1) border(c, dw, y, cw - 2, rh - 2)   // after the logo, otherwise the logo background paints over it
            }
            dp.getOrNull(progI - (s - S))?.let { pr ->   // upcoming shows above the selected one, finished shows below it
                cell(c, px, y, pw, rh, false, false, if (pr.start > now) PAST else PROG)   // upcoming shows use the lighter grey; current and finished shows use the dark panel colour; selection is shown by the border only
                if (on && col == 2) border(c, px, y, pw - 2, rh - 2)
                txt(c, a.hm.format(pr.start), px + 14 * d, y + rh * 0.58f, 70 * d, 20f, -1, true)
                val cu = ch != null && pr.start <= now && a.canCatchup(ch, pr)
                if (cu) rewindMark(c, px + pw - 24 * d, y + rh * 0.5f, 7 * d)
                stxt(c, pr.title, px + 84 * d, y + rh * 0.52f, pw - 94 * d - (if (cu) 24 * d else 0f), 17f, -1, true, on && col == 2)
                if (now >= pr.start && now < pr.stop) progress(c, px + 84 * d, y + rh * 0.74f, pw - 100 * d, pr)
            }
        }
        if (dp.isEmpty()) { cell(c, px, S * rh, pw, rh, true, col == 2); txt(c, "No guide data", px + 14 * d, S * rh + rh * 0.58f, pw - 20 * d, 17f, GRAY) }
        // right side like the Telia guide: the info box sits straight on top of the options bar, both start where the programme column ends
        val bx = px + pw; val bw = w - bx; val pr = dp.getOrNull(progI)
        if (pr != null) {
            val bh = h * 0.34f; val by = S * rh - bh
            fill(c, bx, by, bw, bh, INFO)
            txt(c, pr.title, bx + 24 * d, by + 42 * d, bw - 48 * d, 22f, -1, true)
            val ds = if (ch != null) a.descOf(ch, pr) else pr.desc
            if (ds.isNotEmpty()) {
                tp.textSize = 15 * d * fs; tp.color = 0xFFD5DAE6.toInt(); tp.typeface = Typeface.DEFAULT; tp.textAlign = Paint.Align.LEFT
                val sl = fitText(ds, (bw - 48 * d).toInt(), bh - 72 * d, 1.15f)
                c.save(); c.clipRect(bx, by + 56 * d, bx + bw, by + bh - 10 * d); c.translate(bx + 24 * d, by + 62 * d); sl.draw(c); c.restore()
            }
        }
        if (ch != null && col == 2) optBar(c, bx, S * rh, bw, rh, a.chOptions(ch, true, pr))   // only once the programme column is selected
        chips(c, listOf(a.hm.format(now), a.dm.format(now), srcName(ch)).filter { it.isNotEmpty() }, bx, false)
        val sx = when (col) { 0 -> 0f; 1 -> dw; else -> px }   // the column the selector border is on
        a.season().let { if (it != 0 && ch != null) deco(c, it, if (sx > 0f) sx - rh * 0.1f else 0f, S * rh - rh * 0.39f, rh * 0.52f) }
    }

    // "Channel settings": audio / subtitles / aspect / audio delay tiles; OK on a tile pops its options up above it
    fun drawSub(c: Canvas, w: Float) {
        val rh = rowH(); val lw = w * 0.31f; val logoW = lw * 0.36f; val y = S * rh
        val ch = a.cur ?: a.shown.getOrNull(sel) ?: return
        fill(c, 0f, y, logoW, rh - 2, LOGOBG); drawLogo(c, ch, 0f, y, logoW, rh); border(c, 0f, y, logoW, rh)
        val labels = listOf("Audio", "Subtitles", "Aspect ratio", "Audio delay"); var x = logoW
        for (i in 0..3) {
            val (opts, cs) = a.subOpts(i)
            tp.textSize = 17 * d * fs; tp.typeface = Typeface.DEFAULT_BOLD
            val wide = if (i == 3) listOf(opts.first()) else opts   // the delay list is long; its widest entry is the first
            val tw = (maxOf(wide.maxOf { tp.measureText(it) }, tp.measureText(labels[i])) + 40 * d).coerceIn(w * 0.14f, w * 0.30f)
            val v = opts.getOrElse(cs) { "" }
            fill(c, x, y, tw - 2, rh - 2, if (i == subI) PUR else TILE)
            txt(c, v, x + 20 * d, y + rh * 0.50f, tw - 40 * d, 17f, -1, true)
            txt(c, labels[i], x + 20 * d, y + rh * 0.74f, tw - 40 * d, 13f, -1)
            if (subOpen && i == subI) {
                // the popup lists the options above the tile and scrolls to keep the highlighted one in view,
                // so every option can be reached from whichever one is selected (the tile shows the one in use, marked with a dot)
                val n = opts.size; val vis = 5
                val ord = if (dir == 1) oj else n - 1 - oj
                val top = (ord - vis + 1).coerceIn(0, maxOf(0, n - vis))
                for (j in 1..vis) {
                    val od = top + j - 1; if (od >= n) break
                    val k = if (dir == 1) od else n - 1 - od; val oy = y - j * rh * 0.85f
                    fill(c, x, oy, tw - 2, rh * 0.85f - 2, if (k == oj) PUR else TILE)
                    if (k == oj) { p.style = Paint.Style.STROKE; p.strokeWidth = 2 * d; p.color = -1; c.drawRect(x + d, oy + d, x + tw - 2 - d, oy + rh * 0.85f - 2 - d, p); p.style = Paint.Style.FILL }
                    txt(c, (if (k == cs) "\u25CF " else "") + opts[k], x + 20 * d, oy + rh * 0.5f, tw - 40 * d, 17f, -1, true)
                }
            }
            x += tw
        }
    }

    // seasonal decoration on the selector corner: every shape is drawn in a 0..1 box scaled to s
    fun deco(c: Canvas, id: Int, x: Float, y: Float, s: Float) {
        c.save()
        if (id < SE_CANDLES) c.rotate(-12f, x + s / 2, y + s / 2)
        p.style = Paint.Style.FILL; p.strokeCap = Paint.Cap.ROUND
        fun clr(v: Int) { p.color = v }
        fun rect(l: Float, t: Float, r: Float, b: Float) = c.drawRect(x + l * s, y + t * s, x + r * s, y + b * s, p)
        fun rr(l: Float, t: Float, r: Float, b: Float, rad: Float) = c.drawRoundRect(RectF(x + l * s, y + t * s, x + r * s, y + b * s), rad * s, rad * s, p)
        fun ov(l: Float, t: Float, r: Float, b: Float) = c.drawOval(RectF(x + l * s, y + t * s, x + r * s, y + b * s), p)
        fun dot(cx: Float, cy: Float, r: Float) = c.drawCircle(x + cx * s, y + cy * s, r * s, p)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float, w: Float) {
            p.style = Paint.Style.STROKE; p.strokeWidth = w * s
            c.drawLine(x + x1 * s, y + y1 * s, x + x2 * s, y + y2 * s, p); p.style = Paint.Style.FILL
        }
        fun ring(cx: Float, cy: Float, r: Float, w: Float) {
            p.style = Paint.Style.STROKE; p.strokeWidth = w * s; c.drawCircle(x + cx * s, y + cy * s, r * s, p); p.style = Paint.Style.FILL
        }
        fun poly(vararg v: Float) {
            val q = Path(); q.moveTo(x + v[0] * s, y + v[1] * s)
            var i = 2; while (i < v.size) { q.lineTo(x + v[i] * s, y + v[i + 1] * s); i += 2 }
            q.close(); c.drawPath(q, p)
        }
        when (id) {
            SE_PARTY -> {
                clr(0xFFE91E8C.toInt()); poly(0.2f, 0.88f, 0.8f, 0.88f, 0.5f, 0.12f)
                clr(-1); line(0.31f, 0.64f, 0.69f, 0.64f, 0.06f); line(0.39f, 0.44f, 0.61f, 0.44f, 0.06f)
                clr(0xFFFFD54F.toInt()); dot(0.5f, 0.12f, 0.09f)
                clr(0xFF4FC3F7.toInt()); dot(0.12f, 0.34f, 0.04f); dot(0.9f, 0.52f, 0.04f)
            }
            SE_HEART -> { clr(0xFFE53950.toInt()); dot(0.35f, 0.36f, 0.22f); dot(0.65f, 0.36f, 0.22f); poly(0.14f, 0.46f, 0.86f, 0.46f, 0.5f, 0.9f) }
            SE_FLAG_EE -> {
                clr(0xFFB0B0B0.toInt()); rect(0.14f, 0.08f, 0.2f, 0.96f)
                clr(0xFF0072CE.toInt()); rect(0.2f, 0.14f, 0.92f, 0.3f)
                clr(0xFF000000.toInt()); rect(0.2f, 0.3f, 0.92f, 0.46f)
                clr(-1); rect(0.2f, 0.46f, 0.92f, 0.62f)
            }
            SE_BUN -> {
                clr(0xFFDDA25A.toInt()); ov(0.1f, 0.55f, 0.9f, 0.9f)
                clr(-1); ov(0.16f, 0.43f, 0.84f, 0.67f)
                clr(0xFFE8B96A.toInt()); ov(0.14f, 0.18f, 0.86f, 0.56f)
                clr(-1); dot(0.4f, 0.3f, 0.03f); dot(0.6f, 0.34f, 0.03f); dot(0.5f, 0.25f, 0.025f)
            }
            SE_WILLOW -> {
                clr(0xFF8D6E63.toInt()); line(0.22f, 0.96f, 0.72f, 0.1f, 0.05f); line(0.36f, 0.76f, 0.14f, 0.4f, 0.04f)
                clr(0xFFE6E0D4.toInt()); dot(0.38f, 0.7f, 0.07f); dot(0.48f, 0.52f, 0.07f); dot(0.58f, 0.34f, 0.07f); dot(0.68f, 0.17f, 0.06f)
                dot(0.26f, 0.6f, 0.06f); dot(0.18f, 0.47f, 0.055f)
            }
            SE_EGG -> {
                clr(0xFFF48FB1.toInt()); ov(0.24f, 0.1f, 0.76f, 0.92f)
                clr(0xFF4FC3F7.toInt()); rect(0.27f, 0.46f, 0.73f, 0.58f)
                clr(-1); dot(0.5f, 0.3f, 0.05f); dot(0.5f, 0.74f, 0.05f)
            }
            SE_VAPPU -> {
                clr(-1); rr(0.14f, 0.22f, 0.86f, 0.6f, 0.15f)
                clr(0xFF3A3A3A.toInt()); rect(0.12f, 0.58f, 0.88f, 0.7f); ov(0.22f, 0.66f, 0.78f, 0.86f)
                clr(0xFFFFD54F.toInt()); dot(0.5f, 0.64f, 0.045f)
            }
            SE_WREATH -> {
                clr(0xFF3E8E41.toInt()); ring(0.5f, 0.55f, 0.3f, 0.1f)
                val fc = intArrayOf(0xFFFFD54F.toInt(), -1, 0xFF5C9DFF.toInt())
                for (k in 0 until 8) { val an = k * Math.PI / 4; clr(fc[k % 3]); dot(0.5f + (0.3 * Math.cos(an)).toFloat(), 0.55f + (0.3 * Math.sin(an)).toFloat(), 0.085f) }
            }
            SE_FIREWORK -> {
                val fc = intArrayOf(0xFFE53935.toInt(), -1, 0xFF42A5F5.toInt())
                for (k in 0 until 8) {
                    val an = k * Math.PI / 4; clr(fc[k % 3])
                    line(0.5f + (0.14 * Math.cos(an)).toFloat(), 0.5f + (0.14 * Math.sin(an)).toFloat(), 0.5f + (0.43 * Math.cos(an)).toFloat(), 0.5f + (0.43 * Math.sin(an)).toFloat(), 0.07f)
                }
                clr(0xFFFFD54F.toInt()); dot(0.5f, 0.5f, 0.07f)
            }
            SE_CORNFLOWER -> {
                clr(0xFF4CAF50.toInt()); line(0.5f, 0.6f, 0.5f, 0.97f, 0.05f); poly(0.5f, 0.86f, 0.74f, 0.72f, 0.57f, 0.92f)
                clr(0xFF3D7EE8.toInt())
                for (k in 0 until 8) { val an = k * Math.PI / 4; dot(0.5f + (0.24 * Math.cos(an)).toFloat(), 0.4f + (0.24 * Math.sin(an)).toFloat(), 0.12f) }
                clr(0xFF2A2F7A.toInt()); dot(0.5f, 0.4f, 0.09f)
            }
            SE_LEAF -> {
                clr(0xFFD2481E.toInt())
                poly(0.50f, 0.05f, 0.57f, 0.24f, 0.72f, 0.17f, 0.69f, 0.42f, 0.92f, 0.36f, 0.80f, 0.56f, 0.90f, 0.70f, 0.57f, 0.68f, 0.55f, 0.88f,
                    0.45f, 0.88f, 0.43f, 0.68f, 0.10f, 0.70f, 0.20f, 0.56f, 0.08f, 0.36f, 0.31f, 0.42f, 0.28f, 0.17f, 0.43f, 0.24f)
                clr(0xFF6D4C41.toInt()); line(0.5f, 0.82f, 0.5f, 0.98f, 0.05f)
                clr(0xFFFFB27A.toInt()); line(0.5f, 0.8f, 0.5f, 0.3f, 0.025f)
            }
            SE_CAKE -> {
                clr(0xFFF48FB1.toInt()); rr(0.14f, 0.5f, 0.86f, 0.9f, 0.08f)
                clr(-1); rr(0.14f, 0.5f, 0.86f, 0.6f, 0.05f)
                clr(0xFF64B5F6.toInt()); rect(0.47f, 0.26f, 0.53f, 0.5f)
                clr(0xFFFFC107.toInt()); ov(0.43f, 0.08f, 0.57f, 0.27f)
                clr(0xFFFFD54F.toInt()); dot(0.3f, 0.74f, 0.03f); dot(0.5f, 0.78f, 0.03f); dot(0.7f, 0.74f, 0.03f)
            }
            SE_PUMPKIN -> {
                clr(0xFFFF8F1F.toInt()); ov(0.1f, 0.3f, 0.9f, 0.92f)
                clr(0xFF4C8C2B.toInt()); rr(0.45f, 0.12f, 0.55f, 0.34f, 0.03f)
                clr(0xFF3B1F00.toInt()); poly(0.28f, 0.5f, 0.42f, 0.5f, 0.35f, 0.62f); poly(0.58f, 0.5f, 0.72f, 0.5f, 0.65f, 0.62f); poly(0.34f, 0.74f, 0.66f, 0.74f, 0.5f, 0.84f)
            }
            SE_BAT -> {
                clr(0xFF7E57C2.toInt()); ov(0.42f, 0.38f, 0.58f, 0.72f)
                poly(0.43f, 0.4f, 0.45f, 0.26f, 0.5f, 0.38f); poly(0.57f, 0.4f, 0.55f, 0.26f, 0.5f, 0.38f)
                poly(0.43f, 0.46f, 0.14f, 0.28f, 0.04f, 0.52f, 0.2f, 0.48f, 0.3f, 0.66f, 0.43f, 0.6f)
                poly(0.57f, 0.46f, 0.86f, 0.28f, 0.96f, 0.52f, 0.8f, 0.48f, 0.7f, 0.66f, 0.57f, 0.6f)
                clr(0xFFFFEB3B.toInt()); dot(0.46f, 0.5f, 0.025f); dot(0.54f, 0.5f, 0.025f)
            }
            SE_ACORN -> {
                clr(0xFFC68642.toInt()); ov(0.27f, 0.4f, 0.73f, 0.94f)
                clr(0xFF6D4C41.toInt()); ov(0.2f, 0.2f, 0.8f, 0.55f)
                clr(0xFF5D4037.toInt()); rect(0.47f, 0.08f, 0.53f, 0.24f)
            }
            SE_TURKEY -> {
                val fc = intArrayOf(0xFFD32F2F.toInt(), 0xFFF57C00.toInt(), 0xFFFBC02D.toInt(), 0xFFF57C00.toInt(), 0xFFD32F2F.toInt())
                for (k in 0 until 5) { val an = Math.toRadians(-160.0 + k * 35.0); clr(fc[k]); dot(0.5f + (0.3 * Math.cos(an)).toFloat(), 0.52f + (0.3 * Math.sin(an)).toFloat(), 0.14f) }
                clr(0xFF8D5524.toInt()); dot(0.5f, 0.68f, 0.22f)
                clr(0xFFB07A4C.toInt()); dot(0.5f, 0.5f, 0.1f)
                clr(0xFFE53935.toInt()); dot(0.54f, 0.58f, 0.035f)
                clr(0xFFFFB300.toInt()); poly(0.47f, 0.52f, 0.53f, 0.52f, 0.5f, 0.58f)
                clr(0xFF000000.toInt()); dot(0.46f, 0.47f, 0.02f); dot(0.54f, 0.47f, 0.02f)
            }
            SE_FLAG_FI -> {
                clr(0xFFB0B0B0.toInt()); rect(0.14f, 0.08f, 0.2f, 0.96f)
                clr(-1); rect(0.2f, 0.14f, 0.92f, 0.64f)
                clr(0xFF003580.toInt()); rect(0.2f, 0.33f, 0.92f, 0.45f); rect(0.38f, 0.14f, 0.5f, 0.64f)
            }
            SE_HAT, SE_HAT_GIFT -> {
                val k = if (id == SE_HAT_GIFT) 0.84f else 1f
                clr(0xFFD6212E.toInt())
                val q = Path(); q.moveTo(x + 0.12f * k * s, y + 0.78f * k * s); q.lineTo(x + 0.88f * k * s, y + 0.78f * k * s)
                q.quadTo(x + 0.84f * k * s, y + 0.30f * k * s, x + 0.52f * k * s, y + 0.16f * k * s)
                q.quadTo(x + 0.30f * k * s, y + 0.28f * k * s, x + 0.12f * k * s, y + 0.78f * k * s); q.close(); c.drawPath(q, p)
                clr(-1); rr(0.08f * k, 0.70f * k, 0.92f * k, 0.90f * k, 0.09f); dot(0.52f * k, 0.15f * k, 0.1f * k)
                if (id == SE_HAT_GIFT) {
                    clr(0xFF2E9E57.toInt()); rect(0.62f, 0.62f, 0.96f, 0.96f)
                    clr(0xFFFFD54F.toInt()); rect(0.77f, 0.62f, 0.81f, 0.96f); rect(0.62f, 0.77f, 0.96f, 0.81f); dot(0.76f, 0.58f, 0.04f); dot(0.82f, 0.58f, 0.04f)
                }
            }
            else -> if (id >= SE_CANDLES) {   // Advent wreath: 4 candles, the first (id - 20) are lit
                val lit = id - SE_CANDLES + 1
                clr(0xFF2E7D32.toInt()); ov(0.06f, 0.68f, 0.94f, 0.94f)
                for (k in 0 until 4) {
                    val cx = 0.18f + k * 0.21f
                    clr(0xFFEDE7D9.toInt()); rect(cx - 0.06f, 0.4f, cx + 0.06f, 0.8f)
                    if (k < lit) { clr(0xFFFFC107.toInt()); ov(cx - 0.05f, 0.14f, cx + 0.05f, 0.38f); clr(0xFFFF7043.toInt()); ov(cx - 0.025f, 0.24f, cx + 0.025f, 0.38f) }
                }
            }
        }
        p.style = Paint.Style.FILL; p.strokeCap = Paint.Cap.BUTT
        c.restore()
    }

    // LiteTV logo: rounded square in the accent colour, white "L" with a lavender play triangle (same shapes as the app icon)
    fun drawMark(c: Canvas, x: Float, y: Float, s: Float) {
        p.style = Paint.Style.FILL; p.color = PUR; c.drawRoundRect(RectF(x, y, x + s, y + s), s * 0.22f, s * 0.22f, p)
        val l = Path(); l.moveTo(x + s * 0.28f, y + s * 0.28f); l.lineTo(x + s * 0.39f, y + s * 0.28f); l.lineTo(x + s * 0.39f, y + s * 0.61f)
        l.lineTo(x + s * 0.72f, y + s * 0.61f); l.lineTo(x + s * 0.72f, y + s * 0.72f); l.lineTo(x + s * 0.28f, y + s * 0.72f); l.close()
        p.color = -1; c.drawPath(l, p)
        val t = Path(); t.moveTo(x + s * 0.47f, y + s * 0.29f); t.lineTo(x + s * 0.73f, y + s * 0.44f); t.lineTo(x + s * 0.47f, y + s * 0.59f); t.close()
        p.color = LAV; c.drawPath(t, p)
    }

    fun icon(c: Canvas, id: Int, cx: Float, cy: Float, r: Float, color: Int) {
        p.style = Paint.Style.STROKE; p.strokeWidth = 2.2f * d; p.color = color; p.strokeCap = Paint.Cap.ROUND
        when (id) {
            0 -> { c.drawCircle(cx - r * 0.2f, cy - r * 0.2f, r * 0.6f, p); c.drawLine(cx + r * 0.25f, cy + r * 0.25f, cx + r * 0.9f, cy + r * 0.9f, p) }
            1 -> for (i in 0..1) for (j in 0..1) { val x0 = cx - r + i * r * 1.15f; val y0 = cy - r + j * r * 1.15f; c.drawRoundRect(RectF(x0, y0, x0 + r * 0.85f, y0 + r * 0.85f), 2 * d, 2 * d, p) }
            2 -> for (i in -1..1) c.drawRoundRect(RectF(cx - r, cy + i * r * 0.75f - r * 0.25f, cx + r, cy + i * r * 0.75f + r * 0.25f), 2 * d, 2 * d, p)
            3 -> { c.drawRoundRect(RectF(cx - r, cy - r * 0.75f, cx + r, cy + r * 0.75f), 3 * d, 3 * d, p)
                c.drawLine(cx - r * 0.2f, cy - r * 0.3f, cx - r * 0.2f, cy + r * 0.3f, p); c.drawLine(cx - r * 0.2f, cy - r * 0.3f, cx + r * 0.3f, cy, p); c.drawLine(cx - r * 0.2f, cy + r * 0.3f, cx + r * 0.3f, cy, p) }
            4 -> { c.drawOval(RectF(cx - r, cy - r * 0.55f, cx + r, cy + r * 0.55f), p); c.drawCircle(cx, cy, r * 0.22f, p) }
            5 -> { c.drawCircle(cx, cy, r, p); c.drawLine(cx, cy, cx, cy - r * 0.6f, p); c.drawLine(cx, cy, cx + r * 0.45f, cy + r * 0.2f, p) }
            6 -> for (k in 0..1) { val x0 = cx + r * 0.1f + k * r * 0.8f; c.drawLine(x0 + r * 0.3f, cy - r * 0.6f, x0 - r * 0.3f, cy, p); c.drawLine(x0 - r * 0.3f, cy, x0 + r * 0.3f, cy + r * 0.6f, p) }
            7 -> { c.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -45f, 270f, false, p)   // circle open at the top, arrow head on the end of the arc
                val q = 0.7071f; val ex = cx - r * q; val ey = cy - r * q
                val tx = ex + r * 0.4f * q; val ty = ey - r * 0.4f * q
                c.drawLine(tx, ty, ex - r * 0.45f * q, ey - r * 0.45f * q, p); c.drawLine(tx, ty, ex + r * 0.45f * q, ey + r * 0.45f * q, p) }
            9 -> { c.drawRoundRect(RectF(cx - r * 0.55f, cy - r, cx + r * 0.55f, cy + r), 3 * d, 3 * d, p); c.drawLine(cx - r * 0.18f, cy + r * 0.68f, cx + r * 0.18f, cy + r * 0.68f, p) }
            8 -> { c.drawRoundRect(RectF(cx - r * 0.85f, cy - r * 0.1f, cx + r * 0.85f, cy + r * 0.9f), 2 * d, 2 * d, p)   // gift box = seasonal decorations
                c.drawRect(cx - r, cy - r * 0.5f, cx + r, cy - r * 0.1f, p); c.drawLine(cx, cy - r * 0.5f, cx, cy + r * 0.9f, p)
                c.drawOval(RectF(cx - r * 0.6f, cy - r * 0.95f, cx, cy - r * 0.5f), p); c.drawOval(RectF(cx, cy - r * 0.95f, cx + r * 0.6f, cy - r * 0.5f), p) }
            10 -> { c.drawCircle(cx, cy, r, p)   // palette: circle with three paint dots
                p.style = Paint.Style.FILL
                for (k in 0..2) { val an = -Math.PI / 2 + k * 2 * Math.PI / 3; c.drawCircle(cx + (r * 0.5 * Math.cos(an)).toFloat(), cy + (r * 0.5 * Math.sin(an)).toFloat(), r * 0.17f, p) } }
            else -> { c.drawCircle(cx, cy, r * 0.5f, p); for (k in 0 until 8) { val an = k * Math.PI / 4; c.drawLine(cx + (r * 0.75f * Math.cos(an)).toFloat(), cy + (r * 0.75f * Math.sin(an)).toFloat(), cx + (r * Math.cos(an)).toFloat(), cy + (r * Math.sin(an)).toFloat(), p) } }
        }
        p.style = Paint.Style.FILL; p.strokeCap = Paint.Cap.BUTT
    }

    // main menu (OK): dark gradient on the left, lavender highlight
    fun drawMenu(c: Canvas, w: Float, h: Float) {
        if (menuShader == null || menuShaderW != width) {
            menuShader = LinearGradient(0f, 0f, w * 0.62f, 0f, intArrayOf(withAlpha(T.tint, 0xF6), withAlpha(T.tint, 0xEE), withAlpha(T.tint, 0x00)), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP); menuShaderW = width
        }
        p.color = -1; p.shader = menuShader; c.drawRect(0f, 0f, w * 0.62f, h, p); p.shader = null
        val ms = h * 0.085f; drawMark(c, w * 0.05f, h * 0.045f, ms)
        val lx = w * 0.05f + ms + 14 * d; tp.textSize = 26 * d * fs; tp.typeface = Typeface.DEFAULT_BOLD
        val lite = tp.measureText("Lite"); txt(c, "Lite", lx, h * 0.045f + ms * 0.68f, w * 0.2f, 26f, -1, true); txt(c, "TV", lx + lite, h * 0.045f + ms * 0.68f, w * 0.2f, 26f, LAV, true)
        val items = a.menuItems(); val step = h * 0.0835f
        items.forEachIndexed { i, it ->
            val cy = if (i == items.lastIndex) h * 0.91f else h * 0.205f + i * step; val on = i == menuSel
            if (on) { p.color = LAV; c.drawRoundRect(RectF(w * 0.05f, cy - h * 0.037f, w * 0.30f, cy + h * 0.037f), 12 * d, 12 * d, p) }
            icon(c, it.first, w * 0.077f, cy, 11 * d, if (on) LAVT else -1)
            stxt(c, it.second, w * 0.105f, cy + 7 * d, w * 0.19f, 19f, if (on) LAVT else -1, false, on)
        }
    }

    // settings panel (mode 8): one row per setting, LEFT / RIGHT or OK changes it
    // sections have a thin divider, every row = title + value on one line and a short grey explanation underneath; the list scrolls when it is longer than the screen
    fun drawSettings(c: Canvas, w: Float, h: Float) {
        val pw = w * 0.46f; val hh = h * 0.065f; val sh = h * 0.045f; val ih = h * 0.068f; val bot = h * 0.90f
        fill(c, 0f, 0f, pw, h, ROW); fill(c, pw - 2, 0f, 2f, h, SEL)
        txt(c, "SETTINGS", 20 * d, hh * 0.68f, pw - 30 * d, 13f, 0xFF8A8A92.toInt(), true)
        val items = a.settingsItems()
        val ys = FloatArray(items.size); var yy = 0f   // top of every row inside the list (a section heading sits just above its first row)
        for (i in items.indices) { if (a.settingSection(i) != null) yy += sh; ys[i] = yy; yy += ih }
        val scroll = maxOf(0f, ys[setSel.coerceIn(0, items.lastIndex)] + ih - (bot - hh))
        c.save(); c.clipRect(0f, hh, pw - 2, bot); c.translate(0f, hh - scroll)
        items.forEachIndexed { i, row ->
            val y = ys[i]
            a.settingSection(i)?.let { sec ->
                txt(c, sec, 20 * d, y - sh + sh * 0.70f, pw - 40 * d, 12f, LAV, true)
                fill(c, 20 * d, y - sh + sh * 0.86f, pw - 40 * d, 1f, 0x33FFFFFF)
            }
            val on = i == setSel; val en = a.settingEnabled(i)   // rows that do not apply right now are dimmed
            if (on) fill(c, 0f, y, pw - 2, ih - 2, PUR)
            val tc = if (en) -1 else 0x66FFFFFF
            txt(c, row.first, 20 * d, y + ih * 0.42f, pw * 0.55f, 17f, tc, on)
            txt(c, a.settingDesc(i), 20 * d, y + ih * 0.80f, pw - 40 * d, 12f, if (!en) 0x55FFFFFF else if (on) 0xCCFFFFFF.toInt() else GRAY)
            if (row.second.isNotEmpty()) {
                tp.textSize = 16 * d * fs; tp.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT; tp.color = if (!en) 0x66FFFFFF else if (on) -1 else GRAY; tp.textAlign = Paint.Align.RIGHT
                val v = if (on && en && a.settingArrows(i)) "<  ${row.second}  >" else if (on && en && (i == 0 || i == 13)) "${row.second}  >" else row.second
                c.drawText(TextUtils.ellipsize(v, tp, pw * 0.40f, TextUtils.TruncateAt.END).toString(), pw - 20 * d, y + ih * 0.42f, tp)
            }
        }
        c.restore()
        // the hint only lists the buttons that do something different on the selected row (OK does the same as RIGHT on a < > row, so it is not mentioned there)
        val hint = when {
            !a.settingEnabled(setSel) -> "BACK  close"
            setSel == 0 -> "OK  open themes     BACK  close"
            setSel == 8 -> "OK  update now     BACK  close"
            setSel == 11 || setSel == 13 -> "OK  open     BACK  close"
            setSel == 12 -> when (a.updState) { 3 -> "OK  download     BACK  close"; 5 -> "OK  install     BACK  close"; 1, 4 -> "BACK  close"; else -> "OK  check for update     BACK  close" }
            else -> "LEFT / RIGHT  change     BACK  close"
        }
        txt(c, hint, 20 * d, h - 38 * d, pw - 40 * d, 13f, GRAY)
        txt(c, "LiteTV ${a.verLabel}", 20 * d, h - 14 * d, pw - 40 * d, 9f, 0xFF6A6A72.toInt())
    }

    // error log (mode 10): the last problems since the app started, newest first; UP / DOWN scroll, OK clears the log
    fun drawErrLog(c: Canvas, w: Float, h: Float) {
        val pw = w * 0.62f; val rh = h / 13f; val rows = 10; val items = a.errLog.toList()
        fill(c, 0f, 0f, pw, h, ROW); fill(c, pw - 2, 0f, 2f, h, SEL)
        txt(c, "ERROR LOG", 20 * d, rh * 0.62f, pw - 30 * d, 13f, 0xFF8A8A92.toInt(), true)
        if (items.isEmpty()) txt(c, "No problems since the app started", 20 * d, h / 2, pw - 40 * d, 18f, GRAY)
        val si = logSel.coerceIn(0, maxOf(0, items.lastIndex))
        val top = maxOf(0, minOf(si - 3, items.size - rows))
        for (k in 0 until rows) {
            val i = top + k; val e = items.getOrNull(i) ?: break; val y = rh * (k + 1)
            if (i == si) fill(c, 0f, y, pw - 2, rh - 2, PUR)
            stxt(c, e, 20 * d, y + rh * 0.62f, pw - 40 * d, 16f, -1, false, i == si)
        }
        txt(c, if (items.isEmpty()) "BACK  close" else "UP / DOWN  scroll     OK  clear the log     BACK  close", 20 * d, h - 20 * d, pw - 40 * d, 13f, GRAY)
    }

    // favorite group order (mode 9): UP / DOWN choose a group, OK grabs it, UP / DOWN then moves it, OK drops it; everything stays inside one panel
    fun drawFavGroups(c: Canvas, w: Float, h: Float) {
        val names = a.favGroupNames().map { it.second }; val pw = w * 0.34f; val rh = h / 13f; val rows = 8
        fill(c, 0f, 0f, pw, h, ROW); fill(c, pw - 2, 0f, 2f, h, SEL)
        txt(c, "FAVORITE GROUPS", 20 * d, rh * 0.62f, pw - 30 * d, 13f, 0xFF8A8A92.toInt(), true)
        if (names.isEmpty()) { txt(c, "No favorites yet", 20 * d, h / 2, pw - 40 * d, 18f, GRAY); return }
        val si = grpSel.coerceIn(0, names.lastIndex)
        val top = maxOf(0, minOf(si - 3, names.size - rows))
        for (k in 0 until rows) {
            val i = top + k; val lab = names.getOrNull(i) ?: break; val y = rh * (k + 1)
            if (i == si) fill(c, 0f, y, pw - 2, rh - 2, PUR)
            val g = grpGrab && i == si
            stxt(c, (if (g) "\u25CF " else "   ") + lab, 20 * d, y + rh * 0.62f, pw - 34 * d, 18f, -1, g, i == si)
        }
        var y = rh * (rows + 1) + 16 * d; val tw = (pw - 40 * d).toInt()
        fill(c, 20 * d, y - 8 * d, pw - 40 * d, 1f, 0x33FFFFFF)
        fun para(s: String, sp: Float, color: Int, bold: Boolean) {
            tp.textSize = sp * d * fs; tp.color = color; tp.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT; tp.textAlign = Paint.Align.LEFT
            val sl = StaticLayout(s, tp, tw, Layout.Alignment.ALIGN_NORMAL, 1.1f, 0f, false)
            c.save(); c.clipRect(0f, y, pw - 2, h); c.translate(20 * d, y); sl.draw(c); c.restore(); y += sl.height + 6 * d
        }
        para(if (grpGrab) "UP / DOWN  move the group" else "UP / DOWN  choose a group", 15f, -1, true)
        para(if (grpGrab) "OK or BACK  drop it here" else "OK  grab it to move", 15f, -1, true)
        if (!grpGrab) para("BACK  close", 15f, GRAY, false)
        para(if (REVERSED) "Top of this list = bottom of the channel list" else "Top of this list = top of the channel list", 13f, GRAY, false)
        para("\"Other\" always stays last", 13f, GRAY, false)
    }

    // theme list (mode 6) or custom colour editor (mode 7), each with a live preview of the colours
    fun drawThemes(c: Canvas, w: Float, h: Float) {
        val py = h * 0.2f; val pvh = h * 0.62f
        if (mode == 7) {
            drawCustom(c, w * 0.5f, h)
            drawPreview(c, w * 0.54f, py, w * 0.42f, pvh)
        } else {
            val pw = w * 0.22f; val px = pw + w * 0.04f
            drawPanel(c, 0f, pw, h, "Themes", a.themeNames(), themeSel, a.themeIdx, true)
            drawPreview(c, px, py, w * 0.5f, pvh)
            // the hint lives inside the themes panel (no text outside a panel)
            txt(c, "OK  use this theme", 20 * d, h - 38 * d, pw - 40 * d, 13f, GRAY)
            txt(c, "BACK  cancel", 20 * d, h - 14 * d, pw - 40 * d, 13f, GRAY)
        }
    }

    // custom theme editor: one strip of swatches per colour; LEFT / RIGHT picks, UP / DOWN changes row
    fun drawCustom(c: Canvas, pw: Float, h: Float) {
        fill(c, 0f, 0f, pw, h, ROW); fill(c, pw - 2, 0f, 2f, h, PAST)
        txt(c, "CUSTOM THEME", 20 * d, h * 0.07f, pw - 40 * d, 13f, 0xFF8A8A92.toInt(), true)
        val labels = listOf("Accent  (selectors, bars)", "Panels  (all backgrounds)", "Highlight  (menu selection)")
        val idx = intArrayOf(a.cA, a.cP, a.cH)
        val x0 = 20 * d; val sw = (pw - 40 * d) / SW; val sh = 34 * d
        for (r in 0..2) {
            val y = h * (0.16f + r * 0.2f); val on = r == cRow
            txt(c, labels[r], x0, y, pw - 40 * d, 17f, if (on) -1 else GRAY, on)
            val sy = y + 18 * d
            fill(c, x0 - 6 * d, sy - 6 * d, pw - 28 * d, sh + 12 * d, 0xFF55555E.toInt())
            for (i in 0 until SW) fill(c, x0 + i * sw, sy, sw - 2 * d, sh, swatch(r, i))
            p.style = Paint.Style.STROKE; p.strokeWidth = 3 * d; p.color = if (on) -1 else 0x88FFFFFF.toInt()
            c.drawRect(x0 + idx[r] * sw - 1 * d, sy - 3 * d, x0 + idx[r] * sw + sw - 1 * d, sy + sh + 3 * d, p); p.style = Paint.Style.FILL
        }
        txt(c, "LEFT / RIGHT  change colour", x0, h * 0.80f, pw - 40 * d, 15f, GRAY)
        txt(c, "UP / DOWN  switch row", x0, h * 0.84f, pw - 40 * d, 15f, GRAY)
        txt(c, "OK or BACK  done (saved)", x0, h * 0.88f, pw - 40 * d, 15f, GRAY)
    }

    // a small mock-up of the real screens in the colours of the theme being previewed
    fun drawPreview(c: Canvas, x: Float, y: Float, w: Float, h: Float) {
        fill(c, x, y, w, h, 0xFF44444E.toInt())
        val m = 14 * d; val iw = w - 2 * m; val oy = y + 36 * d; val u = (h - 36 * d) / 5.8f
        txt(c, "PREVIEW", x + m, y + 24 * d, iw, 13f, 0xFFD0D0D8.toInt(), true)
        // current show, selected
        val ax = x + m; val aw = iw * 0.75f
        fill(c, ax, oy, aw, u * 0.9f, ROW)
        txt(c, "12: Channel name", ax + 14 * d, oy + u * 0.28f, aw - 28 * d, 13f, GRAY)
        txt(c, "Program title", ax + 14 * d, oy + u * 0.56f, aw - 28 * d, 17f, -1, true)
        fill(c, ax + 14 * d, oy + u * 0.72f, aw - 28 * d, 4 * d, TRACK); fill(c, ax + 14 * d, oy + u * 0.72f, (aw - 28 * d) * 0.6f, 4 * d, BAR)
        border(c, ax, oy, aw, u * 0.9f)
        // upcoming show (lighter grey)
        val by = oy + u * 1.05f
        fill(c, ax, by, aw, u * 0.7f, PAST)
        txt(c, "17:30", ax + 14 * d, by + u * 0.46f, 70 * d, 17f, -1, true); txt(c, "Upcoming show", ax + 84 * d, by + u * 0.46f, aw - 98 * d, 17f, -1, true)
        // options bar tiles
        val ty = oy + u * 1.9f; val tw = iw * 0.36f
        fill(c, ax, ty, tw - 2, u * 0.7f, PUR); txt(c, "Watch", ax + 14 * d, ty + u * 0.46f, tw - 28 * d, 17f, -1, true)
        fill(c, ax + tw, ty, tw - 2, u * 0.7f, TILE); txt(c, "Program info", ax + tw + 14 * d, ty + u * 0.46f, tw - 28 * d, 17f, -1, true)
        // program info box
        val iy = oy + u * 2.85f
        fill(c, ax, iy, iw, u * 1.5f, INFO)
        txt(c, "Program title", ax + 18 * d, iy + u * 0.5f, iw - 36 * d, 20f, -1, true)
        txt(c, "A short sample description of the programme.", ax + 18 * d, iy + u * 0.95f, iw - 36 * d, 15f, 0xFFD5DAE6.toInt())
        // menu highlight
        val hy = oy + u * 4.5f
        p.color = LAV; c.drawRoundRect(RectF(ax, hy, ax + iw * 0.5f, hy + u * 0.8f), u * 0.4f, u * 0.4f, p)
        txt(c, "Menu highlight", ax + 20 * d, hy + u * 0.52f, iw * 0.5f - 40 * d, 18f, LAVT, true)
    }

    // program info page
    fun drawInfo(c: Canvas, w: Float, h: Float) {
        val ch = infoCh ?: return; val pr = infoProg; val now = System.currentTimeMillis(); val x = w * 0.16f
        fill(c, 0f, 0f, w, h, withAlpha(T.tint, 0xB3))
        fill(c, w * 0.885f, h * 0.10f, w * 0.095f, h * 0.065f, PUR)
        txt(c, a.hm.format(now), w * 0.9325f, h * 0.10f + h * 0.045f, w * 0.09f, 26f, -1, true, true)
        drawLogo(c, ch, w * 0.885f, h * 0.17f, w * 0.095f, h * 0.1f)
        txt(c, pr?.title ?: ch.name, x, h * 0.30f, w * 0.68f, 44f, -1, true)
        txt(c, "${ch.num}: ${ch.name}", x, h * 0.37f, w * 0.55f, 22f, -1, true)
        var tx = x
        for (t in listOfNotNull(ch.group.takeIf { it.isNotEmpty() }, if (pr != null && now >= pr.start && now < pr.stop) "Live" else null)) {
            tp.textSize = 16 * d * fs; tp.typeface = Typeface.DEFAULT_BOLD; val tw = tp.measureText(t) + 28 * d
            fill(c, tx, h * 0.40f, tw, 34 * d, withAlpha(T.tint, 0xCC)); txt(c, t, tx + 14 * d, h * 0.40f + 24 * d, tw, 16f, -1, true); tx += tw + 10 * d
        }
        if (pr != null) {
            val ds = a.descOf(ch, pr)
            if (ds.isNotEmpty()) {
                tp.textSize = 19 * d * fs; tp.color = -1; tp.typeface = Typeface.DEFAULT; tp.textAlign = Paint.Align.LEFT
                val sl = fitText(ds, (w * 0.53f).toInt(), h * 0.25f, 1.1f)
                c.save(); c.clipRect(x, h * 0.47f, x + w * 0.54f, h * 0.73f); c.translate(x, h * 0.47f); sl.draw(c); c.restore()
            }
            txt(c, "On air:  ${wdd.format(pr.start)} / ${a.hm.format(pr.start)}", w * 0.72f, h * 0.58f, w * 0.26f, 19f, -1, true)
            txt(c, "Duration:  ${(pr.stop - pr.start) / 60000} min", w * 0.72f, h * 0.63f, w * 0.26f, 19f, -1, true)
        }
        val cu = pr != null && pr.stop <= now && a.canCatchup(ch, pr); val bw0 = if (cu) w * 0.25f else w * 0.18f
        val py = h * 0.74f; p.color = LAV; c.drawRoundRect(RectF(x, py, x + bw0, py + h * 0.07f), h * 0.035f, h * 0.035f, p)
        val tri = Path(); tri.moveTo(x + w * 0.025f, py + h * 0.02f); tri.lineTo(x + w * 0.025f, py + h * 0.05f); tri.lineTo(x + w * 0.042f, py + h * 0.035f); tri.close()
        p.color = LAVT; c.drawPath(tri, p); txt(c, if (cu) "Watch catch-up" else "Watch", x + w * 0.055f, py + h * 0.047f, bw0 - w * 0.07f, 20f, LAVT, true)
        val bx = w * 0.12f; val bw = w * 0.76f; val by = h * 0.88f
        p.color = 0xFFD7E0EA.toInt(); c.drawRoundRect(RectF(bx, by, bx + bw, by + 12 * d), 6 * d, 6 * d, p)
        if (pr != null) { val f = ((now - pr.start).toFloat() / maxOf(1L, pr.stop - pr.start)).coerceIn(0f, 1f); p.color = PUR; c.drawRoundRect(RectF(bx, by, bx + bw * f, by + 12 * d), 6 * d, 6 * d, p) }
    }

    fun drawPanel(c: Canvas, x: Float, pw: Float, h: Float, title: String, items: List<String>, selI: Int, activeI: Int, focused: Boolean) {
        val rh = h / 11f; val rows = 10
        fill(c, x, 0f, pw, h, ROW); fill(c, x + pw - 2, 0f, 2f, h, SEL)
        txt(c, title.uppercase(), x + 20 * d, rh * 0.62f, pw - 30 * d, 13f, 0xFF8A8A92.toInt(), true)
        val top = maxOf(0, minOf(selI - 3, items.size - rows))
        for (k in 0 until rows) {
            val i = top + k; val lab = items.getOrNull(i) ?: break; val y = rh * (k + 1)
            if (i == selI) fill(c, x, y, pw - 2, rh - 2, if (focused) PUR else SEL)
            stxt(c, (if (i == activeI) "● " else "   ") + lab, x + 20 * d, y + rh * 0.62f, pw - 34 * d, 18f, -1, i == activeI, i == selI && focused)
        }
    }

    override fun onKeyDown(k: Int, e: KeyEvent): Boolean {
        val up = k == KeyEvent.KEYCODE_DPAD_UP; val dn = k == KeyEvent.KEYCODE_DPAD_DOWN
        val lf = k == KeyEvent.KEYCODE_DPAD_LEFT; val rt = k == KeyEvent.KEYCODE_DPAD_RIGHT
        val ok = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
        if (k == KeyEvent.KEYCODE_BACK) {
            when {
                side -> side = false
                mode == 4 -> { if (subOpen) subOpen = false else mode = backMode }
                mode == 5 -> mode = backMode
                mode == 6 -> { a.setTheme(a.themeIdx, false); mode = 8 }   // back to settings, preview dropped
                mode == 8 -> { a.settingsDone(); mode = 3 }
                mode == 9 -> { if (grpGrab) grpGrab = false else mode = 8 }   // drop the group, then back to settings
                mode == 10 -> mode = 8                                      // back to settings
                mode == 7 -> mode = 6                                       // custom colours are already saved
                else -> close()
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
        } else if (mode == 3) {
            val n = a.menuItems().size
            when { up -> menuSel = (menuSel + n - 1) % n; dn -> menuSel = (menuSel + 1) % n; ok -> a.menuAct(menuSel) }
        } else if (mode == 4) {
            val n = a.subOpts(subI).first.size
            when {
                up -> if (subOpen) oj = (oj + dir).coerceIn(0, maxOf(0, n - 1))
                dn -> if (subOpen) oj = (oj - dir).coerceIn(0, maxOf(0, n - 1))
                lf -> if (!subOpen) subI = maxOf(0, subI - 1) else if (subI == 3) oj = maxOf(0, oj - a.AVD_JUMP)
                rt -> if (!subOpen) subI = minOf(3, subI + 1) else if (subI == 3) oj = minOf(n - 1, oj + a.AVD_JUMP)
                ok -> if (subOpen) { a.subApply(subI, oj); subOpen = false } else {
                    val o = a.subOpts(subI)
                    if (o.first.size > 1) { oj = o.second.coerceAtLeast(0); subOpen = true } else a.flash("Only one option available", 1500)
                    if (subOpen && subI == 3) a.flash("Up / Down: 50 ms    Left / Right: 1 s", 3000)
                }
            }
        } else if (mode == 5) {
            if (ok) { val c0 = infoCh; val p0 = infoProg; close(); if (c0 != null) { if (p0 != null && p0.stop <= System.currentTimeMillis() && a.canCatchup(c0, p0)) a.playCatchup(c0, p0) else a.play(c0) } }
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
        } else if (mode == 10) {
            val n = a.errLog.size
            when { up -> logSel = maxOf(0, logSel - 1); dn -> logSel = minOf(maxOf(0, n - 1), logSel + 1); ok -> { a.errLog.clear(); logSel = 0 } }
        } else if (mode == 6) {
            val n = a.themeNames().size   // the last entry is "Custom"
            when {
                up -> { themeSel = (themeSel + n - 1) % n; a.setTheme(themeSel, false) }   // moving through the list previews each theme live
                dn -> { themeSel = (themeSel + 1) % n; a.setTheme(themeSel, false) }
                ok -> if (themeSel == n - 1) { a.setTheme(themeSel, true); cRow = 0; mode = 7 } else a.setTheme(themeSel, true)
            }
        } else if (mode == 7) {
            when {
                up -> cRow = (cRow + 2) % 3
                dn -> cRow = (cRow + 1) % 3
                lf || rt -> {
                    val v = intArrayOf(a.cA, a.cP, a.cH)
                    v[cRow] = (v[cRow] + (if (rt) 1 else -1) + SW) % SW
                    a.setCustom(v[0], v[1], v[2])
                }
                ok -> mode = 6
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
                        else -> { progI = (progI + (if (up) 1 else -1)).coerceIn(0, maxOf(0, dp.size - 1)); oi = oi.coerceIn(0, maxOf(0, a.nOpts(true, ch, dp.getOrNull(progI)) - 1)) }   // UP = later show (drawn above), DOWN = earlier show
                    }
                }
                lf -> if (col == 2 && oi > 0) oi-- else if (col > 0) col-- else openSide()
                rt -> if (col < 2) col++ else oi = minOf(oi + 1, a.nOpts(true, ch, dp.getOrNull(progI)) - 1)
                ok -> if (ch != null) a.chOption(ch, oi, if (col == 2) dp.getOrNull(progI) else null)
            }
        }
        touch(); invalidate(); return true
    }
}
