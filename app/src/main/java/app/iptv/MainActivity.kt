@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package app.iptv

import android.app.Activity
import android.app.AlertDialog
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

const val PORT = 8765
const val UI_ALPHA = 0.98f   // 1.0 = solid; lower = more see-through
const val REVERSED = true   // Telia style: higher channel numbers above, UP = higher. Set false for normal order.
const val ROW_SCALE = 0.95f // 1.0 = original layout; lower = rows slightly shorter, so the options bar, info box and chips sit higher (everything stays aligned)

// ---- themes ----
// accent = selectors / bars, panel = every panel, past = finished shows, tint = menu gradient + info box + info page, lav / lavT = menu highlight and its text
data class Theme(val name: String, val accent: Int, val panel: Int, val past: Int, val tint: Int, val lav: Int, val lavT: Int)

fun hsv(h: Float, s: Float, v: Float): Int = android.graphics.Color.HSVToColor(floatArrayOf(h, s, v))
fun withAlpha(c: Int, a: Int): Int = (a shl 24) or (c and 0xFFFFFF)
fun blend(a: Int, b: Int, f: Float): Int {
    fun ch(s: Int) = (((a shr s) and 255) * (1 - f) + ((b shr s) and 255) * f).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

// tvg-id matching: "YleTV1.fi@SD" -> "yletv1fi" (drops everything from @, ignores case, dots, spaces, dashes)
fun normId(s: String): String = s.substringBefore('@').lowercase().filter { it.isLetterOrDigit() }

// ---- seasonal decorations: picked from the date, moving holidays (Easter, Thanksgiving, Advent) are calculated ----
const val SE_PARTY = 1; const val SE_HEART = 2; const val SE_FLAG_EE = 3; const val SE_BUN = 4; const val SE_WILLOW = 5; const val SE_EGG = 6
const val SE_VAPPU = 7; const val SE_WREATH = 8; const val SE_FIREWORK = 9; const val SE_CORNFLOWER = 10; const val SE_LEAF = 11; const val SE_CAKE = 12
const val SE_PUMPKIN = 13; const val SE_BAT = 14; const val SE_ACORN = 15; const val SE_TURKEY = 16; const val SE_FLAG_FI = 17
const val SE_HAT = 18; const val SE_HAT_GIFT = 19
const val SE_CANDLES = 21   // 21..24 = Advent wreath with 1..4 candles lit

fun doyOf(y: Int, m: Int, d: Int): Int = java.util.GregorianCalendar(y, m - 1, d).get(java.util.Calendar.DAY_OF_YEAR)

// Easter Sunday (Gregorian), as day of the year
fun easterDoy(y: Int): Int {
    val a = y % 19; val b = y / 100; val c = y % 100; val d = b / 4; val e = b % 4
    val f = (b + 8) / 25; val g = (b - f + 1) / 3; val h = (19 * a + b - d - g + 15) % 30
    val i = c / 4; val k = c % 4; val l = (32 + 2 * e + 2 * i - h - k) % 7
    val m = (a + 11 * h + 22 * l) / 451
    val month = (h + l - 7 * m + 114) / 31; val day = (h + l - 7 * m + 114) % 31 + 1
    return doyOf(y, month, day)
}

// US Thanksgiving = 4th Thursday of November
fun thanksDoy(y: Int): Int {
    val first = java.util.GregorianCalendar(y, 10, 1).get(java.util.Calendar.DAY_OF_WEEK)   // SUNDAY = 1, THURSDAY = 5
    return doyOf(y, 11, 1 + (5 - first + 7) % 7 + 21)
}

// 4th Advent = the last Sunday on or before Dec 24 (it is Dec 24 itself only when Dec 24 is a Sunday)
fun advent4Doy(y: Int): Int {
    val w = java.util.GregorianCalendar(y, 11, 24).get(java.util.Calendar.DAY_OF_WEEK)
    return doyOf(y, 12, 24) - (w - 1)
}

fun seasonId(t: Long): Int {
    val c = java.util.Calendar.getInstance(); c.timeInMillis = t
    val y = c.get(java.util.Calendar.YEAR); val m = c.get(java.util.Calendar.MONTH) + 1
    val d = c.get(java.util.Calendar.DAY_OF_MONTH); val n = c.get(java.util.Calendar.DAY_OF_YEAR)
    // special days first: they replace the month's icon for that one day only
    if ((m == 1 && d == 1) || (m == 12 && d == 31)) return SE_PARTY
    if (m == 2 && d == 14) return SE_HEART
    if (m == 2 && d == 24) return SE_FLAG_EE        // Estonian Independence Day
    if (m == 5 && d == 1) return SE_VAPPU
    if (m == 6 && (d == 23 || d == 24)) return SE_WREATH
    if (m == 7 && d == 4) return SE_FIREWORK
    if (m == 8 && d == 20) return SE_CORNFLOWER     // Estonian Day of Restoration of Independence
    if (m == 10 && d == 18) return SE_CAKE          // birthday
    if (m == 10 && d == 31) return SE_BAT
    if (m == 12 && d == 6) return SE_FLAG_FI        // Finnish Independence Day
    if (m == 12 && d in 24..26) return SE_HAT_GIFT
    val es = easterDoy(y)
    if (n == es - 47) return SE_BUN                 // Vastlapaev
    if (n == es - 7) return SE_WILLOW               // Palm Sunday
    if (n in es - 2..es + 1) return SE_EGG          // Good Friday - Easter Monday
    val tg = thanksDoy(y)
    if (n in tg - 3..tg + 1) return SE_TURKEY       // Thanksgiving week, Monday - Friday
    val a4 = advent4Doy(y)
    for (k in 0..3) if (n == a4 - 7 * (3 - k)) return SE_CANDLES + k   // the four Advent Sundays
    // the month's own icon
    if (m == 12) return SE_HAT
    if (m == 11) return SE_ACORN
    if (m == 10) return if (d >= 15) SE_PUMPKIN else SE_LEAF
    if (m == 9) return SE_LEAF
    return 0
}

val THEMES = listOf(
    Theme("LiteTV Purple", 0xFF990AE3.toInt(), 0xFF130C1A.toInt(), 0xFF2F2B3C.toInt(), 0xFF20152D.toInt(), 0xFFD9B8F0.toInt(), 0xFF3A0057.toInt()),
    Theme("Ocean Blue", 0xFF0A84E3.toInt(), 0xFF0A1420.toInt(), 0xFF26323F.toInt(), 0xFF102235.toInt(), 0xFFB8D9F0.toInt(), 0xFF00315A.toInt()),
    Theme("Emerald", 0xFF12A150.toInt(), 0xFF0A1A12.toInt(), 0xFF283A30.toInt(), 0xFF10291D.toInt(), 0xFFB8F0CF.toInt(), 0xFF003A1C.toInt()),
    Theme("Sunset Orange", 0xFFE36A0A.toInt(), 0xFF1C110A.toInt(), 0xFF3C3028.toInt(), 0xFF2D1B10.toInt(), 0xFFF0D2B8.toInt(), 0xFF572400.toInt()),
    Theme("Crimson", 0xFFD91E3C.toInt(), 0xFF1A0A0E.toInt(), 0xFF3C2A30.toInt(), 0xFF2D1018.toInt(), 0xFFF0B8C4.toInt(), 0xFF57001A.toInt()),
    Theme("Graphite", 0xFF6B7280.toInt(), 0xFF121212.toInt(), 0xFF2E2E2E.toInt(), 0xFF1E1E1E.toInt(), 0xFFD0D4E0.toInt(), 0xFF20222A.toInt())
)

// custom theme: the user steps through 18 swatches per colour (16 hues + 2 neutrals); each row has its own saturation / brightness
const val SW = 18
fun swatch(row: Int, i: Int): Int {
    if (i < 16) {
        val h = i * 22.5f
        return when (row) { 0 -> hsv(h, 0.9f, 0.9f); 1 -> hsv(h, 0.55f, 0.16f); else -> hsv(h, 0.3f, 0.95f) }
    }
    return when (row) {
        0 -> if (i == 16) hsv(0f, 0f, 0.9f) else hsv(0f, 0f, 0.5f)
        1 -> if (i == 16) hsv(0f, 0f, 0.05f) else hsv(0f, 0f, 0.2f)
        else -> if (i == 16) hsv(0f, 0f, 1f) else hsv(0f, 0f, 0.75f)
    }
}

fun customTheme(a: Int, p: Int, h: Int): Theme {
    val acc = swatch(0, a); val pan = swatch(1, p); val lav = swatch(2, h)
    val hs = FloatArray(3); android.graphics.Color.colorToHSV(lav, hs)
    if (hs[1] > 0.05f) hs[1] = minOf(1f, hs[1] + 0.5f)
    hs[2] = 0.28f
    return Theme("Custom", acc, pan, blend(pan, -1, 0.13f), blend(pan, -1, 0.07f), lav, android.graphics.Color.HSVToColor(hs))
}

var T: Theme = THEMES[0]   // the theme in use right now

// palette: panel color for everything, grey only for finished shows, accent for selectors (all follow the active theme)
val C_PUR: Int get() = T.accent                       // selectors / accents
val C_ROW: Int get() = T.panel                        // ALL panels: categories, playlists, channel rows, date/channel cells, options bar, current + upcoming shows, bars, dialogs
val C_PAST: Int get() = T.past                        // grey: shows that have already finished (only these)
val C_INFO: Int get() = withAlpha(T.tint, 0xF2)       // program info box: more transparent
val C_SEL: Int get() = T.panel
val C_TILE: Int get() = T.panel
val C_PROG: Int get() = T.panel
val C_CHIP: Int get() = T.panel
val C_BG: Int get() = T.panel
val C_FIELD: Int get() = T.panel

data class Ch(val id: String, val name: String, val group: String, val url: String, val logo: String, val num: Int, val src: String = "")
data class Prog(val start: Long, val stop: Long, val title: String, val desc: String)
data class Src(val id: String, val name: String, val m3u: String)
data class Epg(val id: String, val name: String, val url: String, val all: Boolean, val srcIds: List<String>, val owner: String = "")
data class DBtn(val label: String, val color: Int, val act: (android.app.Dialog) -> Unit)

// settings choices: guide refresh (hours, 0 = only by hand) and guide length (days)
val REFRESH_OPTS = intArrayOf(0, 6, 12, 24, 48)
val DAYS_OPTS = intArrayOf(2, 3, 5, 7, 10, 14)

// the TV guide lives on disk in this small database (not in RAM): one row per programme
class EpgDbHelper(ctx: Context) : SQLiteOpenHelper(ctx, "epg.db", null, 1) {
    override fun onConfigure(db: SQLiteDatabase) { super.onConfigure(db); db.enableWriteAheadLogging() }   // reading never waits for an import
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE p(src TEXT, ch TEXT, s INTEGER, e INTEGER, t TEXT, d TEXT)")
        db.execSQL("CREATE INDEX pi ON p(ch, s)")
    }
    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) { db.execSQL("DROP TABLE IF EXISTS p"); onCreate(db) }
}

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

