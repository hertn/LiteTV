package app.iptv

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path

// line icons on a 24x24 grid (same shapes as the redesign mockups); stroke 2, round caps; "true" parts are filled
val ICON_SRC: Map<String, List<Pair<String, Boolean>>> = mapOf(
    "blur" to listOf("M9.6 12a2.4 2.4 0 1 0 4.8 0a2.4 2.4 0 1 0 -4.8 0z" to true, "M10.3 6.6a1.7 1.7 0 1 0 3.4 0a1.7 1.7 0 1 0 -3.4 0z" to true, "M10.3 17.4a1.7 1.7 0 1 0 3.4 0a1.7 1.7 0 1 0 -3.4 0z" to true, "M4.9 12a1.7 1.7 0 1 0 3.4 0a1.7 1.7 0 1 0 -3.4 0z" to true, "M15.7 12a1.7 1.7 0 1 0 3.4 0a1.7 1.7 0 1 0 -3.4 0z" to true, "M6.0 7.2a1.2 1.2 0 1 0 2.4 0a1.2 1.2 0 1 0 -2.4 0z" to true, "M15.6 7.2a1.2 1.2 0 1 0 2.4 0a1.2 1.2 0 1 0 -2.4 0z" to true, "M6.0 16.8a1.2 1.2 0 1 0 2.4 0a1.2 1.2 0 1 0 -2.4 0z" to true, "M15.6 16.8a1.2 1.2 0 1 0 2.4 0a1.2 1.2 0 1 0 -2.4 0z" to true, "M11.2 2a0.8 0.8 0 1 0 1.6 0a0.8 0.8 0 1 0 -1.6 0z" to true, "M11.2 22a0.8 0.8 0 1 0 1.6 0a0.8 0.8 0 1 0 -1.6 0z" to true, "M1.2 12a0.8 0.8 0 1 0 1.6 0a0.8 0.8 0 1 0 -1.6 0z" to true, "M21.2 12a0.8 0.8 0 1 0 1.6 0a0.8 0.8 0 1 0 -1.6 0z" to true),   // dots that fade outwards: a soft edge
    "layers" to listOf("M15 9V6a3 3 0 0 0-3-3H6a3 3 0 0 0-3 3v6a3 3 0 0 0 3 3h3" to false, "M12 9h6a3 3 0 0 1 3 3v6a3 3 0 0 1-3 3h-6a3 3 0 0 1-3-3v-6a3 3 0 0 1 3-3z" to false),   // one card over another
    "bug" to listOf("M12 6a5.25 5.25 0 0 1 5.25 5.25v3.75a5.25 5.25 0 0 1 -10.5 0v-3.75a5.25 5.25 0 0 1 5.25 -5.25z" to false, "M3 11.25h3.75M17.25 11.25h3.75M3.75 17.25l3-1.5M20.25 17.25l-3-1.5M9 3.75l1.5 2.25M15 3.75l-1.5 2.25" to false),
    "x" to listOf("M6 6l12 12M18 6L6 18" to false),
    "grip" to listOf("M7.4 6a1.6 1.6 0 1 0 3.2 0a1.6 1.6 0 1 0 -3.2 0zM13.4 6a1.6 1.6 0 1 0 3.2 0a1.6 1.6 0 1 0 -3.2 0zM7.4 12a1.6 1.6 0 1 0 3.2 0a1.6 1.6 0 1 0 -3.2 0zM13.4 12a1.6 1.6 0 1 0 3.2 0a1.6 1.6 0 1 0 -3.2 0zM7.4 18a1.6 1.6 0 1 0 3.2 0a1.6 1.6 0 1 0 -3.2 0zM13.4 18a1.6 1.6 0 1 0 3.2 0a1.6 1.6 0 1 0 -3.2 0z" to true),   // a grab handle: this row can be moved
    "updown" to listOf("M12 3l5 6H7zM12 21l5-6H7z" to true),   // picked up: UP / DOWN move it
    "move" to listOf("M12 3v18M8 7l4-4 4 4M8 17l4 4 4-4" to false),
    "trash" to listOf("M4 7h16M10 7V4.5h4V7M6.5 7l1 13h9l1-13M10 11v6M14 11v6" to false),
    "plus" to listOf("M12 5v14M5 12h14" to false),
    "delete" to listOf("M9 6h11v12H9l-6-6z" to false, "M12.5 9.5l5 5M17.5 9.5l-5 5" to false),   // the search keyboard's backspace
    "back" to listOf("M14.5 6l-6 6 6 6" to false),   // the remote's Back button
    "mic" to listOf("M9 6a3 3 0 0 1 6 0v5a3 3 0 0 1 -6 0z" to true, "M5.5 11a6.5 6.5 0 0 0 13 0M12 17.5v3.5" to false),   // voice typing
    "search" to listOf("M4.0 10.5a6.5 6.5 0 1 0 13.0 0a6.5 6.5 0 1 0 -13.0 0z" to false, "M15.5 15.5L21 21" to false),
    "phone" to listOf("M9.0 2.5h6.0a2.5 2.5 0 0 1 2.5 2.5v14.0a2.5 2.5 0 0 1 -2.5 2.5h-6.0a2.5 2.5 0 0 1 -2.5 -2.5v-14.0a2.5 2.5 0 0 1 2.5 -2.5z" to false, "M10.5 18.5h3" to false),
    "guide" to listOf("M4.5 4.0h15.0a1.5 1.5 0 0 1 1.5 1.5v1.0a1.5 1.5 0 0 1 -1.5 1.5h-15.0a1.5 1.5 0 0 1 -1.5 -1.5v-1.0a1.5 1.5 0 0 1 1.5 -1.5z" to false, "M4.5 10.0h15.0a1.5 1.5 0 0 1 1.5 1.5v1.0a1.5 1.5 0 0 1 -1.5 1.5h-15.0a1.5 1.5 0 0 1 -1.5 -1.5v-1.0a1.5 1.5 0 0 1 1.5 -1.5z" to false, "M4.5 16.0h15.0a1.5 1.5 0 0 1 1.5 1.5v1.0a1.5 1.5 0 0 1 -1.5 1.5h-15.0a1.5 1.5 0 0 1 -1.5 -1.5v-1.0a1.5 1.5 0 0 1 1.5 -1.5z" to false),
    "onair" to listOf("M5.5 4.5h13.0a3.0 3.0 0 0 1 3.0 3.0v9.0a3.0 3.0 0 0 1 -3.0 3.0h-13.0a3.0 3.0 0 0 1 -3.0 -3.0v-9.0a3.0 3.0 0 0 1 3.0 -3.0z" to false, "M10 9v6l5-3z" to true),
    "eye" to listOf("M2.5 12s3.5-6.5 9.5-6.5S21.5 12 21.5 12s-3.5 6.5-9.5 6.5S2.5 12 2.5 12z" to false, "M9.2 12.0a2.8 2.8 0 1 0 5.6 0a2.8 2.8 0 1 0 -5.6 0z" to false),
    "last" to listOf("M12 6l-6 6 6 6M19 6l-6 6 6 6" to false),
    "gear" to listOf("M8.8 12.0a3.2 3.2 0 1 0 6.4 0a3.2 3.2 0 1 0 -6.4 0z" to false, "M12 2.5v2.8M12 18.7v2.8M2.5 12h2.8M18.7 12h2.8M5.3 5.3l2 2M16.7 16.7l2 2M5.3 18.7l2-2M16.7 7.3l2-2" to false, "M5.4 12.0a6.6 6.6 0 1 0 13.2 0a6.6 6.6 0 1 0 -13.2 0z" to false),
    "remote" to listOf("M8.5 2.5h7a2 2 0 0 1 2 2v15a2 2 0 0 1 -2 2h-7a2 2 0 0 1 -2 -2v-15a2 2 0 0 1 2 -2z" to false, "M9.6 8.5a2.4 2.4 0 1 0 4.8 0a2.4 2.4 0 1 0 -4.8 0z" to false, "M10 14.5h.01M14 14.5h.01M10 18h.01M14 18h.01" to false),
    "check" to listOf("M4.5 12.5l5 5L19.5 7" to false),
    "play" to listOf("M7 4.5v15l13-7.5z" to true),
    "grid" to listOf("M5 4h14a2 2 0 0 1 2 2v12a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2v-12a2 2 0 0 1 2 -2z" to false, "M3 10h18" to false, "M9 4v16" to false),
    "info" to listOf("M2.5 12.0a9.5 9.5 0 1 0 19.0 0a9.5 9.5 0 1 0 -19.0 0z" to false, "M12 11v6" to false, "M10.9 7.6a1.1 1.1 0 1 0 2.2 0a1.1 1.1 0 1 0 -2.2 0z" to true),
    "sliders" to listOf("M4 7h9M17 7h3M4 17h3M11 17h9" to false, "M12.8 7.0a2.2 2.2 0 1 0 4.4 0a2.2 2.2 0 1 0 -4.4 0z" to false, "M6.8 17.0a2.2 2.2 0 1 0 4.4 0a2.2 2.2 0 1 0 -4.4 0z" to false),
    "star" to listOf("M12 3l2.7 5.6 6.1.8-4.5 4.2 1.1 6.1L12 16.8l-5.4 2.9 1.1-6.1-4.5-4.2 6.1-.8z" to false),
    "hide" to listOf("M2.5 12s3.5-6.5 9.5-6.5S21.5 12 21.5 12s-3.5 6.5-9.5 6.5S2.5 12 2.5 12z" to false, "M9.2 12.0a2.8 2.8 0 1 0 5.6 0a2.8 2.8 0 1 0 -5.6 0z" to false, "M4 20L20 4" to false),
    "audio" to listOf("M4 9v6M8 6v12M12 3v18M16 7v10M20 10v4" to false),
    "cc" to listOf("M5.5 5.0h13.0a3.0 3.0 0 0 1 3.0 3.0v8.0a3.0 3.0 0 0 1 -3.0 3.0h-13.0a3.0 3.0 0 0 1 -3.0 -3.0v-8.0a3.0 3.0 0 0 1 3.0 -3.0z" to false, "M10.5 10.2a2.4 2.4 0 1 0 0 3.6M17.5 10.2a2.4 2.4 0 1 0 0 3.6" to false),
    "aspect" to listOf("M5.0 5.0h14.0a2.5 2.5 0 0 1 2.5 2.5v9.0a2.5 2.5 0 0 1 -2.5 2.5h-14.0a2.5 2.5 0 0 1 -2.5 -2.5v-9.0a2.5 2.5 0 0 1 2.5 -2.5z" to false, "M6.5 11V9h2.5M17.5 13v2H15" to false),
    "delay" to listOf("M4.0 13.0a8.0 8.0 0 1 0 16.0 0a8.0 8.0 0 1 0 -16.0 0z" to false, "M12 9v4l2.5 2.5M9.5 2.5h5" to false),
    "palette" to listOf("M12 3a9 9 0 1 0 0 18c1.4 0 2-1 1.4-2.2-.7-1.3.2-2.8 1.7-2.8H17a4 4 0 0 0 4-4c0-5-4-9-9-9z" to false, "M6.3 11.0a1.2 1.2 0 1 0 2.4 0a1.2 1.2 0 1 0 -2.4 0z" to true, "M8.8 7.0a1.2 1.2 0 1 0 2.4 0a1.2 1.2 0 1 0 -2.4 0z" to true, "M13.8 7.5a1.2 1.2 0 1 0 2.4 0a1.2 1.2 0 1 0 -2.4 0z" to true),
    "text" to listOf("M3 19L8 6l5 13M4.8 14.5h6.4M14 19l3.5-9 3.5 9M15 16.5h5" to false),
    "leaf" to listOf("M5 19c0-8 5-14 15-14 0 10-6 15-14 15" to false, "M5 19l8-8" to false),
    "gauge" to listOf("M4 17a8 8 0 1 1 16 0" to false, "M12 17l4-5" to false),
    "moon" to listOf("M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5z" to false),
    "refresh" to listOf("M20 12a8 8 0 1 1-2.4-5.7" to false, "M20 4v4.5h-4.5" to false),
    "cal" to listOf("M6.0 5.0h12.0a3.0 3.0 0 0 1 3.0 3.0v10.0a3.0 3.0 0 0 1 -3.0 3.0h-12.0a3.0 3.0 0 0 1 -3.0 -3.0v-10.0a3.0 3.0 0 0 1 3.0 -3.0z" to false, "M3 10h18M8 3v4M16 3v4" to false),
    "down" to listOf("M12 4v11M7 10.5l5 5 5-5M5 20h14" to false),
    "back15" to listOf("M4 12a8 8 0 1 0 2.4-5.7" to false, "M4 4v4.5h4.5" to false),
    "grid" to listOf("M6.0 4.0h12.0a3.0 3.0 0 0 1 3.0 3.0v10.0a3.0 3.0 0 0 1 -3.0 3.0h-12.0a3.0 3.0 0 0 1 -3.0 -3.0v-10.0a3.0 3.0 0 0 1 3.0 -3.0z" to false, "M3 10h18M9 10v10" to false),
    "more" to listOf("M4.4 12.0a1.6 1.6 0 1 0 3.2 0a1.6 1.6 0 1 0 -3.2 0z" to true, "M10.4 12.0a1.6 1.6 0 1 0 3.2 0a1.6 1.6 0 1 0 -3.2 0z" to true, "M16.4 12.0a1.6 1.6 0 1 0 3.2 0a1.6 1.6 0 1 0 -3.2 0z" to true),
    "pin" to listOf("M9 3h6M10 3v7l-3 4h10l-3-4V3" to false, "M12 14v7" to false),   // push pin
    "bell" to listOf("M6 16V11a6 6 0 0 1 12 0v5l1.5 2h-15z" to false, "M10 20.5a2 2 0 0 0 4 0" to false)
)
private val iconCache = HashMap<String, List<Pair<Path, Boolean>>>()
private val iconM = Matrix()
private val iconP = Path()

