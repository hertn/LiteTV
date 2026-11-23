package app.iptv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.abs
import kotlin.math.sin

// Christmas (T.snow) / New Year (T.party): snow or confetti falls over the whole screen while a menu is open; the
// Christmas main menu wears a garland with blinking lights that slides in and out with the menu.
// Halloween (T.spooky): nothing falls; three bats fly out of the main menu when it opens.
// Its own layer above Ui, so the menus are not redrawn 30 times a second; Ui reports the cards it draws (card / garland).
class Snow(ctx: Context) : View(ctx) {
    private class Card(val x: Float, val top: Float, val w: Float, val bottom: Float, var garland: Boolean)
    private val frame = ArrayList<Card>(); private val uiCards = ArrayList<Card>()
    private val popups = LinkedHashMap<Any, Card>()   // pop-up windows (Search, Playlists...): cards in their own window
    @Volatile private var garlandBmp: List<Bitmap>? = null; private var loading = false   // decoded in the background the first time snow starts
    private var garOut: Card? = null   // the garland of a menu that is sliding away
    // flakes: x, y, speed, radius, alpha, sway phase, sway width (per flake, in 1920-wide design px)
    private val N = 120
    private val fx = FloatArray(N); private val fy = FloatArray(N); private val fv = FloatArray(N); private val fr = FloatArray(N)
    private val fa = FloatArray(N); private val fp = FloatArray(N); private val fs = FloatArray(N)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG); private val rnd = java.util.Random(); private val garR = RectF()
    private var on = false; private var closing = 0f; private var last = 0L; private var U = 1f

    private fun newFlake(i: Int, anywhere: Boolean) {
        fx[i] = rnd.nextFloat() * 2000f - 40f; fy[i] = if (anywhere) rnd.nextFloat() * 1120f - 40f else -rnd.nextFloat() * 400f - 10f
        fv[i] = 60f + rnd.nextFloat() * 80f; fr[i] = floatArrayOf(1.5f, 2f, 2f, 2.5f, 3f, 4f)[rnd.nextInt(6)]
        fa[i] = floatArrayOf(.55f, .8f, 1f)[rnd.nextInt(3)]; fp[i] = rnd.nextFloat() * 6.3f; fs[i] = 8f + rnd.nextFloat() * 14f
    }

    // ---- called by Ui while it draws: the cards on screen this frame (screen px) ----
    fun begin() { frame.clear() }
    fun card(x: Float, y: Float, w: Float, h: Float, r: Float) { if (T.fx) frame.add(Card(x, y, w, y + h, false)) }
    fun garland(x: Float, y: Float, w: Float) { frame.lastOrNull { abs(it.x - x) < 2 && abs(it.top - y) < 2 }?.garland = true }
    fun end() { val g = uiCards.firstOrNull { it.garland }
        if (T.spooky && g == null) frame.firstOrNull { it.garland }?.let { batT0 = SystemClock.uptimeMillis(); batX = it.x; batY = it.top; last = batT0; invalidate() }   // the main menu just opened: bats
        uiCards.clear(); uiCards.addAll(frame); if (g != null && uiCards.none { it.garland }) garOut = g; sync() }
    // the menu closed: snow stops unless a pop-up is still open; the garland leaves with the menu
    fun close() { uiCards.firstOrNull { it.garland }?.let { garOut = it }; uiCards.clear(); sync() }
    fun popup(key: Any, x: Float, y: Float, w: Float, h: Float, r: Float) { if (!T.fx) return; popups[key] = Card(x, y, w, y + h, false); sync() }
    fun popupGone(key: Any) { if (popups.remove(key) != null) sync() }
    private fun sync() {
        if (!T.fx) { stop(); return }
        val open = uiCards.isNotEmpty() || popups.isNotEmpty()
        if (!open) { if (on && closing == 0f) { closing = 0.0001f; invalidate() }; return }   // flakes fade out, then the layer sleeps
        if (on && closing == 0f) return   // already snowing: the running animation carries on by itself
        if (!on && !animOk()) return
        if (T.snow && garlandBmp == null && !loading) { loading = true; Thread { garlandBmp = listOf(R.drawable.garland_0, R.drawable.garland_1, R.drawable.garland_2).map { BitmapFactory.decodeResource(resources, it) } }.start() }
        if (!on) for (i in 0 until N) newFlake(i, true)
        on = true; closing = 0f; last = SystemClock.uptimeMillis(); visibility = VISIBLE; invalidate()
    }
    // New Year: confetti instead of flakes. Each piece is a little gold, champagne or silver strip that tumbles as it
    // falls (drawn squashed as it turns edge-on) and flashes bright when it faces the light; the small ones are glitter.
    private val CONF = intArrayOf(0xFFE8C66A.toInt(), 0xFFD4A940.toInt(), 0xFFFFF3C4.toInt(), 0xFFB8862B.toInt(), 0xFFE6E8EE.toInt(), 0xFFF5DFA0.toInt())
    private fun confetti(c: Canvas, i: Int, x: Float, y: Float, a: Float, t: Float) {
        val turn = sin(t * (2f + fs[i] * .12f) + fp[i] * 3f)   // -1..1: how much of its face shows
        val col = CONF[(fp[i] * 10).toInt() % CONF.size]
        if (fr[i] < 2f) {   // glitter: a speck that twinkles
            p.color = ((a * (0.35f + 0.65f * turn * turn) * 255).toInt() shl 24) or (col and 0xFFFFFF); c.drawCircle(x, y, 1.6f * U, p); return
        }
        val lit = turn * turn; val shade = blend(blend(col, 0xFF000000.toInt(), 0.35f * (1 - lit)), -1, if (lit > .92f) .55f else 0f)
        p.color = ((a * 255).toInt() shl 24) or (shade and 0xFFFFFF)
        val w = fr[i] * 1.5f * U; val h = fr[i] * 3.2f * U * maxOf(.15f, abs(turn))
        c.save(); c.rotate(fp[i] * 57f + sin(t + fp[i]) * 40f, x, y); c.drawRect(x - w / 2, y - h / 2, x + w / 2, y + h / 2, p); c.restore()
    }
    // New Year main menu: a gold bead string draped along the top edge in three swags, a star at every hook; a glint runs
    // along the beads and the stars twinkle
    private val star = android.graphics.Path()
    private fun beads(c: Canvas, x: Float, top: Float, w: Float, fade: Float, now: Long) {
        val al = (fade * 255).toInt(); if (al <= 0) return
        val sw = 3; val sag = 16 * U; val step = 9 * U; val y0 = top + 2 * U; val glint = (now % 2600L) / 2600f
        val n = (w / step).toInt()
        for (j in 0..n) {
            val f = j.toFloat() / n; val inS = (f * sw) % 1f; val bx = x + f * w; val by = y0 + sag * 4 * inS * (1 - inS)
            val g = maxOf(0f, 1 - abs(f - glint) * 14)   // the running glint
            p.color = blend(0xFF9A7425.toInt(), 0xFFFFF3C4.toInt(), 0.35f + 0.65f * g); p.alpha = al; c.drawCircle(bx, by, 3.4f * U, p)
            p.color = 0xFFFFFFFF.toInt(); p.alpha = (al * (0.35f + 0.65f * g)).toInt(); c.drawCircle(bx - U, by - U, 1.1f * U, p)
        }
        for (k in 0..sw) {
            val sx = x + w * k / sw; val tw = 0.75f + 0.25f * sin(now / 380f + k * 1.7f); val r = 15 * U * tw
            star.reset(); for (q in 0 until 10) { val ang = -Math.PI / 2 + q * Math.PI / 5; val rr = if (q % 2 == 0) r else r * .45f
                val px = sx + (rr * Math.cos(ang)).toFloat(); val py = y0 + (rr * Math.sin(ang)).toFloat(); if (q == 0) star.moveTo(px, py) else star.lineTo(px, py) }
            star.close()
            p.color = blend(0xFFD4A940.toInt(), 0xFFFFF3C4.toInt(), tw - .5f); p.alpha = al; c.drawPath(star, p)
            if (tw > .97f) { p.color = 0xFFFFFFFF.toInt(); p.alpha = (al * .9f).toInt(); p.strokeWidth = 1.4f * U; p.style = Paint.Style.STROKE   // a sparkle at its brightest
                c.drawLine(sx - r * 1.6f, y0, sx + r * 1.6f, y0, p); c.drawLine(sx, y0 - r * 1.6f, sx, y0 + r * 1.6f, p); p.style = Paint.Style.FILL }
        }
        p.alpha = 255
    }
    // Halloween: when the main menu opens, three bats spill up out of its top edge, each on its own lane (high, middle,
    // low, never crossing), arc out over the picture and come past you, growing; ~1.4 s, then gone. Drawn in design px
    // around the body: shoulder, wrist (thumb claw), four fingertips and the ankle, the membrane scalloped between them.
    private var batT0 = 0L; private var batX = 0f; private var batY = 0f
    private val BW = floatArrayOf(5f, -6f, 28f, -19f, 66f, -14f, 60f, 2f, 47f, 12f, 31f, 16f, 8f, 13f)      // wing points
    private val BC = floatArrayOf(15f, -16f, 48f, -24f, 58f, -8f, 50f, 2f, 37f, 9f, 18f, 10f)                // curve controls into points 1..6
    private val LANES = arrayOf(floatArrayOf(94f, 760f, -60f, .26f, 3.2f, 1.6f), floatArrayOf(264f, 1420f, 330f, .13f, 4f, 1.3f), floatArrayOf(434f, 1980f, 760f, 0f, 4.6f, .8f))
    private val bp = android.graphics.Path(); private val wq = FloatArray(14); private val wc = FloatArray(12)
    private var membSh: android.graphics.LinearGradient? = null; private var bodySh: android.graphics.LinearGradient? = null
    private fun bats(c: Canvas, now: Long): Boolean {
        if (batT0 == 0L) return false
        val t = (now - batT0) / 1000f; var any = false
        for (i in 0..2) {
            val L = LANES[i]; val k = (t - L[3]) / 1.15f
            if (k > 1.05f) continue; any = true; if (k <= 0f) continue
            val sx = batX + L[0] * U; val sy = batY + 18 * U
            val x = sx + (L[1] * U - sx) * Math.pow(k.toDouble(), 1.3).toFloat()
            val y = sy + (L[2] * U - sy) * Math.pow(k.toDouble(), L[5].toDouble()).toFloat() - 90 * U * sin(Math.PI.toFloat() * minOf(1f, k * 1.4f)) * (1 - k)
            val s = (1f + (L[4] - 1f) * k * k) * U
            batShape(c, x, y, s, sin(t * 6.5f * 6.2832f + i * 2.1f), -12f + 20f * k, if (k > .8f) (1 - (k - .8f) / .25f).coerceIn(0f, 1f) else 1f)
        }
        if (!any) batT0 = 0L
        return any
    }
    private fun batShape(c: Canvas, x: Float, y: Float, s: Float, e: Float, rot: Float, alpha: Float) {
        if (membSh == null) {
            membSh = android.graphics.LinearGradient(0f, -24f, 0f, 18f, 0xFF3A2F4E.toInt(), 0xFF16101E.toInt(), android.graphics.Shader.TileMode.CLAMP)
            bodySh = android.graphics.LinearGradient(0f, -22f, 0f, 14f, intArrayOf(0xFF3E3354.toInt(), 0xFF1C1526.toInt(), 0xFF0C0810.toInt()), floatArrayOf(0f, .5f, 1f), android.graphics.Shader.TileMode.CLAMP)
        }
        val al = (alpha * 255).toInt()
        c.save(); c.translate(x, y); c.rotate(rot); c.scale(s, s)
        val ang = -e * 0.8f; val fold = 1 - maxOf(0f, -e) * 0.2f; val ca = Math.cos(ang.toDouble()).toFloat(); val sa = Math.sin(ang.toDouble()).toFloat()
        fun tf(px0: Float, py0: Float, out: FloatArray, o: Int) { val px = (px0 - 5) * fold; val py = py0 + 6; out[o] = px * ca - py * sa + 5; out[o + 1] = px * sa + py * ca - 6 }
        for (k in 0..6) tf(BW[k * 2], BW[k * 2 + 1], wq, k * 2)
        for (k in 0..5) tf(BC[k * 2], BC[k * 2 + 1], wc, k * 2)
        for (sg in intArrayOf(1, -1)) {
            bp.reset(); bp.moveTo(sg * wq[0], wq[1])
            for (k in 1..6) bp.quadTo(sg * wc[(k - 1) * 2], wc[(k - 1) * 2 + 1], sg * wq[k * 2], wq[k * 2 + 1])
            bp.close()
            p.style = Paint.Style.FILL; p.shader = membSh; p.color = -1; p.alpha = al; c.drawPath(bp, p); p.shader = null
            p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND
            p.color = 0xFF4C3F63.toInt(); p.alpha = (al * .7f).toInt(); p.strokeWidth = 1f
            for (k in 2..5) c.drawLine(sg * wq[2], wq[3], sg * wq[k * 2], wq[k * 2 + 1], p)   // finger bones
            p.color = 0xFF1A1324.toInt(); p.alpha = al; p.strokeWidth = 2f; c.drawLine(sg * wq[0], wq[1], sg * wq[2], wq[3], p)   // arm
            val thx = 25f; val thy = -24f; val px = (thx - 5) * fold; val py = thy + 6; val tx2 = px * ca - py * sa + 5; val ty2 = px * sa + py * ca - 6
            p.strokeWidth = 1.4f; bp.reset(); bp.moveTo(sg * wq[2], wq[3]); bp.quadTo(sg * tx2, ty2 - 1, sg * (tx2 + 1.5f), ty2 + 2); c.drawPath(bp, p)   // thumb claw
            p.style = Paint.Style.FILL; p.strokeCap = Paint.Cap.BUTT
        }
        p.shader = membSh; p.color = -1; p.alpha = (al * .9f).toInt()
        bp.reset(); bp.moveTo(-7f, 12f); bp.quadTo(0f, 22f, 7f, 12f); bp.quadTo(0f, 15f, -7f, 12f); bp.close(); c.drawPath(bp, p)   // tail membrane
        p.shader = bodySh; p.alpha = al
        c.drawOval(-6f, -9f, 6f, 13f, p); c.drawOval(-5.2f, -14.8f, 5.2f, -5.2f, p)   // body, head
        bp.reset(); bp.moveTo(-4.2f, -12.5f); bp.quadTo(-7.5f, -20f, -5.2f, -23f); bp.quadTo(-2.6f, -18f, -1.3f, -13.5f); bp.close()
        bp.moveTo(4.2f, -12.5f); bp.quadTo(7.5f, -20f, 5.2f, -23f); bp.quadTo(2.6f, -18f, 1.3f, -13.5f); bp.close(); c.drawPath(bp, p)   // ears
        p.shader = null; p.color = 0xFF0C0810.toInt(); p.alpha = al
        bp.reset(); bp.moveTo(-1.5f, -6.5f); bp.lineTo(0f, -3.5f); bp.lineTo(1.5f, -6.5f); bp.close(); c.drawPath(bp, p)
        p.alpha = 255
        c.restore()
    }
    private fun stop() { on = false; closing = 0f; garOut = null; visibility = GONE }
    private fun animOk() = try { android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f } catch (e: Exception) { true }

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis(); val dt = ((now - last).coerceIn(1, 100)) / 1000f; last = now; U = width / 1920f
        val ui = (context as MainActivity).ui
        if (ui.outMode == 0) garOut = null
        if (closing > 0f) { closing += dt; if (closing > .6f && garOut == null) { stop(); return } }
        val t = now / 1000f
        p.style = Paint.Style.FILL
        // flakes: fall and sway; a flake reaching a card disappears into it and starts again at the top
        if (on && !T.spooky) for (i in 0 until N) {
            fy[i] += fv[i] * dt; val x = (fx[i] + sin(t * 1.3f + fp[i]) * fs[i]) * U; val y = fy[i] * U
            val gone = fy[i] > 1100f   // falls over everything, menus included
            if (gone) { newFlake(i, false); if (closing > 0f) fy[i] = 99999f; continue }
            if (closing > 0f && fy[i] > 9000f) continue
            val a = fa[i] * (if (closing > 0f) maxOf(0f, 1 - closing / .6f) else 1f)
            if (T.party) { confetti(c, i, x, y, a, t); continue }
            p.color = ((a * 255).toInt() shl 24) or 0xFFFFFF
            if (fr[i] >= 3f) {   // the bigger flakes are little six-pointed stars
                val L = fr[i] * 1.7f * U; p.strokeWidth = 1.3f * U; p.style = Paint.Style.STROKE
                c.drawLine(x - L, y, x + L, y, p); c.drawLine(x - L * .5f, y - L * .87f, x + L * .5f, y + L * .87f, p); c.drawLine(x - L * .5f, y + L * .87f, x + L * .5f, y - L * .87f, p)
                p.style = Paint.Style.FILL; c.drawCircle(x, y, fr[i] * .45f * U, p)
            } else c.drawCircle(x, y, fr[i] * U, p)
        }
        // garland (Christmas main menu): needles, berries, bow and bulbs; three pictures take turns so the bulbs blink.
        // It follows the menu's slide and fade (a View animation on Ui), in and out.
        val tx = ui.translationX; val ty = ui.translationY; val fade = (ui.alpha / maxOf(.01f, (context as MainActivity).uiAlpha())).coerceIn(0f, 1f)
        val gb = garlandBmp
        if (T.party) for (k in uiCards + listOfNotNull(garOut)) if (k.garland) beads(c, k.x + tx, k.top + ty, k.w, fade, now)
        val flying = T.spooky && bats(c, now)
        if (gb != null && T.snow) for (k in uiCards + listOfNotNull(garOut)) if (k.garland) {
            val b = gb[((now / 600) % 3).toInt()]; p.color = 0xFFFFFFFF.toInt(); p.alpha = (fade * 255).toInt()
            garR.set(k.x - 6 * U + tx, k.top - 19 * U + ty, k.x - 6 * U + tx + b.width * U, k.top - 19 * U + ty + b.height * U); c.drawBitmap(b, null, garR, p)
        }
        p.alpha = 255
        val anim = tx != 0f || ty != 0f || fade < 1f
        if (T.spooky && !flying && !anim && closing == 0f && garOut == null) return   // Halloween: nothing to animate between bat flights
        if (on || garOut != null) postInvalidateDelayed(if (anim || flying) 16 else 33) else visibility = GONE
    }
}