class Ui(ctx: Context, val a: MainActivity) : View(ctx) {
    val d = ctx.resources.displayMetrics.density
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
    // mode: 0 hidden, 1 channel list, 2 date/channel/program, 3 main menu (OK), 4 channel settings, 5 program info, 6 theme list, 7 custom theme editor, 8 settings
    var mode = 0; var sideLvl = 0; var sideFocus = 1; var catSel = 1; var plSel = 0   // sideLvl: 0 none, 1 categories, 2 playlists + categories
    var side: Boolean
        get() = sideLvl > 0
        set(v) { if (!v) sideLvl = 0 }
    var sel = 0; var oi = 0; var bar = false; var col = 1; var dayI = 0; var progI = 0; var menuSel = 0
    var subI = 0; var subOpen = false; var oj = 0
    var themeSel = 0; var cRow = 0; var setSel = 0
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
        tp.textSize = sp * d; tp.color = color; tp.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        tp.textAlign = if (center) Paint.Align.CENTER else Paint.Align.LEFT
        c.drawText(TextUtils.ellipsize(s, tp, maxOf(1f, maxW), TextUtils.TruncateAt.END).toString(), x, y, tp)
    }
    // like txt, but the selected item scrolls sideways (after 1 s without moving) when the text is too long
    fun stxt(c: Canvas, s: String, x: Float, y: Float, maxW: Float, sp: Float, color: Int, bold: Boolean, sel: Boolean, center: Boolean = false) {
        tp.textSize = sp * d; tp.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
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
        tp.textSize = 17 * d; tp.typeface = Typeface.DEFAULT_BOLD
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
    fun chips(c: Canvas, items: List<String>, x: Float, right: Boolean) {
        tp.textSize = 14 * d; tp.typeface = Typeface.DEFAULT_BOLD; val pad = 12 * d
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
        dayI = 0; progI = defProg(); touch(); invalidate()
    }
    fun show(m: Int) { mode = m; side = false; visibility = View.VISIBLE; a.banner.visibility = View.GONE; touch(); requestFocus() }
    fun open(m: Int) { oi = 0; bar = false; col = 1; show(m); reset(false) }
    fun openGuide(ch: Ch) { open(2); val k = a.shown.indexOf(ch); if (k >= 0) { sel = k; progI = defProg(); invalidate() } }
    fun openMenu() { menuSel = 0; show(3); invalidate() }
    fun openThemes() { themeSel = a.themeIdx; show(6); invalidate() }
    fun openSettings() { setSel = 0; show(8); invalidate() }
    fun openSub() { backMode = if (mode == 2) 2 else 1; subI = 0; subOpen = false; show(4); invalidate() }
    fun openInfo(ch: Ch, pr: Prog?) { backMode = if (mode == 2) 2 else 1; infoCh = ch; infoProg = pr; show(5); invalidate() }
    fun close() {
        if (mode == 6) a.setTheme(a.themeIdx, false)   // leaving the theme list without pressing OK: drop the preview
        a.settingsDone()
        mode = 0; sideLvl = 0; removeCallbacks(idle); visibility = View.GONE; if (a.searching) a.endSearch()
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
            4 -> { c.save(); c.translate(gx, 0f); drawSub(c, w - gx, h); c.restore(); postInvalidateDelayed(700) }
            5 -> drawInfo(c, w, h)
            6, 7 -> drawThemes(c, w, h)
            8 -> drawSettings(c, w, h)
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
        a.shown.getOrNull(sel)?.let { optBar(c, lw, S * rh, w - lw, rh, a.chOptions(it)) }
        val now = System.currentTimeMillis()
        chips(c, listOf(a.hm.format(now), a.dm.format(now), a.sources.getOrNull(a.act)?.name ?: ""), lw + 8 * d, false)
        chips(c, listOf(a.groups.getOrElse(a.grp) { "" }, "${a.shown.size} channels"), w - 8 * d, true)
        a.season().let { if (it != 0 && a.shown.isNotEmpty()) deco(c, it, -rh * 0.14f, S * rh - rh * 0.39f, rh * 0.52f) }
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
                txt(c, if (i == 0) "Today" else if (i == 1) "Tomorrow" else wd.format(day), dw / 2, y + rh * 0.78f, dw, 14f, GRAY, false, true)
            }
            chAt(sel + rel)?.let { cc ->
                cell(c, dw, y, cw, rh, false, false); fill(c, dw, y, cw - 2, rh - 2, LOGOBG); drawLogo(c, cc, dw, y, cw, rh)
                if (on && col == 1) border(c, dw, y, cw - 2, rh - 2)   // after the logo, otherwise the logo background paints over it
            }
            dp.getOrNull(progI - (s - S))?.let { pr ->   // upcoming shows above the selected one, finished shows below it
                cell(c, px, y, pw, rh, false, false, if (pr.start > now) PAST else PROG)   // upcoming shows use the lighter grey; current and finished shows use the dark panel colour; selection is shown by the border only
                if (on && col == 2) border(c, px, y, pw - 2, rh - 2)
                txt(c, a.hm.format(pr.start), px + 14 * d, y + rh * 0.58f, 70 * d, 20f, -1, true)
                stxt(c, pr.title, px + 84 * d, y + rh * 0.52f, pw - 94 * d, 17f, -1, true, on && col == 2)
                if (now >= pr.start && now < pr.stop) progress(c, px + 84 * d, y + rh * 0.74f, pw - 100 * d, pr)
            }
        }
        if (dp.isEmpty()) { cell(c, px, S * rh, pw, rh, true, col == 2); txt(c, "No guide data", px + 14 * d, S * rh + rh * 0.58f, pw - 20 * d, 17f, GRAY) }
        // right side like the Telia guide: the info box sits straight on top of the options bar, both start where the programme column ends
        val bx = px + pw; val bw = w - bx; val pr = dp.getOrNull(progI)
        if (pr != null) {
            val bh = h * 0.27f; val by = S * rh - bh
            fill(c, bx, by, bw, bh, INFO)
            txt(c, pr.title, bx + 24 * d, by + 42 * d, bw - 48 * d, 22f, -1, true)
            val ds = if (ch != null) a.descOf(ch, pr) else pr.desc
            if (ds.isNotEmpty()) {
                tp.textSize = 15 * d; tp.color = 0xFFD5DAE6.toInt(); tp.typeface = Typeface.DEFAULT; tp.textAlign = Paint.Align.LEFT
                val sl = StaticLayout(ds, tp, (bw - 48 * d).toInt(), Layout.Alignment.ALIGN_NORMAL, 1.15f, 0f, false)
                c.save(); c.clipRect(bx, by + 56 * d, bx + bw, by + bh - 10 * d); c.translate(bx + 24 * d, by + 62 * d); sl.draw(c); c.restore()
            }
        }
        if (ch != null && col == 2) optBar(c, bx, S * rh, bw, rh, a.chOptions(ch))   // only once the programme column is selected
        chips(c, listOf(a.hm.format(now), a.dm.format(now), a.sources.getOrNull(a.act)?.name ?: ""), bx, false)
        val sx = when (col) { 0 -> 0f; 1 -> dw; else -> px }   // the column the selector border is on
        a.season().let { if (it != 0 && ch != null) deco(c, it, if (sx > 0f) sx - rh * 0.1f else 0f, S * rh - rh * 0.39f, rh * 0.52f) }
    }

    // "Channel settings": audio / subtitles / aspect tiles; OK on a tile pops its options up above it
    fun drawSub(c: Canvas, w: Float, h: Float) {
        val rh = rowH(); val lw = w * 0.31f; val logoW = lw * 0.36f; val y = S * rh
        val ch = a.cur ?: a.shown.getOrNull(sel) ?: return
        fill(c, 0f, y, logoW, rh - 2, LOGOBG); drawLogo(c, ch, 0f, y, logoW, rh); border(c, 0f, y, logoW, rh)
        val labels = listOf("Audio", "Subtitles", "Aspect ratio"); var x = logoW
        for (i in 0..2) {
            val (opts, cs) = a.subOpts(i)
            tp.textSize = 17 * d; tp.typeface = Typeface.DEFAULT_BOLD
            val tw = (maxOf(opts.maxOf { tp.measureText(it) }, tp.measureText(labels[i])) + 40 * d).coerceIn(w * 0.14f, w * 0.30f)
            val v = if (subOpen && i == subI) opts.getOrElse(oj) { "" } else opts.getOrElse(cs) { "" }
            fill(c, x, y, tw - 2, rh - 2, if (i == subI) PUR else TILE)
            txt(c, v, x + 20 * d, y + rh * 0.50f, tw - 40 * d, 17f, -1, true)
            txt(c, labels[i], x + 20 * d, y + rh * 0.74f, tw - 40 * d, 13f, -1)
            if (subOpen && i == subI) for (j in 1..4) {
                val o = opts.getOrNull(oj + dir * j) ?: break; val oy = y - j * rh * 0.85f
                fill(c, x, oy, tw - 2, rh * 0.85f - 2, TILE); txt(c, o, x + 20 * d, oy + rh * 0.5f, tw - 40 * d, 17f, -1, true)
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
        val lx = w * 0.05f + ms + 14 * d; tp.textSize = 26 * d; tp.typeface = Typeface.DEFAULT_BOLD
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
    fun drawSettings(c: Canvas, w: Float, h: Float) {
        val pw = w * 0.42f; val rh = h / 11f
        fill(c, 0f, 0f, pw, h, ROW); fill(c, pw - 2, 0f, 2f, h, SEL)
        txt(c, "SETTINGS", 20 * d, rh * 0.62f, pw - 30 * d, 13f, 0xFF8A8A92.toInt(), true)
        val items = a.settingsItems()
        items.forEachIndexed { i, row ->
            val y = rh * (i + 1); val on = i == setSel
            if (on) fill(c, 0f, y, pw - 2, rh - 2, PUR)
            txt(c, row.first, 20 * d, y + rh * 0.62f, pw * 0.5f, 18f, -1, on)
            if (row.second.isNotEmpty()) {
                tp.textSize = 17 * d; tp.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT; tp.color = if (on) -1 else GRAY; tp.textAlign = Paint.Align.RIGHT
                val v = if (on && i in 1..3) "<  ${row.second}  >" else row.second
                c.drawText(TextUtils.ellipsize(v, tp, pw * 0.45f, TextUtils.TruncateAt.END).toString(), pw - 20 * d, y + rh * 0.62f, tp)
            }
        }
        txt(c, "LEFT / RIGHT  change     OK  select     BACK  close", 20 * d, rh * (items.size + 1.8f), pw - 40 * d, 14f, GRAY)
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
            txt(c, "OK = use this theme     BACK = cancel", px, py + pvh + 32 * d, w * 0.5f, 15f, GRAY)
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
            tp.textSize = 16 * d; tp.typeface = Typeface.DEFAULT_BOLD; val tw = tp.measureText(t) + 28 * d
            fill(c, tx, h * 0.40f, tw, 34 * d, withAlpha(T.tint, 0xCC)); txt(c, t, tx + 14 * d, h * 0.40f + 24 * d, tw, 16f, -1, true); tx += tw + 10 * d
        }
        if (pr != null) {
            val ds = a.descOf(ch, pr)
            if (ds.isNotEmpty()) {
                tp.textSize = 19 * d; tp.color = -1; tp.typeface = Typeface.DEFAULT; tp.textAlign = Paint.Align.LEFT
                val sl = StaticLayout(ds, tp, (w * 0.55f).toInt(), Layout.Alignment.ALIGN_NORMAL, 1.1f, 0f, false)
                c.save(); c.clipRect(x, h * 0.48f, x + w * 0.56f, h * 0.70f); c.translate(x, h * 0.48f); sl.draw(c); c.restore()
            }
            txt(c, "On air:  ${wdd.format(pr.start)} / ${a.hm.format(pr.start)}", w * 0.62f, h * 0.58f, w * 0.34f, 19f, -1, true)
            txt(c, "Duration:  ${(pr.stop - pr.start) / 60000} min", w * 0.62f, h * 0.63f, w * 0.34f, 19f, -1, true)
        }
        val py = h * 0.74f; p.color = LAV; c.drawRoundRect(RectF(x, py, x + w * 0.18f, py + h * 0.07f), h * 0.035f, h * 0.035f, p)
        val tri = Path(); tri.moveTo(x + w * 0.025f, py + h * 0.02f); tri.lineTo(x + w * 0.025f, py + h * 0.05f); tri.lineTo(x + w * 0.042f, py + h * 0.035f); tri.close()
        p.color = LAVT; c.drawPath(tri, p); txt(c, "Watch", x + w * 0.055f, py + h * 0.047f, w * 0.11f, 20f, LAVT, true)
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
                mode == 7 -> mode = 6                                       // custom colours are already saved
                else -> close()
            }
            touch(); invalidate(); return true
        }
        if (!(up || dn || lf || rt || ok)) return super.onKeyDown(k, e)
        val mx = maxOf(0, a.shown.size - 1); val ch = a.shown.getOrNull(sel)
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
                lf -> if (!subOpen) subI = maxOf(0, subI - 1)
                rt -> if (!subOpen) subI = minOf(2, subI + 1)
                ok -> if (subOpen) { a.subApply(subI, oj); subOpen = false } else { oj = a.subOpts(subI).second.coerceAtLeast(0); subOpen = true }
            }
        } else if (mode == 5) {
            if (ok) { val c0 = infoCh; close(); if (c0 != null) a.play(c0) }
        } else if (mode == 8) {
            val n = a.settingsItems().size
            when { up -> setSel = (setSel + n - 1) % n; dn -> setSel = (setSel + 1) % n; lf -> a.settingAct(setSel, -1); rt -> a.settingAct(setSel, 1); ok -> a.settingAct(setSel, 1) }
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
                rt -> oi = minOf(oi + 1, a.nOpts - 1)
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
                        else -> progI = (progI + (if (up) 1 else -1)).coerceIn(0, maxOf(0, dp.size - 1))   // UP = later show (drawn above), DOWN = earlier show
                    }
                }
                lf -> if (col == 2 && oi > 0) oi-- else if (col > 0) col-- else openSide()
                rt -> if (col < 2) col++ else oi = minOf(oi + 1, a.nOpts - 1)
                ok -> if (ch != null) a.chOption(ch, oi, dp.getOrNull(progI))
            }
        }
        touch(); invalidate(); return true
    }
}