// an icon as a Drawable, for the Android views in the pop-ups
class IconDrawable(val k: String, var color: Int) : android.graphics.drawable.Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(c: Canvas) { p.color = color; drawIcon(c, k, bounds.exactCenterX(), bounds.exactCenterY(), minOf(bounds.width(), bounds.height()).toFloat(), p) }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
    @Deprecated("") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

// draws icon k centred on (cx, cy), sz px wide; the paint's colour or shader is used as is
fun drawIcon(c: Canvas, k: String, cx: Float, cy: Float, sz: Float, p: Paint) {
    val parts = iconCache.getOrPut(k) { (ICON_SRC[k] ?: ICON_SRC.getValue("info")).map { androidx.core.graphics.PathParser.createPathFromPathData(it.first) to it.second } }
    val s = sz / 24f; iconM.setScale(s, s); iconM.postTranslate(cx - sz / 2, cy - sz / 2)
    val st = p.style; val sw = p.strokeWidth; val cap = p.strokeCap; val j = p.strokeJoin
    p.strokeWidth = 2f * s; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
    for ((path, filled) in parts) {
        iconP.reset(); path.transform(iconM, iconP)
        p.style = if (filled) Paint.Style.FILL else Paint.Style.STROKE; c.drawPath(iconP, p)
    }
    p.style = st; p.strokeWidth = sw; p.strokeCap = cap; p.strokeJoin = j
}
