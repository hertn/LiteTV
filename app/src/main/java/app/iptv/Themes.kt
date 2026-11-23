package app.iptv

// ---- themes ----
// Apple style: menus are a neutral material (blurred, dimmed video + a tint) and the focused item is white in every theme.
// A theme only picks the accent (checkmarks, reminders, the "TV" of the logo) - except the seasonal themes,
// which also tint the material and the selection outline. New Year's accent is a polished gold gradient that shimmers.
data class Theme(val name: String, val accent: Int, val panel: Int = 0xFF1C1C1E.toInt(), val alpha: Float = 0.62f,
                 val seasonal: Boolean = false, val gold: Boolean = false, val redFocus: Boolean = false, val snow: Boolean = false,
                 val party: Boolean = false, val spooky: Boolean = false) {   // redFocus: the focus highlight is the accent (white text) instead of white; snow: falling flakes + garland; party: gold confetti + sparkling gold focus; spooky: bats fly out of the main menu
    val fx get() = snow || party || spooky   // the Snow layer runs over the open menus
}

fun withAlpha(c: Int, a: Int): Int = (a shl 24) or (c and 0xFFFFFF)
fun blend(a: Int, b: Int, f: Float): Int {
    fun ch(s: Int) = (((a shr s) and 255) * (1 - f) + ((b shr s) and 255) * f).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

// accents are Apple's dark-mode system colours; Christmas uses Apple's higher-contrast red so it stays readable on the
// selected row and is not the same red as the LIVE badge
val THEMES = listOf(
    Theme("Purple", 0xFFBF5AF2.toInt()),
    Theme("Blue", 0xFF0A84FF.toInt()),
    Theme("Teal", 0xFF40CBE0.toInt()),
    Theme("Green", 0xFF30D158.toInt()),
    Theme("Yellow", 0xFFFFD60A.toInt()),
    Theme("Orange", 0xFFFF9F0A.toInt()),
    Theme("Pink", 0xFFFF375F.toInt()),
    Theme("Graphite", 0xFF8E8E93.toInt()),
    Theme("Christmas", 0xFFFF6961.toInt(), 0xFF0D2A1B.toInt(), 0.72f, seasonal = true, redFocus = true, snow = true),   // red focus, garland on the main menu, falling snow
    Theme("Halloween", 0xFF30D158.toInt(), 0xFF1E0E30.toInt(), 0.65f, seasonal = true, redFocus = true, spooky = true),
    Theme("New Year", 0xFFE8C66A.toInt(), 0xFF0A0A0A.toInt(), 0.72f, seasonal = true, gold = true, party = true)
)
const val FIRST_SEASONAL = 8

const val LIVE_RED = 0xFFFF453A.toInt()   // LIVE badge, the same in every theme

var T: Theme = THEMES[0]   // the theme in use right now

// colours the dialogs (search, playlists) still use: solid panel, accent for the primary button
val C_PUR: Int get() = T.accent
val C_ROW: Int get() = T.panel
val C_BG: Int get() = T.panel
val C_FIELD: Int get() = blend(T.panel, -1, 0.10f)