class MainActivity : Activity() {
    lateinit var root: FrameLayout; lateinit var pv: PlayerView; lateinit var osd: TextView; lateinit var ui: Ui; lateinit var banner: Banner
    lateinit var player: ExoPlayer; lateinit var prefs: SharedPreferences
    var channels = listOf<Ch>(); var shown = listOf<Ch>(); var groups = listOf("★ Favorites", "All")
    var sources = listOf<Src>(); var epgs = listOf<Epg>(); var act = 0; var grp = 1
    var prev: Ch? = null; var cur: Ch? = null; var numBuf = ""; var arMode = 0; var showHidden = false; val nOpts = 6
    val hidden = HashSet<String>(); val favs = HashSet<String>(); var searching = false
    var errShown = false
    val arNames = arrayOf("Fit", "Zoom", "Stretch")
    var themeIdx = 0   // saved theme: 0 until THEMES.size - 1 = premade, THEMES.size = Custom
    var cA = 12; var cP = 12; var cH = 12   // custom theme: swatch index of accent / panels / highlight
    val chCache = java.util.concurrent.ConcurrentHashMap<String, List<Ch>>()                              // source id -> channels
    val epgAt = java.util.concurrent.ConcurrentHashMap<String, Long>()                                    // epg url -> age of the downloaded file that is in the database
    val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()
    @Volatile var preloading = false
    fun lk(k: String): Any = locks.getOrPut(k) { Any() }
    val hm = SimpleDateFormat("HH:mm"); val dm = SimpleDateFormat("EEEE, d MMMM")
    // logos: capped by size (8 MB), not by count
    val logos = object : android.util.LruCache<String, Bitmap>(8 * 1024 * 1024) { override fun sizeOf(k: String, b: Bitmap): Int = b.byteCount }

    // ---- guide (EPG) database: only a few channels at a time are ever held in RAM ----
    val epgDb: SQLiteDatabase by lazy { EpgDbHelper(this).writableDatabase }
    var epgHours = 12; var epgDaysN = 7; var daysDirty = false   // settings: refresh every N hours (0 = by hand), keep N days
    @Volatile var scope: List<String> = emptyList()    // which guide files are in view: the active playlist's, or all of them while favorites / search results are shown
    @Volatile var epgVer = 0                           // goes up whenever the guide data or the scope changes
    val progCache = android.util.LruCache<String, List<Prog>>(60)   // channel -> its programmes (read from the database on first use)
    val descCache = android.util.LruCache<String, String>(40)       // descriptions of far-away programmes, read on demand
    @Volatile var epgBusy = false
    @Volatile var endCache = 0L; @Volatile var endVer = -1
    var exiting = false
    val loading: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet<String>())
    val failed: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet<String>())
    val pool: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newFixedThreadPool(2)
    val hideOsd = Runnable { osd.visibility = View.GONE }

    // true while the list mixes channels of several playlists (search results, favorites)
    fun multi() = searching || grp == 0

    // today's seasonal decoration (recomputed hourly); the menu switches it On / Off
    var seasonOn = true; var seasonHour = -1; var seasonCache = 0
    fun season(): Int {
        if (!seasonOn) return 0
        val t = System.currentTimeMillis(); val hr = (t / 3600000L).toInt()
        if (hr != seasonHour) { seasonHour = hr; seasonCache = seasonId(t) }
        return seasonCache
    }

    // ---- themes ----
    fun themeOf(i: Int): Theme = if (i < THEMES.size) THEMES[i] else customTheme(cA, cP, cH)
    fun themeNames(): List<String> = THEMES.map { it.name } + "Custom"

    fun loadTheme() {
        themeIdx = prefs.getInt("theme", 0).coerceIn(0, THEMES.size)
        cA = prefs.getInt("cA", 12).coerceIn(0, SW - 1); cP = prefs.getInt("cP", 12).coerceIn(0, SW - 1); cH = prefs.getInt("cH", 12).coerceIn(0, SW - 1)
        T = themeOf(themeIdx)
    }

    fun refreshTheme() {
        root.setBackgroundColor(C_BG); osd.setBackgroundColor(C_BG)
        ui.menuShader = null; ui.invalidate(); banner.invalidate()
    }

    // save = false: only a preview (undone with setTheme(themeIdx, false)); save = true: remember it
    fun setTheme(i: Int, save: Boolean) {
        T = themeOf(i)
        if (save) { themeIdx = i; prefs.edit().putInt("theme", i).apply() }
        refreshTheme()
    }

    fun setCustom(a: Int, p: Int, h: Int) {
        cA = a; cP = p; cH = h
        prefs.edit().putInt("cA", a).putInt("cP", p).putInt("cH", h).apply()
        T = customTheme(cA, cP, cH)
        refreshTheme()
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = getSharedPreferences("p", 0)
        loadTheme()
        epgHours = prefs.getInt("epgH", 12); epgDaysN = prefs.getInt("epgD", 7).coerceIn(1, 30)
        Thread { try { epgDb.isOpen } catch (e: Exception) {} }.start()   // open the guide database in the background
        seasonOn = prefs.getBoolean("season", true)
        favs.addAll(prefs.getStringSet("favs", emptySet())!!); hidden.addAll(prefs.getStringSet("hidden", emptySet())!!)
        root = FrameLayout(this); root.setBackgroundColor(C_BG)
        pv = PlayerView(this).apply { useController = false; keepScreenOn = true }; root.addView(pv)
        osd = TextView(this).apply { setTextColor(-1); textSize = 20f; setPadding(40, 20, 40, 20); setBackgroundColor(C_BG); visibility = View.GONE }
        root.addView(osd, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        banner = Banner(this, this).apply { visibility = View.GONE }; root.addView(banner, FrameLayout.LayoutParams(-1, -1))
        ui = Ui(this, this).apply { visibility = View.GONE }; root.addView(ui, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        val ds = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true).setUserAgent("Mozilla/5.0").setConnectTimeoutMs(10000)
        player = ExoPlayer.Builder(this, DefaultRenderersFactory(this).setEnableDecoderFallback(true))
            .setMediaSourceFactory(DefaultMediaSourceFactory(ds))
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(4000, 20000, 800, 1500).build()).build()
        pv.player = player
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()   // subtitles off by default
        player.addListener(object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                errShown = true
                flash((cur?.let { "${it.num}: ${it.name}  \u2013  " } ?: "") + errText(e), 8000)
                pv.postDelayed({ if (cur != null) player.prepare() }, 2500)
            }
            override fun onPlaybackStateChanged(st: Int) {
                if (st == Player.STATE_READY && errShown) { errShown = false; osd.removeCallbacks(hideOsd); osd.visibility = View.GONE }
            }
        })
        sources = readSrcs(); epgs = readEpgs()
        if (sources.isEmpty()) settings() else activate(prefs.getInt("act", 0), false, true)
    }

    fun open(u: String) = (URL(u).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15000; readTimeout = 30000; setRequestProperty("User-Agent", "Mozilla/5.0")
    }.inputStream

    fun newId() = java.util.UUID.randomUUID().toString().take(8)

    fun readSrcs(): List<Src> = try {
        val a = JSONArray(prefs.getString("srcs", "[]"))
        (0 until a.length()).map { k ->
            val o = a.getJSONObject(k); val m = o.optString("m"); val n = o.optString("n")
            Src(o.optString("i").ifEmpty { hx(m + n) }, n, m)
        }
    } catch (e: Exception) { emptyList() }

    fun saveSrcs(l: List<Src>) {
        val a = JSONArray(); l.forEach { a.put(JSONObject().put("i", it.id).put("n", it.name).put("m", it.m3u)) }
        prefs.edit().putString("srcs", a.toString()).apply()
    }

    fun readEpgs(): List<Epg> = try {
        val a = JSONArray(prefs.getString("epgs", "[]"))
        (0 until a.length()).map { k ->
            val o = a.getJSONObject(k); val ids = o.optJSONArray("a")
            Epg(o.optString("i"), o.optString("n"), o.optString("u"), o.optBoolean("all"), if (ids == null) emptyList() else (0 until ids.length()).map { j -> ids.getString(j) }, o.optString("o"))
        }
    } catch (e: Exception) { emptyList() }

    fun saveEpgs(l: List<Epg>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("i", it.id).put("n", it.name).put("u", it.url).put("all", it.all).put("a", JSONArray(it.srcIds)).put("o", it.owner)) }
        prefs.edit().putString("epgs", a.toString()).apply()
    }

    // EPGs assigned to this playlist (or flagged "all"); none assigned = the url-tvg found in the M3U header
    fun epgUrls(s: Src): List<String> {
        val m = epgs.filter { it.all || s.id in it.srcIds }.map { it.url.trim() }.filter { it.startsWith("http") }.distinct()
        return if (m.isNotEmpty()) m else prefs.getString("auto_" + hx(s.m3u), "")!!.split(" ").filter { it.startsWith("http") }
    }

    fun hx(u: String) = Integer.toHexString(u.hashCode())
    fun cleanCache() {
        val keep = HashSet<String>()
        for (s in sources) { keep.add("l" + hx(s.m3u)); epgUrls(s).forEach { keep.add("e" + hx(it)) } }
        filesDir.listFiles()?.filter { (it.name.startsWith("l") || it.name.startsWith("e")) && it.name !in keep }?.forEach { it.delete() }
        // guide rows of guide files that no playlist uses any more
        val keys = sources.flatMap { epgUrls(it) }.map { hx(it) }.distinct()
        try { epgDb.execSQL(if (keys.isEmpty()) "DELETE FROM p" else "DELETE FROM p WHERE src NOT IN (" + keys.joinToString(",") { "'$it'" } + ")") } catch (e: Exception) {}
    }

    // downloads every url in parallel to its own cache file; failed ones keep their old cache
    fun fetch(urls: List<String>, pre: String, refresh: Boolean, maxAge: Long): List<File> {
        val files = urls.map { File(filesDir, pre + hx(it)) }
        val fails = java.util.concurrent.atomic.AtomicInteger()
        urls.indices.map { i ->
            Thread {
                val f = files[i]
                if (refresh || !f.exists() || System.currentTimeMillis() - f.lastModified() > maxAge) {
                    try { val t = File(f.path + ".tmp"); open(urls[i]).use { s -> t.outputStream().use { s.copyTo(it) } }; t.renameTo(f) }
                    catch (e: Exception) { fails.incrementAndGet() }
                }
            }.apply { start() }
        }.forEach { it.join() }
        val what = if (pre == "l") "playlist" else "EPG"
        if (fails.get() > 0) runOnUiThread { Toast.makeText(this, "${fails.get()} $what source(s) failed", Toast.LENGTH_LONG).show() }
        return files.filter { it.exists() }
    }

    // channels of one playlist: from memory, else from the cached file / download (also remembers the EPG url in its header)
    fun loadPlaylist(s: Src, refresh: Boolean): List<Ch> = synchronized(lk("l" + s.id)) {
        if (refresh) chCache.remove(s.id)
        chCache[s.id]?.let { return@synchronized it }
        val all = ArrayList<Ch>()
        for (f in fetch(listOf(s.m3u), "l", refresh, Long.MAX_VALUE)) {
            val head = f.bufferedReader().use { it.readLine() } ?: ""
            val auto = Regex("(?:url-tvg|x-tvg-url)=\"([^\"]*)\"").find(head)?.groupValues?.get(1)
                ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.joinToString(" ") ?: ""
            prefs.edit().putString("auto_" + hx(s.m3u), auto).apply()
            all.addAll(f.useLines { parseM3u(it).toList() }.map { c -> c.copy(src = s.id) })
        }
        if (all.isNotEmpty()) chCache[s.id] = all
        all
    }

    // ---- the guide (EPG): downloaded file -> database on disk -> only the channels on screen are read back ----

    fun epgMaxAge(): Long = if (epgHours <= 0) Long.MAX_VALUE else epgHours * 3600_000L

    // the guide files in view: the active playlist's, or every playlist's while favorites / search results are shown
    fun scopeNow(): List<String> =
        if (multi()) sources.toList().flatMap { epgUrls(it) }.distinct().map { hx(it) }
        else (sources.getOrNull(act)?.let { s -> epgUrls(s).map { hx(it) } } ?: emptyList())

    // the guide data or the scope changed: forget what was read, redraw
    fun epgChanged() {
        scope = scopeNow(); epgVer++
        progCache.evictAll(); descCache.evictAll(); ui.postInvalidate()
    }

    fun syncScope() { if (scopeNow() != scope) epgChanged() }

    // programmes of one channel (all days kept), read from the database the first time and then remembered for a while
    fun progsOf(c: Ch): List<Prog> {
        val k = normId(c.id); if (k.isEmpty()) return emptyList()
        progCache.get(k)?.let { return it }
        val sc = scope; if (sc.isEmpty()) return emptyList()
        val v = epgVer; val l = queryProgs(k, sc)
        if (v == epgVer) progCache.put(k, l)
        return l
    }

    // descriptions are only loaded for programmes that are on now or soon; the rest are read when you open them
    fun queryProgs(k: String, sc: List<String>): List<Prog> {
        val now = System.currentTimeMillis(); val hi = now + 12 * 3600_000L; val lo = now - 3600_000L
        val out = ArrayList<Prog>()
        try {
            val sql = "SELECT s, e, t, CASE WHEN s < $hi AND e > $lo THEN d ELSE '' END FROM p WHERE ch = ? AND src IN (" + sc.joinToString(",") { "?" } + ") ORDER BY s"
            epgDb.rawQuery(sql, (listOf(k) + sc).toTypedArray()).use { cur ->
                while (cur.moveToNext()) out.add(Prog(cur.getLong(0), cur.getLong(1), cur.getString(2) ?: "", cur.getString(3) ?: ""))
            }
        } catch (e: Exception) {}
        return out.distinctBy { it.start }
    }

    fun descOf(c: Ch, p: Prog): String {
        if (p.desc.isNotEmpty()) return p.desc
        val k = normId(c.id) + "|" + p.start
        descCache.get(k)?.let { return it }
        val sc = scope; var d = ""
        if (sc.isNotEmpty()) {
            try {
                val sql = "SELECT d FROM p WHERE ch = ? AND s = ${p.start} AND src IN (" + sc.joinToString(",") { "?" } + ") LIMIT 1"
                epgDb.rawQuery(sql, (listOf(normId(c.id)) + sc).toTypedArray()).use { cur -> if (cur.moveToFirst()) d = cur.getString(0) ?: "" }
            } catch (e: Exception) {}
        }
        descCache.put(k, d)
        return d
    }

    // end of the last programme in the database (for the guide files in view): the guide screen only lists days that have data
    fun epgEnd(): Long {
        val v = epgVer
        if (endVer == v) return endCache
        val sc = scope; var m = 0L
        if (sc.isNotEmpty()) {
            try {
                epgDb.rawQuery("SELECT MAX(e) FROM p WHERE src IN (" + sc.joinToString(",") { "?" } + ")", sc.toTypedArray()).use { cur ->
                    if (cur.moveToFirst() && !cur.isNull(0)) m = cur.getLong(0)
                }
            } catch (e: Exception) {}
        }
        endCache = m; endVer = v
        return m
    }

    // ids of every channel of every playlist that uses this guide file: only those programmes are stored
    fun wantedIds(u: String): HashSet<String> {
        val out = HashSet<String>()
        for (s in sources.toList()) if (u in epgUrls(s)) {
            val l = chCache[s.id] ?: try { loadPlaylist(s, false) } catch (e: Exception) { emptyList() }
            for (c in l) out.add(normId(c.id))
        }
        return out
    }

    // reads one downloaded guide file straight into the database (nothing is collected in RAM); skipped when it was already imported
    fun importEpg(u: String, f: File) {
        val key = hx(u); val keep = wantedIds(u)
        val mark = "${f.lastModified()}:$epgDaysN:${keep.size}:${dayStart()}"
        if (prefs.getString("imp_$key", "") == mark) return
        val lo = dayStart(); val hi = lo + epgDaysN * 86400000L
        val fz = SimpleDateFormat("yyyyMMddHHmmss Z", java.util.Locale.US); val fl = SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.US)
        fun pt(s: String?): Long { val d = (s ?: "").trim(); return (if (d.length > 14) fz.parse(d) else fl.parse(d))!!.time }
        var n = 0; var bad = false
        val db = epgDb
        val st = db.compileStatement("INSERT INTO p(src, ch, s, e, t, d) VALUES(?, ?, ?, ?, ?, ?)")
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM p WHERE src = ?", arrayOf<Any?>(key))
            try {
                val bis = f.inputStream().buffered(); bis.mark(2); val b0 = bis.read(); val b1 = bis.read(); bis.reset()
                val ins = if (b0 == 0x1f && b1 == 0x8b) GZIPInputStream(bis, 65536) else bis
                val p = Xml.newPullParser(); p.setInput(ins, null)
                var ch = ""; var s = 0L; var e = 0L; var ti = ""; var de = ""; var ev = p.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG) {
                        when (p.name) {
                            "programme" -> {
                                ti = ""; de = ""
                                val raw = normId(p.getAttributeValue(null, "channel") ?: "")
                                ch = if (raw.isNotEmpty() && (keep.isEmpty() || raw in keep)) raw else ""   // channels that are in none of your playlists are skipped
                                if (ch.isNotEmpty()) {
                                    try { s = pt(p.getAttributeValue(null, "start")); e = pt(p.getAttributeValue(null, "stop")); if (!(e > lo && s < hi)) ch = "" } catch (x: Exception) { ch = "" }
                                }
                            }
                            "title" -> if (ch.isNotEmpty() && ti.isEmpty()) ti = p.nextText()
                            "desc" -> if (ch.isNotEmpty() && de.isEmpty()) de = p.nextText().take(300)
                        }
                    } else if (ev == XmlPullParser.END_TAG && p.name == "programme" && ch.isNotEmpty()) {
                        st.clearBindings(); st.bindString(1, key); st.bindString(2, ch); st.bindLong(3, s); st.bindLong(4, e); st.bindString(5, ti); st.bindString(6, de)
                        st.executeInsert(); n++; ch = ""
                    }
                    ev = p.next()
                }
                ins.close()
            } catch (ex: Exception) { bad = true }
            if (!bad || n > 0) db.setTransactionSuccessful()   // a broken file with nothing readable keeps the old guide
        } finally { db.endTransaction(); st.close() }
        if (!bad) prefs.edit().putString("imp_$key", mark).apply()
    }

    // downloads the guide files when they are older than the refresh setting (or when forced), then imports them
    fun ensureEpg(urls: List<String>, force: Boolean, download: Boolean = true) {
        if (urls.isEmpty()) return
        val dl = urls.filter { force || !epgAt.containsKey(it) }
        if (download && dl.isNotEmpty()) fetch(dl, "e", force, epgMaxAge())
        for (u in urls) synchronized(lk("e" + u)) {
            val f = File(filesDir, "e" + hx(u))
            if (f.exists()) { importEpg(u, f); epgAt[u] = f.lastModified() }
        }
    }

    // loads every other playlist and its guide in the background so switching playlists is instant
    fun preloadOthers(refresh: Boolean) {
        if (preloading) return
        preloading = true
        try {
            Thread.currentThread().priority = Thread.MIN_PRIORITY
            for (o in sources.toList()) {
                if (o.id == sources.getOrNull(act)?.id) continue
                try { loadPlaylist(o, refresh); ensureEpg(epgUrls(o), false) } catch (e: Exception) {}
            }
            // favorites span all playlists: refresh them once everything is loaded
            runOnUiThread { epgChanged(); if (grp == 0 && !searching) selectGroup(0, true) }
        } finally { preloading = false }
    }

    fun refreshEpgOnly() {
        val s = sources.getOrNull(act) ?: return
        ensureEpg(epgUrls(s), false); epgChanged()
        preloadOthers(false)
    }

    // scheduled / manual guide update: re-downloads the guide files that are older than the refresh setting (all of them when forced)
    fun checkEpg(force: Boolean = false) {
        if (epgBusy || sources.isEmpty()) return
        if (!force && epgHours <= 0) return
        val now = System.currentTimeMillis()
        val all = sources.toList().flatMap { epgUrls(it) }.distinct()
        val old = all.filter { force || now - (epgAt[it] ?: File(filesDir, "e" + hx(it)).lastModified()) > epgMaxAge() }
        if (old.isEmpty()) return
        epgBusy = true
        Thread {
            try {
                Thread.currentThread().priority = Thread.MIN_PRIORITY
                ensureEpg(old, true); epgChanged()
                if (force) runOnUiThread { Toast.makeText(this, "Guide updated", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {} finally { epgBusy = false }
        }.start()
    }

    // the hourly check while the app is on screen (and once more each time you come back to it)
    val epgTick: Runnable = object : Runnable { override fun run() { checkEpg(); pv.postDelayed(this, 3600_000L) } }

    // leaving Settings: a changed guide length re-reads the files already on disk (no download)
    fun settingsDone() {
        if (!daysDirty) return
        daysDirty = false
        Thread {
            try { ensureEpg(sources.toList().flatMap { epgUrls(it) }.distinct(), true, false); epgChanged() } catch (e: Exception) {}
        }.start()
    }

    fun activate(i: Int, refresh: Boolean, boot: Boolean = false, keepFav: Boolean = false) {
        if (sources.isEmpty()) return
        act = i.coerceIn(0, sources.lastIndex); prefs.edit().putInt("act", act).apply()
        if (refresh) { epgAt.clear(); chCache.clear() }
        val s = sources[act]
        Thread {
            try {
                cleanCache()
                val all = loadPlaylist(s, refresh); val urls = epgUrls(s)
                val ready = urls.all { epgAt.containsKey(it) }
                val fresh = urls.all { (epgAt[it] ?: 0L) > System.currentTimeMillis() - epgMaxAge() }
                runOnUiThread { setChannels(all, boot, keepFav); syncScope() }   // the guide already in the database shows straight away
                ensureEpg(urls, refresh || (ready && !fresh)); epgChanged()
                preloadOthers(refresh)
            } catch (e: Exception) { runOnUiThread { Toast.makeText(this, "Load failed: ${e.message}", Toast.LENGTH_LONG).show() } }
        }.start()
    }

    fun parseM3u(lines: Sequence<String>): List<Ch> {
        val out = ArrayList<Ch>(); val re = Regex("([\\w-]+)=\"([^\"]*)\"")
        var name = ""; var id = ""; var g = "Other"; var logo = ""; var no = 0; var have = false
        for (l in lines) {
            if (l.startsWith("#EXTINF")) {
                val a = re.findAll(l).associate { it.groupValues[1] to it.groupValues[2] }
                name = l.substringAfterLast(',').trim(); id = a["tvg-id"] ?: ""; if (id.isEmpty()) id = name
                g = a["group-title"]?.ifEmpty { null } ?: "Other"; logo = a["tvg-logo"] ?: ""; no = a["tvg-chno"]?.toIntOrNull() ?: 0; have = true
            } else if (have && l.isNotBlank() && !l.startsWith("#")) { out.add(Ch(id, name, g, l.trim(), logo, if (no > 0) no else out.size + 1)); have = false }
        }
        return out
    }

    fun vis() = if (showHidden) channels else channels.filter { it.url !in hidden }

    // favorited channels from every playlist that is loaded (the active one is always included)
    fun favChannels(): List<Ch> {
        val out = ArrayList<Ch>(); val seen = HashSet<String>(); val activeId = sources.getOrNull(act)?.id
        for (s in sources.toList()) {
            val l = chCache[s.id] ?: (if (s.id == activeId) channels else emptyList())
            for (c in l) if (c.url in favs && (showHidden || c.url !in hidden) && seen.add(c.url)) out.add(c)
        }
        return out
    }

    fun selectGroup(i: Int, keep: Boolean = false) {
        searching = false; grp = i.coerceIn(0, groups.lastIndex); prefs.edit().putString("grp", groups[grp]).apply(); val v = vis()
        shown = when (grp) { 0 -> favChannels(); 1 -> v; else -> v.filter { it.group == groups[grp] } }
        syncScope()
        ui.reset(keep)
    }

    // searches every playlist (they are all kept in memory); the guide of all playlists is merged while the results are shown
    fun search(q: String) {
        Thread {
            val res = ArrayList<Ch>()
            for (src in sources.toList()) {
                val l = chCache[src.id] ?: try { loadPlaylist(src, false) } catch (e: Exception) { emptyList() }
                for (c in l) if (c.name.contains(q, true) && (showHidden || c.url !in hidden)) res.add(c)
            }
            runOnUiThread { searching = true; shown = res; syncScope(); ui.open(1) }
        }.start()
    }
    fun endSearch() {
        searching = false
        selectGroup(grp, true)
    }
    // a channel picked from another playlist's search result / favorites: switch to that playlist first
    fun switchTo(c: Ch) { val i = sources.indexOfFirst { it.id == c.src }; if (i >= 0 && i != act) activate(i, false, false, grp == 0) }

    fun setChannels(c: List<Ch>, boot: Boolean, keepFav: Boolean = false) {
        channels = c
        groups = listOf("★ Favorites", "All") + c.map { it.group }.distinct()
        val saved = if (boot) groups.indexOf(prefs.getString("grp", "All")) else -1   // remembered category (by name: every playlist has its own)
        selectGroup(if (saved >= 0) saved else if (keepFav && grp == 0) 0 else 1)   // switching playlist because a favorite was played: stay in Favorites
        val last = if (boot) c.firstOrNull { it.url == prefs.getString("last", null) } else null
        if (last != null) play(last)
        else if (ui.side) { ui.catSel = grp; ui.plSel = act; ui.invalidate() }   // playlist picked in the side panels: keep them open
        else if (boot || ui.mode != 0) ui.open(if (ui.mode == 2) 2 else 1)
    }

    fun logoOf(c: Ch): Bitmap? {
        if (!c.logo.startsWith("http") || c.logo in failed) return null
        logos.get(c.logo)?.let { return it }
        if (loading.add(c.logo)) pool.execute {
            try {
                val f = File(cacheDir, "lg" + hx(c.logo))
                if (!f.exists()) { val t = File(f.path + ".t"); open(c.logo).use { s -> t.outputStream().use { s.copyTo(it) } }; t.renameTo(f) }
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(f.path, o)
                o.inJustDecodeBounds = false; o.inSampleSize = maxOf(1, o.outWidth / 200)
                val b = BitmapFactory.decodeFile(f.path, o)
                if (b != null) { logos.put(c.logo, b); ui.postInvalidate() } else failed.add(c.logo)
            } catch (e: Exception) { failed.add(c.logo) } finally { loading.remove(c.logo) }
        }
        return null
    }

    // midnight of today
    fun dayStart(): Long = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }.timeInMillis

    // the days the guide screen lists: today, then as many as the guide really has (at most the "Guide length" setting, never fewer than 2)
    fun days(): List<Long> {
        val c = java.util.Calendar.getInstance(); c.timeInMillis = dayStart()
        val end = epgEnd(); val out = ArrayList<Long>()
        for (i in 0 until maxOf(2, epgDaysN)) {
            if (i >= 2 && c.timeInMillis >= end) break
            out.add(c.timeInMillis); c.add(java.util.Calendar.DAY_OF_MONTH, 1)
        }
        return out
    }
    fun dayProgs(ch: Ch, day: Long): List<Prog> = progsOf(ch).filter { it.stop > day && it.start < day + 86400000L }

    // options bar next to a channel / program
    fun chOptions(c: Ch) = listOf("Watch", "Program info", "Channel guide", "Channel settings",
        if (c.url in favs) "Remove from favorites" else "Add to favorites", if (c.url in hidden) "Unhide channel" else "Hide channel")

    fun toggle(set: HashSet<String>, key: String, u: String) { if (!set.remove(u)) set.add(u); prefs.edit().putStringSet(key, HashSet(set)).apply() }

    fun chOption(c: Ch, i: Int, p: Prog? = null) {
        when (i) {
            0 -> { if (c == cur) { ui.close(); switchTo(c) } else play(c) }   // 1st Watch: change channel, UI stays; 2nd Watch: close UI
            1 -> ui.openInfo(c, p ?: now(c))
            2 -> ui.openGuide(c)
            3 -> { if (c != cur) play(c); ui.openSub() }
            4 -> { toggle(favs, "favs", c.url); selectGroup(grp, true) }
            5 -> { toggle(hidden, "hidden", c.url); selectGroup(grp, true) }
        }
        ui.invalidate()
    }

    fun trackName(f: Format, n: Int): String {
        f.label?.let { return it }
        f.language?.let { l -> if (l != "und") { val nm = java.util.Locale(l).displayLanguage; return if (nm.isEmpty()) l else nm } }
        return "Track $n"
    }

    // options + index of the selected one for the channel-settings tiles: 0 audio, 1 subtitles, 2 aspect
    fun subOpts(i: Int): Pair<List<String>, Int> {
        if (i == 2) return Pair(arNames.toList(), arMode)
        val text = i == 1; val type = if (text) C.TRACK_TYPE_TEXT else C.TRACK_TYPE_AUDIO
        val names = ArrayList<String>(); var sel = 0
        val off = text && player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        if (text) names.add("Off")
        for (g in player.currentTracks.groups) if (g.type == type) for (t in 0 until g.length) {
            if (!off && g.isTrackSelected(t)) sel = names.size
            names.add(trackName(g.getTrackFormat(t), names.size + 1))
        }
        if (names.isEmpty()) names.add("Default")
        return Pair(names, sel)
    }

    fun subApply(i: Int, k: Int) {
        if (i == 2) {
            arMode = k.coerceIn(0, 2)
            pv.resizeMode = intArrayOf(AspectRatioFrameLayout.RESIZE_MODE_FIT, AspectRatioFrameLayout.RESIZE_MODE_ZOOM, AspectRatioFrameLayout.RESIZE_MODE_FILL)[arMode]
            return
        }
        val text = i == 1; val type = if (text) C.TRACK_TYPE_TEXT else C.TRACK_TYPE_AUDIO
        val b = player.trackSelectionParameters.buildUpon()
        if (text && k == 0) b.setTrackTypeDisabled(type, true)
        else {
            var n = if (text) 1 else 0
            for (g in player.currentTracks.groups) if (g.type == type) for (t in 0 until g.length) {
                if (n == k) { b.setTrackTypeDisabled(type, false); b.setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, t)) }
                n++
            }
        }
        player.trackSelectionParameters = b.build()
    }

    // ---- dialogs in the app's own style (dark panel, accent colour; the system keyboard still appears for typing) ----
    fun dBg(color: Int, stroke: Int = 0): GradientDrawable {
        val dp = resources.displayMetrics.density
        return GradientDrawable().apply { setColor(color); cornerRadius = 10 * dp; if (stroke != 0) setStroke((3 * dp).toInt(), stroke) }
    }

    fun dEdit(h: String): EditText {
        val dp = resources.displayMetrics.density
        return EditText(this).apply {
            hint = h; setHintTextColor(0xFF8A8A92.toInt()); setTextColor(-1); textSize = 20f; setSingleLine()
            background = dBg(C_FIELD, 0x44FFFFFF); setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
            setOnFocusChangeListener { v, f -> v.background = dBg(C_FIELD, if (f) C_PUR else 0x44FFFFFF) }
        }
    }

    fun showPanel(title: String, views: List<View>, buttons: List<DBtn>, keyboard: Boolean = false, widthFrac: Double = 0.5): android.app.Dialog {
        val dp = resources.displayMetrics.density
        val dlg = android.app.Dialog(this); dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        buttons.forEachIndexed { i, b ->
            val t = TextView(this).apply {
                text = b.label; setTextColor(-1); textSize = 18f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                setPadding((28 * dp).toInt(), (12 * dp).toInt(), (28 * dp).toInt(), (12 * dp).toInt())
                background = dBg(b.color, if (b.color == C_FIELD) 0x44FFFFFF else 0); isFocusable = true; isClickable = true
                setOnFocusChangeListener { v, f -> v.background = dBg(b.color, if (f) 0xFFFFFFFF.toInt() else if (b.color == C_FIELD) 0x44FFFFFF else 0) }
                setOnClickListener { b.act(dlg) }
            }
            row.addView(t, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) leftMargin = (12 * dp).toInt() })
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = dBg(C_ROW)
            setPadding((32 * dp).toInt(), (28 * dp).toInt(), (32 * dp).toInt(), (24 * dp).toInt())
            addView(TextView(this@MainActivity).apply { text = title; setTextColor(-1); textSize = 24f; typeface = Typeface.DEFAULT_BOLD })
            for (v in views) addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (18 * dp).toInt() })
            addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (22 * dp).toInt() })
        }
        dlg.setContentView(box)
        dlg.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout((resources.displayMetrics.widthPixels * widthFrac).toInt(), -2)
            if (keyboard) setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        dlg.show()
        return dlg
    }

    fun searchBox() {
        val input = dEdit("Channel name"); var d: android.app.Dialog? = null
        fun go() { val q = input.text.toString().trim(); d?.dismiss(); if (q.isNotEmpty()) search(q) }
        input.setOnEditorActionListener { _, _, _ -> go(); true }
        d = showPanel("Search all playlists", listOf(input), listOf(
            DBtn("Cancel", C_FIELD) { it.dismiss() },
            DBtn("Search", C_PUR) { go() }), keyboard = true)
    }

    // main menu (OK button); the number is the icon id
    fun menuItems() = listOf(0 to "Search", 9 to "Playlists", 2 to "TV guide", 3 to "On air now", 4 to (if (showHidden) "Hide hidden" else "Show hidden"),
        6 to "Last channel", 11 to "Settings")

    fun menuAct(i: Int) {
        when (i) {
            0 -> { ui.close(); searchBox() }
            1 -> { ui.close(); settings() }
            2 -> ui.open(2)
            3 -> ui.open(1)
            4 -> { showHidden = !showHidden; selectGroup(grp, true) }
            5 -> { ui.close(); prev?.let { play(it) } }
            6 -> ui.openSettings()
        }
    }

    // settings panel rows: name to current value
    fun settingsItems() = listOf(
        "Themes" to themeNames()[themeIdx.coerceIn(0, THEMES.size)],
        "Seasonal decorations" to (if (seasonOn) "On" else "Off"),
        "Guide refresh" to (if (epgHours <= 0) "Manual only" else "Every $epgHours hours"),
        "Guide length" to "$epgDaysN days",
        "Update guide now" to "")

    // d = -1 (LEFT) or +1 (RIGHT / OK)
    fun settingAct(i: Int, d: Int) {
        when (i) {
            0 -> if (d > 0) ui.openThemes()
            1 -> { seasonOn = !seasonOn; prefs.edit().putBoolean("season", seasonOn).apply() }
            2 -> {
                val k = REFRESH_OPTS.indexOf(epgHours).let { if (it < 0) 2 else it }
                epgHours = REFRESH_OPTS[(k + d + REFRESH_OPTS.size) % REFRESH_OPTS.size]; prefs.edit().putInt("epgH", epgHours).apply()
            }
            3 -> {
                val k = DAYS_OPTS.indexOf(epgDaysN).let { if (it < 0) 3 else it }
                epgDaysN = DAYS_OPTS[(k + d + DAYS_OPTS.size) % DAYS_OPTS.size]; prefs.edit().putInt("epgD", epgDaysN).apply(); daysDirty = true
            }
            4 -> if (d > 0) { Toast.makeText(this, "Updating guide...", Toast.LENGTH_SHORT).show(); checkEpg(true) }
        }
    }

    fun now(c: Ch): Prog? { val t = System.currentTimeMillis(); return progsOf(c).firstOrNull { t >= it.start && t < it.stop } }
    fun next(c: Ch): Prog? { val n = now(c) ?: return null; return progsOf(c).firstOrNull { it.start >= n.stop } }

    fun play(c: Ch) {
        if (cur != null && cur != c) prev = cur
        cur = c
        errShown = false; osd.removeCallbacks(hideOsd); osd.visibility = View.GONE
        player.setMediaItem(MediaItem.fromUri(c.url)); player.prepare(); player.playWhenReady = true
        prefs.edit().putString("last", c.url).apply()
        if (ui.mode == 0) banner.show(c)   // no banner while the lists are open
    }

    // human readable reason why a stream does not play
    fun errText(e: PlaybackException): String {
        var t: Throwable? = e
        while (t != null) {
            if (t is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                val c = t.responseCode
                return "Server returned HTTP $c" + when (c) { 401, 403 -> " (access denied)"; 404 -> " (stream not found)"; 410 -> " (stream removed)"; in 500..599 -> " (server error)"; else -> "" }
            }
            if (t is java.net.UnknownHostException) return "Server name not found (DNS)"
            if (t is java.net.SocketTimeoutException) return "Connection timed out"
            if (t is java.net.ConnectException) return "Connection refused by the server"
            if (t is javax.net.ssl.SSLException) return "Secure (HTTPS) connection failed"
            t = t.cause
        }
        return when (e.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "Cannot connect to the server"
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Connection timed out"
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "Stream not found"
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "Server refused the stream"
            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> "Plain http streams are blocked"
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> "Stream fell behind live, resyncing"
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> "Stream data is corrupt or not a valid stream"
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> "Stream format is not supported"
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED -> "Decoder could not start"
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> "This device cannot decode the stream"
            PlaybackException.ERROR_CODE_DECODING_FAILED -> "Decoding failed"
            else -> "Playback error: " + e.errorCodeName.removePrefix("ERROR_CODE_").replace('_', ' ').lowercase()
        }
    }

    fun flash(t: String, ms: Long = 2500) { osd.text = t; osd.visibility = View.VISIBLE; osd.removeCallbacks(hideOsd); osd.postDelayed(hideOsd, ms) }

    fun describe(c: Ch): String {
        val n = now(c); val x = next(c)
        val bar = n?.let { val k = (((System.currentTimeMillis() - it.start) * 10) / maxOf(1L, it.stop - it.start)).toInt().coerceIn(0, 10); "▰".repeat(k) + "▱".repeat(10 - k) }
        return "${c.num}  ${c.name}" +
                (n?.let { "\n${hm.format(it.start)}–${hm.format(it.stop)}  ${it.title}\n$bar" + (descOf(c, it).let { d -> if (d.isNotEmpty()) "\n$d" else "" }) } ?: "\nNo guide data") +
                (x?.let { "\nNext ${hm.format(it.start)}  ${it.title}" } ?: "")
    }

    fun zap(d: Int) {
        val l = if (shown.isNotEmpty()) shown else channels; if (l.isEmpty()) return
        val i = l.indexOf(cur); play(l[((if (i < 0) 0 else i) + d + l.size) % l.size])
    }

    var dlg: android.app.Dialog? = null
    var srv: java.net.ServerSocket? = null

    fun localIp(): String? {
        try { for (n in java.net.NetworkInterface.getNetworkInterfaces()) for (a in n.inetAddresses)
            if (!a.isLoopbackAddress && a is java.net.Inet4Address) return a.hostAddress } catch (e: Exception) {}
        return null
    }

    fun qr(s: String, n: Int = 360): android.graphics.Bitmap {
        val m = com.google.zxing.qrcode.QRCodeWriter().encode(s, com.google.zxing.BarcodeFormat.QR_CODE, n, n)
        val bmp = android.graphics.Bitmap.createBitmap(n, n, android.graphics.Bitmap.Config.RGB_565)
        for (x in 0 until n) for (y in 0 until n) bmp.setPixel(x, y, if (m[x, y]) -16777216 else -1)
        return bmp
    }

    fun esc(s: String) = s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")

    // the phone pages share the TV's look: #20152D panels, #990AE3 accent, purple buttons, lavender accents
    val logoSvg = """<svg class=logo viewBox="0 0 100 100" xmlns="http://www.w3.org/2000/svg"><rect width="100" height="100" rx="22" fill="#990AE3"/><path d="M28 28H39V61H72V72H28Z" fill="#fff"/><path d="M47 29L73 44L47 59Z" fill="#D9B8F0"/></svg>"""
    val iconLink = """<link rel=icon href="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'%3E%3Crect width='100' height='100' rx='22' fill='%23990AE3'/%3E%3Cpath d='M28 28H39V61H72V72H28Z' fill='%23fff'/%3E%3Cpath d='M47 29L73 44L47 59Z' fill='%23D9B8F0'/%3E%3C/svg%3E">"""
    val css = """<style>
body{font-family:sans-serif;background:#20152D;color:#fff;max-width:700px;margin:0 auto;padding:24px 14px}
.hd{display:flex;align-items:center;margin-bottom:10px}
.logo{width:48px;height:48px;margin-right:14px;flex:none}
h2{margin:0;font-size:28px}
h2 span{color:#D9B8F0}
a{color:#D9B8F0;text-decoration:none}
p{line-height:1.4}
.sub{color:#B8BECC}
.msg{background:rgba(153,10,227,.3);color:#fff;padding:12px 14px;border-radius:10px;font-weight:bold}
.card{background:rgba(0,0,0,.28);border-radius:10px;padding:14px;margin:14px 0}
.card.drag{outline:2px solid #990AE3}
.card button.r{margin-top:10px}
.top{display:flex;align-items:center;justify-content:space-between;margin-bottom:6px}
.hdl{font-size:28px;line-height:1;padding:6px 16px 6px 2px;touch-action:none;cursor:grab;user-select:none;-webkit-user-select:none;color:#D9B8F0}
button.a{padding:6px 14px;margin-left:8px;font-size:15px}
input:not([type=checkbox]):not([type=hidden]){width:100%;box-sizing:border-box;font-size:16px;margin:5px 0;padding:12px 14px;background:rgba(255,255,255,.08);color:#fff;border:2px solid transparent;border-radius:10px;outline:none}
input:not([type=checkbox]):not([type=hidden]):focus{border-color:#990AE3}
input::placeholder{color:#8A8A92}
input[type=checkbox]{accent-color:#990AE3;width:18px;height:18px;vertical-align:middle;margin-right:8px}
label{display:block;margin:8px 0}
button{font-size:16px;font-weight:bold;padding:12px 22px;border:0;border-radius:10px;background:rgba(255,255,255,.14);color:#fff}
button.p{background:#990AE3}
.ov{position:fixed;left:0;top:0;right:0;bottom:0;background:rgba(0,0,0,.72);display:flex;align-items:center;justify-content:center;padding:20px;z-index:9}
.btn{display:inline-block;font-size:16px;font-weight:bold;padding:12px 22px;border-radius:10px;background:rgba(255,255,255,.14);color:#fff;margin:6px 0}
.dlg{background:#20152D;border:1px solid rgba(255,255,255,.2);border-radius:10px;padding:24px;max-width:420px;width:100%}
.dlg h3{margin:0 0 10px;font-size:22px}
.dlg .row{display:flex;justify-content:flex-end;margin-top:20px}
.dlg .row button{margin-left:12px}
</style>"""

    // confirmation popup in the app's style (used before a card is removed)
    val askJs = """<script>
function ask(title,text,yes,lab){var o=document.createElement('div');o.className='ov';
o.innerHTML='<div class=dlg><h3></h3><p class=sub></p><div class=row><button type=button>Cancel</button><button type=button class=p>Remove</button></div></div>';
o.querySelector('h3').textContent=title;o.querySelector('p').textContent=text;
var b=o.getElementsByTagName('button');b[1].textContent=lab||'Remove';b[0].onclick=function(){o.remove()};b[1].onclick=function(){o.remove();yes()};
document.body.appendChild(o)}
function del(b){var d=b.closest('.card'),n=d.getElementsByTagName('input')[1].value.trim();
ask('Remove '+(n||'this item')+'?','It is removed when you press Save.',function(){d.remove()})}
</script>"""

    fun page(msg: String?): String {
        val json = JSONArray().apply { sources.forEach { sc -> put(JSONObject().put("i", sc.id).put("n", sc.name).put("m", sc.m3u).put("e", epgs.firstOrNull { e -> e.owner == sc.id }?.url ?: "")) } }.toString().replace("</", "<\\/")
        return """<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'><meta name=color-scheme content=dark><title>LiteTV playlists</title>$iconLink
$css<body><div class=hd>$logoSvg<h2>Lite<span>TV</span> playlists</h2></div><a href=/epg>Shared EPGs &rarr;</a>${if (msg != null) "<p class=msg>$msg</p>" else ""}
<form method=post><div id=rows></div>
<button type=button onclick="add({})">+ Add playlist</button>
<button class=p style='margin-left:12px'>Save</button></form>
<div class=card><b>Backup</b>
<p class=sub>Playlists, EPGs and favorites in one file. Download it here, then restore it on your other TVs. Restoring replaces the lists on that TV.</p>
<p id=bm class=msg style='display:none'></p>
<a class=btn href=/backup download=litetv-backup.json>Download backup</a>
<input type=file id=bf accept='.json,application/json'>
<button type=button class=p onclick="restore()">Restore from file</button></div>
$askJs
<script>
function restore(){var f=document.getElementById('bf').files[0],m=document.getElementById('bm');
if(!f){m.style.display='block';m.textContent='Choose a backup file first.';return}
ask('Restore backup?','This replaces the playlists, EPGs and favorites on this TV with the ones in the file.',function(){
var r=new FileReader();r.onload=function(){fetch('/restore',{method:'POST',body:r.result}).then(function(x){return x.text()}).then(function(t){if(t=='ok')location.href='/?restored=1';else{m.style.display='block';m.textContent=t}}).catch(function(){m.style.display='block';m.textContent='Upload failed'})};r.readAsText(f)},'Restore')}
function mv(b,dir){var d=b.closest('.card'),r=d.parentNode;if(dir<0&&d.previousElementSibling)r.insertBefore(d,d.previousElementSibling);else if(dir>0&&d.nextElementSibling)r.insertBefore(d.nextElementSibling,d)}
function drag(ev,d){ev.preventDefault();var h=ev.target;h.setPointerCapture(ev.pointerId);d.classList.add('drag');
h.onpointermove=function(e){var r=d.parentNode,p=d.previousElementSibling,n=d.nextElementSibling;
if(e.clientY<60)scrollBy(0,-12);if(e.clientY>innerHeight-60)scrollBy(0,12);
if(p){var b=p.getBoundingClientRect();if(e.clientY<b.top+b.height/2)r.insertBefore(d,p)}
if(n){var c=n.getBoundingClientRect();if(e.clientY>c.top+c.height/2)r.insertBefore(n,d)}};
h.onpointerup=h.onpointercancel=function(){d.classList.remove('drag');h.onpointermove=null}}
function add(s){var d=document.createElement('div');d.className='card';
d.innerHTML='<div class=top><span class=hdl>&#9776;</span><span><button type=button class=a onclick="mv(this,-1)">&#9650;</button><button type=button class=a onclick="mv(this,1)">&#9660;</button></span></div><input type=hidden name=i><input name=n placeholder="Name"><input name=m placeholder="M3U URL"><input name=e placeholder="EPG URL for this playlist (optional)"><button type=button class=r onclick="del(this)">Remove</button>';
var i=d.getElementsByTagName('input');i[0].value=s.i||'';i[1].value=s.n||'';i[2].value=s.m||'';i[3].value=s.e||'';
var h=d.querySelector('.hdl');h.onpointerdown=function(ev){drag(ev,d)};document.getElementById('rows').appendChild(d)}
var S=$json;S.forEach(add);if(!S.length)add({})
</script>"""
    }

    fun epgPage(msg: String?): String {
        val src = JSONArray().apply { sources.forEach { put(JSONObject().put("i", it.id).put("n", it.name)) } }.toString().replace("</", "<\\/")
        val eg = JSONArray().apply { epgs.filter { it.owner.isEmpty() }.forEach { e -> put(JSONObject().put("i", e.id).put("n", e.name).put("u", e.url).put("all", e.all).put("a", JSONArray(e.srcIds))) } }.toString().replace("</", "<\\/")
        return """<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'><meta name=color-scheme content=dark><title>LiteTV shared EPGs</title>$iconLink
$css<body><div class=hd>$logoSvg<h2>Lite<span>TV</span> shared EPGs</h2></div><a href=/>&larr; Playlists</a>${if (msg != null) "<p class=msg>$msg</p>" else ""}
<p class=sub>A shared EPG is added once and can be applied to all playlists or only some of them. An EPG that belongs to a single playlist is set on the playlists page. A playlist with no EPG at all uses the one in its M3U header, if it has one.</p>
<form method=post><div id=rows></div>
<button type=button onclick="add({})">+ Add shared EPG</button>
<button class=p style='margin-left:12px'>Save</button></form>
$askJs
<script>
var SRC=$src,EG=$eg,k=0;
function q(t){return (t||'').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/"/g,'&quot;')}
function add(e){var c=k++,d=document.createElement('div');d.className='card';
var h='<input type=hidden name=i_'+c+' value="'+q(e.i)+'"><input name=n_'+c+' placeholder="EPG name" value="'+q(e.n)+'"><input name=u_'+c+' placeholder="XMLTV URL (.xml or .gz)" value="'+q(e.u)+'"><label><input type=checkbox name=all_'+c+(e.all?' checked':'')+'> Apply to all playlists</label><div class=sub>Or only these:</div>';
SRC.forEach(function(s){h+='<label><input type=checkbox name=a_'+c+' value="'+q(s.i)+'"'+((e.a||[]).indexOf(s.i)>=0?' checked':'')+'> '+q(s.n)+'</label>'});
h+='<button type=button class=r onclick="del(this)">Remove</button>';d.innerHTML=h;document.getElementById('rows').appendChild(d)}
EG.forEach(add);if(!EG.length)add({})
</script>"""
    }

    // backup = playlists + EPGs + favorites, read from the saved settings (safe to call from the web server thread)
    fun backupJson(): String = JSONObject().put("app", "LiteTV").put("v", 1)
        .put("sources", JSONArray(prefs.getString("srcs", "[]")))
        .put("epgs", JSONArray(prefs.getString("epgs", "[]")))
        .put("favs", JSONArray((prefs.getStringSet("favs", emptySet()) ?: emptySet()).toList()))
        .toString(2)

    // replaces playlists, EPGs and favorites with the ones in a backup file; returns an error text, or null when it worked
    fun restoreBackup(txt: String): String? {
        try {
            val o = JSONObject(txt)
            if (o.optString("app") != "LiteTV") return "This is not a LiteTV backup file"
            val sa = o.optJSONArray("sources") ?: return "The file has no playlists"
            val ea = o.optJSONArray("epgs") ?: JSONArray()
            val fa = o.optJSONArray("favs") ?: JSONArray()
            val fv = HashSet<String>(); for (k in 0 until fa.length()) fv.add(fa.getString(k))
            prefs.edit().putString("srcs", sa.toString()).putString("epgs", ea.toString()).putStringSet("favs", HashSet(fv)).apply()
            runOnUiThread {
                val curId = sources.getOrNull(act)?.id
                sources = readSrcs(); epgs = readEpgs(); favs.clear(); favs.addAll(fv)
                act = maxOf(0, sources.indexOfFirst { it.id == curId })
                if (sources.isEmpty()) settings() else activate(act, true)
            }
            return null
        } catch (e: Exception) { return "Could not read the file" }
    }

    fun startServer() {
        if (srv != null) return
        Thread {
            try {
                val s = java.net.ServerSocket(PORT); srv = s
                while (true) { val c = s.accept(); Thread { handle(c) }.start() }
            } catch (e: Exception) {}
        }.start()
    }

    fun stopServer() { try { srv?.close() } catch (e: Exception) {}; srv = null }

    fun handle(c: java.net.Socket) {
        try {
            c.use {
                c.soTimeout = 5000
                val i = c.getInputStream().buffered()
                fun line(): String {
                    val sb = StringBuilder()
                    while (true) { val b = i.read(); if (b < 0 || b == '\n'.code) break; if (b != '\r'.code) sb.append(b.toChar()) }
                    return sb.toString()
                }
                val req = line(); var len = 0
                while (true) { val h = line(); if (h.isEmpty()) break; if (h.startsWith("Content-Length:", true)) len = h.substring(15).trim().toInt() }
                val out = c.getOutputStream()
                fun send(body: String, type: String = "text/html; charset=utf-8", extra: String = "") {
                    val b = body.toByteArray()
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: $type\r\n${extra}Content-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write(b); out.flush()
                }
                val path = req.split(" ").getOrElse(1) { "/" }; val epgPg = path.startsWith("/epg")
                if (req.startsWith("POST")) {
                    len = minOf(len, 4_000_000); val buf = ByteArray(len); var r = 0
                    while (r < len) { val n = i.read(buf, r, len - r); if (n < 0) break; r += n }
                    if (path.startsWith("/restore")) { send(restoreBackup(String(buf, 0, r, Charsets.UTF_8)) ?: "ok", "text/plain; charset=utf-8"); return@use }
                    val f = HashMap<String, MutableList<String>>()
                    for (kv in String(buf, 0, r).split("&")) {
                        val q = kv.split("=", limit = 2)
                        f.getOrPut(java.net.URLDecoder.decode(q[0], "UTF-8")) { ArrayList() }.add(java.net.URLDecoder.decode(q.getOrElse(1) { "" }, "UTF-8"))
                    }
                    if (epgPg) {
                        val res = ArrayList<Epg>()
                        for (k in f.keys.filter { it.startsWith("u_") }.map { it.substring(2).toInt() }.sorted()) {
                            val u = (f["u_$k"]?.firstOrNull() ?: "").trim(); if (u.isEmpty()) continue
                            res.add(Epg((f["i_$k"]?.firstOrNull() ?: "").ifEmpty { newId() }, (f["n_$k"]?.firstOrNull() ?: "").trim().ifEmpty { "EPG ${res.size + 1}" },
                                u, f.containsKey("all_$k"), f["a_$k"] ?: emptyList()))
                        }
                        val all = epgs.filter { it.owner.isNotEmpty() } + res
                        saveEpgs(all)
                        runOnUiThread { epgs = all; Thread { cleanCache(); refreshEpgOnly() }.start() }
                        send(epgPage("Saved. EPG updating on TV..."))
                    } else {
                        val ns = f["n"] ?: emptyList<String>(); val ms = f["m"] ?: emptyList<String>(); val ids = f["i"] ?: emptyList<String>(); val es = f["e"] ?: emptyList<String>()
                        val res = ArrayList<Src>(); val own = ArrayList<Epg>()
                        for (k in ms.indices) if (ms[k].trim().isNotEmpty()) {
                            val sid = ids.getOrElse(k) { "" }.ifEmpty { newId() }
                            val nm = ns.getOrElse(k) { "" }.trim().ifEmpty { "Source ${res.size + 1}" }
                            res.add(Src(sid, nm, ms[k].trim()))
                            val eu = es.getOrElse(k) { "" }.trim()
                            if (eu.isNotEmpty()) own.add(Epg("o_$sid", "$nm EPG", eu, false, listOf(sid), sid))
                        }
                        val all = epgs.filter { it.owner.isEmpty() } + own
                        saveSrcs(res); saveEpgs(all)
                        runOnUiThread {
                            val curId = sources.getOrNull(act)?.id   // the list may have been reordered: keep the same playlist active
                            sources = res; epgs = all; act = maxOf(0, res.indexOfFirst { it.id == curId })
                            if (res.isEmpty()) settings() else activate(act, true)
                        }
                        send(page("Saved. Loading on TV..."))
                    }
                } else if (path.startsWith("/backup")) {
                    send(backupJson(), "application/json; charset=utf-8", "Content-Disposition: attachment; filename=\"litetv-backup.json\"\r\n")
                } else send(if (epgPg) epgPage(null) else page(if (path.contains("restored")) "Backup restored. Loading on TV..." else null))
            }
        } catch (e: Exception) {}
    }

    fun settings() {
        startServer()
        val ip = localIp(); val url = ip?.let { "http://$it:$PORT" }
        val views = ArrayList<View>()
        views.add(TextView(this).apply { text = if (url != null) "Scan with your phone to manage playlists and EPGs, or open\n$url" else "No network"; textSize = 18f; setTextColor(0xFFB8BECC.toInt()) })
        if (url != null) views.add(ImageView(this).apply { setImageBitmap(qr(url)) })
        val d = showPanel("Playlist & EPG Setup", views, listOf(
            DBtn("Close", C_FIELD) { it.dismiss() },
            DBtn("Reload", C_FIELD) { it.dismiss(); activate(act, true) },
            DBtn("Add here", C_PUR) { it.dismiss(); addSource() }))
        d.setOnDismissListener { stopServer() }
        dlg = d
    }

    fun addSource() {
        val n = dEdit("Name"); val m = dEdit("M3U URL"); val e = dEdit("EPG URL (optional)")
        showPanel("Add playlist", listOf(n, m, e), listOf(
            DBtn("Cancel", C_FIELD) { it.dismiss() },
            DBtn("Add", C_PUR) { d ->
                d.dismiss()
                if (m.text.isNotBlank()) {
                    val sid = newId(); val nm = n.text.toString().trim().ifEmpty { "Source ${sources.size + 1}" }
                    sources = sources + Src(sid, nm, m.text.toString().trim())
                    if (e.text.isNotBlank()) { epgs = epgs + Epg("o_$sid", "$nm EPG", e.text.toString().trim(), false, listOf(sid), sid); saveEpgs(epgs) }
                    saveSrcs(sources); activate(sources.lastIndex, true)
                }
            }), keyboard = true)
    }

    fun tracks(type: Int) {
        val names = ArrayList<String>(); val acts = ArrayList<() -> Unit>()
        if (type == C.TRACK_TYPE_TEXT) { names.add("Off"); acts.add { player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(type, true).build() } }
        for (g in player.currentTracks.groups) if (g.type == type) for (t in 0 until g.length) {
            val f = g.getTrackFormat(t)
            names.add((f.label ?: f.language ?: "Track ${names.size + 1}") + (if (g.isTrackSelected(t)) "  ✓" else ""))
            acts.add { player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(type, false).setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, t)).build() }
        }
        if (names.isEmpty()) { flash("No tracks available"); return }
        AlertDialog.Builder(this).setItems(names.toTypedArray()) { _, w -> acts[w]() }.show()
    }

    val numRun = Runnable { val n = numBuf.toIntOrNull(); numBuf = ""; if (n != null) channels.firstOrNull { it.num == n }?.let { play(it) } }

    // BACK is handled here (before any view sees it): a short press goes back, holding it exits the app completely
    var backLong = false
    override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
        if (ev.keyCode == KeyEvent.KEYCODE_BACK) {
            if (ev.action == KeyEvent.ACTION_DOWN) {
                if (ev.repeatCount == 0) backLong = false
                else if (!backLong && (ev.isLongPress || ev.repeatCount >= 5)) { backLong = true; exitApp() }
            } else if (ev.action == KeyEvent.ACTION_UP) {
                if (!backLong) backShort(ev)
                backLong = false
            }
            return true
        }
        return super.dispatchKeyEvent(ev)
    }

    fun backShort(ev: KeyEvent) {
        if (ui.mode != 0) { ui.onKeyDown(KeyEvent.KEYCODE_BACK, ev); return }
        if (prev != null) prev?.let { play(it) } else flash("Hold BACK to exit the app", 2500)
    }

    // closes the app for real and frees all of its memory
    fun exitApp() { exiting = true; finishAndRemoveTask() }

    override fun onKeyDown(k: Int, e: KeyEvent): Boolean {
        if (ui.mode != 0) { ui.requestFocus(); return ui.onKeyDown(k, e) }
        val dir = if (REVERSED) 1 else -1
        when (k) {
            KeyEvent.KEYCODE_DPAD_UP -> zap(dir)
            KeyEvent.KEYCODE_DPAD_DOWN -> zap(-dir)
            KeyEvent.KEYCODE_CHANNEL_UP -> zap(1)
            KeyEvent.KEYCODE_CHANNEL_DOWN -> zap(-1)
            KeyEvent.KEYCODE_DPAD_LEFT -> ui.open(1)
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_PROG_BLUE -> ui.open(2)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MENU -> ui.openMenu()
            KeyEvent.KEYCODE_INFO -> cur?.let { banner.show(it) }
            KeyEvent.KEYCODE_LAST_CHANNEL -> prev?.let { play(it) }
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                val d = k - KeyEvent.KEYCODE_0
                if (d == 0 && numBuf.isEmpty()) prev?.let { play(it) }
                else { numBuf += d; flash("Channel $numBuf", 1500); pv.removeCallbacks(numRun); pv.postDelayed(numRun, 1500) }
            }
            else -> return super.onKeyDown(k, e)
        }
        return true
    }

    override fun onStop() { super.onStop(); player.pause(); stopServer(); pv.removeCallbacks(epgTick) }
    override fun onStart() {
        super.onStart(); if (cur != null) player.play()
        pv.removeCallbacks(epgTick); pv.postDelayed(epgTick, 60_000L)   // coming back from Home: refresh the guide a minute later if it is older than the refresh setting
    }
    // pressing Home: give back the memory that is only a cache (logos, programmes read for the screen); they are re-read when needed
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) { logos.evictAll(); progCache.evictAll(); descCache.evictAll() }
    }
    override fun onDestroy() {
        super.onDestroy(); player.release()
        if (exiting) { try { epgDb.close() } catch (e: Exception) {}; android.os.Process.killProcess(android.os.Process.myPid()) }
    }
}