package app.iptv

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.KeyEvent

// ---- Search (main menu), the Apple TV way ----
// The usual ‹ + title header, a big borderless field, one thin row of letters (mic · 123 · SPACE · a-z · ⌫), a hairline, then
// shelves of cards that scroll sideways. No Search button: results update by themselves 0.3 s after the last letter
// (typing itself only works out suggestions, from a word list kept in memory). The whole page scrolls with the focus.
// Before typing the page is empty but for one quiet line in the middle (like Apple TV).
// Voice: the mic key or the remote's mic opens Google's speech screen; the words come back into the field.
// Holding OK on a letter pops up its other forms (ä é ñ ...). BACK deletes a letter; holding BACK clears the words, holding it again leaves. Holding LEFT / RIGHT speeds up; Play/Pause = space, Rewind = delete.
// Every word must be in the channel name or the show title, so "nbc thanksgiving" finds Thanksgiving shows on NBC channels.
// Results: the window at the top right shows the focused card's picture; OK plays it there (one stream, the main player shrunk),
// OK again = watch full screen, and the channel list becomes that channel's (Favorites, or its own playlist and category). A channel that fails gets "Not working" on its card (remembered).
// While a preview plays the page never closes by itself.
// Leaving without watching goes back to the channel from before. The page keeps the words and the place you were when it opens again.

const val REQ_SPEECH = 7117

class SearchState {
    var q = ""; var num = false; var key = -1; var foc = 0   // foc: 0 letters, 3 suggestions, 4 CHANNELS, 1 ON NOW, 2 LATER
    var chSel = 0; var nowSel = 0; var laterSel = 0; var sugSel = 0; var gen = 0; var sgGen = 0; var busy = false
    var done = ""   // the words the results on screen are for
    var res = SearchRes(emptyList(), 0, emptyList())
    var idx: List<Pair<String, String>> = emptyList(); var sugg: List<String> = emptyList()   // suggestion word list (with lowercase), current picks
    var sgRun: Runnable? = null; var idxVer = -1; var doneAt = 0L; var autoRun: Runnable? = null
    var acc: List<String>? = null; var accSel = 0   // the accented letters pop-up
    var mic: Boolean? = null   // voice typing available on this TV
    var pageY = 0f; var pageAt = 0L   // how far the page has scrolled (animated)
    val hOff = FloatArray(3); var hAt = 0L   // each shelf's sideways scroll (CHANNELS, ON NOW, LATER), animated
    var cardKey = ""; var cardPrev = ""; var cardAt = 0L   // the focused card grows over 0.2 s, the one before shrinks back
    var before: Ch? = null; var prevBefore: Ch? = null; var started = false   // the channel from before the previews, put back when leaving
    var pvWant: String? = null; var pvAt = 0L   // the channel in the preview window (OK on its card), and when it was asked for
    val dead = HashSet<String>(); var deadLoaded = false   // channels that failed in a preview (prefs "deadCh")
    val tints = HashMap<String, Int>(); val grads = HashMap<Long, LinearGradient>()
    val work = java.util.concurrent.Executors.newSingleThreadExecutor()   // suggestions, the index, favourites: one at a time, never piled up
    val find = java.util.concurrent.Executors.newSingleThreadExecutor()   // the searches, apart so a slow one never holds up suggestions
}
// now: channel-name matches (the first nCh) then shows on now; later: shows still to come, by start
class SearchRes(val now: List<Pair<Ch, Prog?>>, val nCh: Int, val later: List<Pair<Ch, Prog>>, val days: List<String> = emptyList())

// the letter row: mic (when the TV can do voice typing), 123 / ABC, SPACE, the letters (or digits and signs), delete
fun searchKeys(num: Boolean, mic: Boolean): List<String> = (if (mic) listOf("mic") else emptyList()) + listOf(if (num) "ABC" else "123", "SPACE") +
    (if (num) "1234567890&'-.:!?+@#".map { it.toString() } else ('a'..'z').map { it.toString() }) + "del"
// other forms of a letter, shown when OK is held on it
val ACCENTS = mapOf("a" to "äåàáâãæ", "c" to "çčć", "d" to "ď", "e" to "éèêëě", "g" to "ğ", "i" to "íìîï", "l" to "ł", "n" to "ñň", "o" to "öøóòôõœ",
    "r" to "ř", "s" to "šßś", "t" to "ť", "u" to "üúùûů", "y" to "ýÿ", "z" to "žźż")

fun MainActivity.allChannels(): List<Ch> {
    val all = ArrayList<Ch>()
    for (src in sources.toList()) all += (chCache[src.id] ?: try { loadPlaylist(src, false) } catch (e: Exception) { emptyList() }).filter { it.url !in hidden }
    return all
}
fun MainActivity.guideScope() = sources.toList().flatMap { epgUrls(it) }.distinct().map { hx(it) }

// the suggestion word list: every show title still to come in the guides (episode numbers dropped) and every channel name
fun MainActivity.searchIndex(): List<Pair<String, String>> {
    val out = LinkedHashSet<String>(); val now = System.currentTimeMillis(); val sc = guideScope()
    if (sc.isNotEmpty()) try {
        epgDb.rawQuery("SELECT DISTINCT t FROM p WHERE e > $now AND src IN (" + sc.joinToString(",") { "?" } + ")", sc.toTypedArray()).use { cur ->
            while (cur.moveToNext()) cur.getString(0)?.let { out.add(remTitle(it)) } }
    } catch (e: Exception) {}
    (chCache.values.flatten() + channels).forEach { if (it.url !in hidden) out.add(it.name) }   // only playlists already loaded: searchData loads the rest (no double downloads)
    return out.filter { it.isNotBlank() }.map { it to it.lowercase() }   // lowercased once here, not on every letter
}

// suggestions for the words typed so far: the last word completes a title or channel name, the words before it stay in front
// ("nbc thanks" -> "nbc Thanksgiving Parade"); starts-with first, then a word that starts with it, shortest first
fun searchSuggest(idx: List<Pair<String, String>>, q: String, stale: () -> Boolean = { false }): List<String> {
    val words = q.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }; if (words.isEmpty()) return emptyList()
    val last = words.last(); val lead = words.dropLast(1).joinToString(" ")
    fun rank(l: String) = if (l.startsWith(last)) 0 else if (l.split(' ', '-', ':', '\'').any { it.startsWith(last) }) 1 else 2
    val hits = ArrayList<Pair<Int, String>>()
    for ((i, e) in idx.withIndex()) { if (i % 4000 == 0 && stale()) return emptyList(); val (s, l) = e; if (l.contains(last) && l != last) hits.add(rank(l) to s) }
    return hits.sortedWith(compareBy({ it.first }, { it.second.length })).map { if (lead.isEmpty()) it.second else "$lead ${it.second}" }
        .distinctBy { it.lowercase() }.filter { it.lowercase() != q.trim().lowercase() }.take(8)
}

// the search itself (off the UI thread): every word must be in the channel name or the show title
fun MainActivity.searchData(q: String): SearchRes {
    val now = System.currentTimeMillis(); val all = allChannels(); val sc = guideScope(); val inSc = sc.joinToString(",") { "?" }
    val words = q.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    fun hasAll(vararg s: String) = words.all { w -> s.any { it.lowercase().contains(w) } }
    fun onNow(c: Ch): Prog? {
        if (sc.isEmpty()) return null
        for (k in keysOf(c)) try {
            epgDb.rawQuery("SELECT s, e, t, i FROM p WHERE ch = ? AND s <= $now AND e > $now AND src IN ($inSc) LIMIT 1", (listOf(k) + sc).toTypedArray()).use { cur ->
                if (cur.moveToFirst()) return Prog(cur.getLong(0), cur.getLong(1), cur.getString(2) ?: "", "", cur.getString(3) ?: "") }
        } catch (e: Exception) {}
        return null
    }
    val seen = HashSet<String>()
    val nowL = ArrayList<Pair<Ch, Prog?>>(all.asSequence().filter { hasAll(it.name) && seen.add(it.url) }.take(60).map { it to onNow(it) }.toList())   // lazy: channels past 60 stay free for ON NOW
    val nCh = nowL.size; val later = ArrayList<Pair<Ch, Prog>>()
    if (sc.isNotEmpty() && words.isNotEmpty()) {
        val byKey = HashMap<String, Ch>(); for (c in all) for (k in keysOf(c)) byKey.putIfAbsent(k, c)   // guide key -> channel (first playlist wins)
        val got = HashSet<String>()
        try {   // titles with any of the words; the channel name can hold the others
            epgDb.rawQuery("SELECT ch, s, e, t, i FROM p WHERE e > $now AND (" + words.joinToString(" OR ") { "t LIKE ? ESCAPE '\\'" } + ") AND src IN ($inSc) ORDER BY s LIMIT 1500",
                (words.map { likeArg(it) } + sc).toTypedArray()).use { cur ->
                while (cur.moveToNext()) {
                    val t = cur.getString(3) ?: ""; val c = byKey[cur.getString(0)] ?: continue
                    if (!hasAll(t, c.name)) continue
                    val p = Prog(cur.getLong(1), cur.getLong(2), t, "", cur.getString(4) ?: "")
                    if (p.start <= now) { if (nowL.size < 100 && seen.add(c.url)) nowL.add(c to p) }
                    else if (later.size < 80 && got.add(c.url + "|" + p.start)) later.add(c to p)
                }
            }
        } catch (e: Exception) {}
    }
    return SearchRes(nowL, nCh, later, dayLabels(later))
}

fun dayLabels(l: List<Pair<Ch, Prog>>): List<String> {
    val today = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }.timeInMillis
    val df = java.text.SimpleDateFormat("EEE"); val hm = java.text.SimpleDateFormat("HH:mm")
    return l.map { val dn = Math.floorDiv(it.second.start - today, 86_400_000L); (if (dn == 0L) "Today" else if (dn == 1L) "Tomorrow" else df.format(it.second.start)) + " " + hm.format(it.second.start) }
}

fun Ui.searchMic(): Boolean = srch.mic ?: (a.packageManager.queryIntentActivities(android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0).isNotEmpty()).also { srch.mic = it }

fun Ui.openSearch() {
    val s = srch; bkF = false; s.acc = null
    if (s.key < 0) s.key = searchKeys(s.num, searchMic()).indexOf("a")
    show(17); invalidate()   // the guide scope changes only when a result is watched or opened (changing it empties the guide cache)
    val v = a.epgVer; if (v != s.idxVer) s.work.execute { val ix = a.searchIndex(); post { s.idx = ix; s.idxVer = v; if (s.q.isNotBlank()) suggest() } }   // read again only when the guide changed
    if (s.done.isNotEmpty() && SystemClock.uptimeMillis() - s.doneAt > 60_000) runSearch(true)   // "on now" a minute old or more: fresh, same place
}

// every letter from the 2nd: suggestions at once (in memory, off the UI thread), the search itself 0.3 s after the last letter
fun Ui.suggest() {
    val s = srch; val q = s.q; val g = ++s.sgGen; val ix = s.idx
    s.sgRun?.let { removeCallbacks(it) }; s.autoRun?.let { removeCallbacks(it) }
    if (q.trim().length < 2) { s.sugg = emptyList(); s.done = "";   // nothing is suggested or searched until 2 letters ("f1" works)
        s.res = SearchRes(emptyList(), 0, emptyList()); s.busy = false; ++s.gen
        if (s.foc == 3 || s.foc == 4) s.foc = 0; invalidate(); return }
    val run = Runnable { s.work.execute { if (g != s.sgGen) return@execute; val r = searchSuggest(ix, q) { g != s.sgGen }
        post { if (g == s.sgGen) { s.sugg = r; s.sugSel = 0; if (s.foc == 3 && r.isEmpty()) s.foc = 0; invalidate() } } } }
    s.sgRun = run; postDelayed(run, 80)
    val ar = Runnable { if (s.q.trim() != s.done) runSearch(jump = false) }; s.autoRun = ar; postDelayed(ar, 300)
}

// result sections: 4 CHANNELS (the first nCh of now), 1 ON NOW (the shows after them), 2 LATER
fun hasSection(r: SearchRes, f: Int) = when (f) { 4 -> r.nCh > 0; 1 -> r.now.size > r.nCh; 2 -> r.later.isNotEmpty(); else -> true }
fun firstSection(r: SearchRes) = listOf(4, 1, 2).firstOrNull { hasSection(r, it) } ?: 0
fun Ui.shownRes(): SearchRes = srch.res   // empty until something is typed

// the search: by itself after typing, a picked suggestion, voice, or reopening the page (keep = same place)
fun Ui.runSearch(keep: Boolean = false, jump: Boolean = true) {
    val s = srch; val q = (if (keep) s.done else s.q).trim(); val g = ++s.gen
    if (q.isEmpty()) return
    s.busy = true; s.done = q; s.doneAt = SystemClock.uptimeMillis(); invalidate()
    s.find.execute { if (g != s.gen) return@execute; val res = a.searchData(q); post { if (g == s.gen) {
        s.res = res; s.busy = false
        if (!keep) { s.chSel = 0; s.nowSel = 0; s.laterSel = 0; s.hOff.fill(0f); if (jump) s.foc = firstSection(res) }
        s.chSel = s.chSel.coerceIn(0, maxOf(0, res.nCh - 1)); s.nowSel = s.nowSel.coerceIn(0, maxOf(0, res.now.size - res.nCh - 1)); s.laterSel = s.laterSel.coerceIn(0, maxOf(0, res.later.lastIndex))
        if (s.foc != 0 && s.foc != 3 && !hasSection(res, s.foc)) s.foc = 0
        invalidate() } } }
}

fun Ui.searchType(k: String) {
    val s = srch; val mic = searchMic()
    when (k) {
        "del" -> s.q = s.q.dropLast(1)
        "SPACE" -> if (s.q.isNotEmpty() && !s.q.endsWith(" ")) s.q += " "
        "mic" -> { dictate(); return }
        "123", "ABC" -> { s.num = !s.num; s.key = searchKeys(s.num, mic).indexOf(if (s.num) "ABC" else "123"); invalidate(); return }
        else -> if (s.q.length < 40) s.q += k
    }
    suggest()
}

// voice typing: Google's speech screen listens through the remote's mic
fun Ui.dictate() {
    val i = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Say a channel or a show")
    try { a.startActivityForResult(i, REQ_SPEECH) } catch (e: Exception) { srch.mic = false; a.flash("Voice typing is not available on this TV", 3000); invalidate() }
}
fun Ui.searchSpoken(t: String) {
    val s = srch; if (mode != 17) return
    s.q = t.lowercase().take(40); s.acc = null; s.foc = 0; suggest(); s.autoRun?.let { removeCallbacks(it) }; if (s.q.trim().length >= 2) runSearch(jump = false)
}

// watch a result full screen; nothing stays on the picture
fun Ui.searchWatch(ch: Ch) {
    val s = srch; val before = if (s.started) s.before else null; s.started = false   // watching: the channel from before is not put back
    close()
    if (ch != a.cur || a.catchupProg != null || a.errShown) a.play(ch)
    if (before != null && before.url != ch.url) a.prev = before   // BACK = the channel from before Search, not the last preview
    a.showListOf(ch); s.pvWant = ch.url   // in Search again it is still the one in the window
}
// leaving Search without watching: back to the channel from before the previews
fun Ui.searchLeave() {
    val s = srch; s.pvWant = null; a.player.volume = 1f
    if (!s.started) return; s.started = false
    val b = s.before
    if (b == null) { a.player.stop(); a.player.clearMediaItems() } else if (b.url != a.cur?.url || a.errShown) { a.play(b); a.prev = s.prevBefore }
}
fun Ui.deadSet(): HashSet<String> { val s = srch; if (!s.deadLoaded) { s.dead.addAll(a.prefs.getStringSet("deadCh", emptySet())!!); s.deadLoaded = true }; return s.dead }
fun Ui.saveDead() { a.prefs.edit().putStringSet("deadCh", HashSet(srch.dead)).apply() }
fun Ui.searchDead(ch: Ch?) { if (ch == null) return; if (deadSet().add(ch.url)) saveDead(); invalidate() }
// the card with the focus, when it is on now (Channels or On Now)
fun Ui.searchFocCh(): Pair<Ch, Prog?>? { val s = srch; val r = shownRes(); return when (s.foc) { 4 -> r.now.getOrNull(s.chSel); 1 -> r.now.getOrNull(r.nCh + s.nowSel); else -> null } }
// OK on a card that is on now: the first OK plays it in the preview window, the next one watches it full screen
fun Ui.searchPick(ch: Ch) {
    val s = srch
    if (s.pvWant == ch.url && a.cur?.url == ch.url && !a.errShown) { searchWatch(ch); return }
    if (!s.started) { s.started = true; s.before = a.cur; s.prevBefore = a.prev }
    s.pvWant = ch.url; s.pvAt = SystemClock.uptimeMillis()
    if (ch != a.cur || a.catchupProg != null || a.errShown || a.frameUrl != ch.url) a.play(ch)
    invalidate()
}

// Program Info from a result reads every playlist's guide
fun Ui.scopeAll() { a.searching = true; a.syncScope() }

// OK let go (hold = Program Info); on a letter holding OK pops up its other forms, on ⌫ it clears everything
fun Ui.searchOk(hold: Boolean) {
    val s = srch; val r = shownRes()
    s.acc?.let { l -> if (!hold) { val v = l.getOrNull(s.accSel); s.acc = null; if (v != null) searchType(v) }; invalidate(); return }
    when (s.foc) {
        0 -> { val k = searchKeys(s.num, searchMic()).getOrNull(s.key) ?: return
            if (hold) { if (k == "del") { s.q = ""; suggest() } else ACCENTS[k]?.let { s.acc = listOf(k) + it.map { c -> c.toString() }; s.accSel = 1 } }
            else searchType(k) }
        4 -> r.now.getOrNull(s.chSel)?.let { (ch, pr) -> if (hold) { scopeAll(); a.pvBox(null); openInfo(ch, pr) } else searchPick(ch) }
        1 -> r.now.getOrNull(r.nCh + s.nowSel)?.let { (ch, pr) -> if (hold) { scopeAll(); a.pvBox(null); openInfo(ch, pr) } else searchPick(ch) }
        2 -> r.later.getOrNull(s.laterSel)?.let { (ch, pr) -> if (hold) { scopeAll(); a.pvBox(null); openInfo(ch, pr) } else a.toggleRem(ch, pr) }
        3 -> s.sugg.getOrNull(s.sugSel)?.let { if (!hold) { s.q = it.lowercase(); s.sugg = emptyList(); s.sgGen++; s.sgRun?.let { r0 -> removeCallbacks(r0) };   // a lookup still running must not refill the list
                s.autoRun?.let { r0 -> removeCallbacks(r0) }; runSearch() } }   // the words become the suggestion, then to the results
    }
    invalidate()
}

// sideways: holding speeds up (1, then 2, then 3 a step) and stops at the end of the row; a fresh press at the end stays put
fun hStep(cur: Int, d: Int, n: Int, rep: Int = 0) = (cur + d * (1 + minOf(2, rep / 10))).coerceIn(0, maxOf(0, n - 1))

fun Ui.searchKey(k: Int, e: KeyEvent): Boolean {
    val s = srch; val r = shownRes(); val up = k == KeyEvent.KEYCODE_DPAD_UP; val dn = k == KeyEvent.KEYCODE_DPAD_DOWN
    val lf = k == KeyEvent.KEYCODE_DPAD_LEFT; val rt = k == KeyEvent.KEYCODE_DPAD_RIGHT
    val ok = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
    if (bkF) { if (ok) { bkF = false; searchLeave(); a.pvBox(null); a.endSearch(); mode = 3 }; if (dn) bkF = false; return true }   // the ‹ in the header: leaves (BACK there would delete a letter)
    s.acc?.let { l -> when { lf || rt -> s.accSel = hStep(s.accSel, if (lf) -1 else 1, l.size); ok -> searchOk(false); up || dn -> s.acc = null }; return true }
    // remote shortcuts: the mic talks, Play/Pause adds a space, Rewind deletes; a real keyboard types straight in
    when (k) {
        KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_VOICE_ASSIST -> { dictate(); return true }
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_SPACE -> { searchType("SPACE"); return true }
        KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_DEL -> { searchType("del"); return true }
    }
    val ch = e.unicodeChar
    if (!ok && ch > 32 && !Character.isISOControl(ch)) { if (s.q.length < 40) { s.q += Character.toChars(ch).concatToString().lowercase(); suggest() }; return true }
    if (ok) { searchOk(false); return true }
    val hasSug = s.sugg.isNotEmpty() && s.q.isNotBlank()
    val order = listOf(0) + (if (hasSug) listOf(3) else emptyList()) + listOf(4, 1, 2).filter { hasSection(r, it) }
    val at = order.indexOf(s.foc).coerceAtLeast(0)
    when {
        lf || rt -> { val d = if (lf) -1 else 1
            when (s.foc) {
                0 -> s.key = hStep(s.key, d, searchKeys(s.num, searchMic()).size, e.repeatCount)
                3 -> s.sugSel = hStep(s.sugSel, d, s.sugg.size)
                4 -> s.chSel = hStep(s.chSel, d, r.nCh, e.repeatCount)
                1 -> s.nowSel = hStep(s.nowSel, d, r.now.size - r.nCh, e.repeatCount)
                2 -> s.laterSel = hStep(s.laterSel, d, r.later.size, e.repeatCount)
            } }
        dn -> if (at < order.lastIndex && (e.repeatCount == 0 || s.foc >= 3 || s.foc == 1)) s.foc = order[at + 1]   // holding DOWN from the letters stays there
        up -> if (at == 0) bkF = true else if (e.repeatCount == 0 || order[at - 1] != 0 && order[at - 1] != 3) s.foc = order[at - 1]   // holding UP stops at the first shelf
    }
    return true
}

// a card's colour comes from its channel logo: the logo's strongest colour, kept dark enough for white text; grey logos get a neutral grey
fun Ui.tintOf(ch: Ch): Int {
    val s = srch; s.tints[ch.url]?.let { return it }
    val b = a.logoOf(ch) ?: return 0xFF34323A.toInt()   // not loaded yet: ask again next frame
    val sm = try { Bitmap.createScaledBitmap(b, 12, 12, true) } catch (e: Exception) { null }
    var rs = 0f; var gs = 0f; var bs = 0f; var ws = 0f; val hsv = FloatArray(3)
    if (sm != null) for (y in 0 until 12) for (x in 0 until 12) { val px = sm.getPixel(x, y); val al = (px ushr 24) / 255f; if (al < 0.5f) continue
        android.graphics.Color.colorToHSV(px, hsv); val wt = al * hsv[1] * hsv[1] * (0.3f + hsv[2]); rs += ((px shr 16) and 255) * wt; gs += ((px shr 8) and 255) * wt; bs += (px and 255) * wt; ws += wt }
    val out = if (ws < 2f) 0xFF34323A.toInt() else {
        android.graphics.Color.RGBToHSV((rs / ws).toInt(), (gs / ws).toInt(), (bs / ws).toInt(), hsv)
        hsv[1] = hsv[1].coerceIn(0.35f, 0.85f); hsv[2] = hsv[2].coerceIn(0.32f, 0.62f); android.graphics.Color.HSVToColor(hsv) }
    s.tints[ch.url] = out; return out
}
// the card fill: the logo colour at the top fading to near black at the bottom (one shader per colour and height, reused)
fun Ui.cardFill(c: Canvas, x: Float, y: Float, w: Float, h: Float, col: Int, solid: Boolean) {
    if (solid) { rr(c, x, y, w, h, 18 * U, col); return }
    val key = (col.toLong() shl 20) xor h.toLong()
    val g = srch.grads.getOrPut(key) { LinearGradient(0f, 0f, 0f, h, intArrayOf(col, 0xFF1A181E.toInt()), floatArrayOf(0f, 0.95f), Shader.TileMode.CLAMP) }
    c.save(); c.translate(x, y); p.shader = g; c.drawRoundRect(RectF(0f, 0f, w, h), 18 * U, 18 * U, p); p.shader = null; c.restore()
}
// the show's picture filling the card (cropped to fit), darkened toward the bottom so the white title stays readable
val picPath = android.graphics.Path(); val picDst = RectF(); val picSrc = android.graphics.Rect()
fun Ui.cardPic(c: Canvas, b: Bitmap, x: Float, y: Float, w: Float, h: Float) {
    val sc = maxOf(w / b.width, h / b.height); val sw = (w / sc).toInt(); val sh = (h / sc).toInt()
    picSrc.set((b.width - sw) / 2, (b.height - sh) / 2, (b.width + sw) / 2, (b.height + sh) / 2); picDst.set(x, y, x + w, y + h)
    c.save(); picPath.reset(); picPath.addRoundRect(picDst, 18 * U, 18 * U, android.graphics.Path.Direction.CW); c.clipPath(picPath)
    p.shader = null; p.color = -1; p.isFilterBitmap = true; c.drawBitmap(b, picSrc, picDst, p)
    val g = srch.grads.getOrPut(-h.toLong()) { LinearGradient(0f, 0f, 0f, h, intArrayOf(0x33000000, 0, 0xD9000000.toInt()), floatArrayOf(0f, 0.3f, 1f), Shader.TileMode.CLAMP) }
    c.translate(x, y); p.shader = g; c.drawRect(0f, 0f, w, h, p); p.shader = null
    c.restore()
}
val shadowP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt() }

fun Ui.drawSearch(c: Canvas, w: Float, h: Float) {
    val s = srch; val r = shownRes(); val L = 80 * U; val R = w - 80 * U; val now = SystemClock.uptimeMillis(); val mic = searchMic()
    val blank = s.q.isBlank(); val hasSug = s.sugg.isNotEmpty() && !blank
    // results: a fixed band on top (the show, and the preview window at the right), the shelves scroll under it
    val res = s.foc == 4 || s.foc == 1 || s.foc == 2; val BB = 500 * U
    val fc = if (s.foc == 4 || s.foc == 1) searchFocCh() else null; val fu = fc?.first?.url
    val win = RectF(w - 800 * U, 48 * U, w - 80 * U, 453 * U); val dead = deadSet()
    val open = mode == 17   // false while the page slides out after closing: then the picture is full screen again
    val mine = open && fu != null && fu == s.pvWant && a.cur?.url == fu   // the focused card is the one in the window
    val playing = mine && a.frameUrl == fu && !a.errShown
    val failed = mine && a.errShown
    if (open) a.player.volume = if (s.started && fc != null && !mine) 0f else 1f   // looking at another card: the preview keeps going, quietly
    if (mine && !playing && !failed && now - s.pvAt > 15_000) { a.errShown = true; a.player.stop(); fc?.let { searchDead(it.first) } }   // no picture after 15 s: not working
    if (playing && dead.remove(fu)) saveDead()   // it works now
    if (mine && !playing) tick(250)
    if (open) a.pvBox(if (fc != null) win else null)
    if (playing) { holeP.reset(); holeP.fillType = android.graphics.Path.FillType.EVEN_ODD; holeP.addRect(0f, 0f, w, h, android.graphics.Path.Direction.CW)
        holeP.addRoundRect(win, 24 * U, 24 * U, android.graphics.Path.Direction.CW); mat(c, holeP) }   // the picture shows through a hole in the page
    else glass(c, 0f, 0f, w, h, 0f)
    // ---- page layout (page positions; the page scrolls by pageY) ----
    val fy = 150 * U; val ky = 252 * U; val kh = 66 * U; val ruleY = 350 * U
    var y0 = ruleY + 24 * U; val sugY = y0; if (hasSug) y0 += 56 * U + 36 * U else y0 += 18 * U
    val secs = listOf(4, 1, 2).filter { hasSection(r, it) }
    val secY = HashMap<Int, Float>(); var yy = y0
    for (f in secs) { secY[f] = yy; yy += 54 * U + (if (f == 4) 169 * U else 248 * U) + 50 * U + 44 * U }
    // the page follows the focus: at the top for the letters and suggestions, else the focused shelf's title near the top
    val target = when (s.foc) { 0, 3 -> 0f; else -> (secY[s.foc] ?: 0f) - BB - 10 * U }   // results: the focused shelf right under the band
    fun ease(cur: Float, to: Float, dt: Float) = if (!animOn() || Math.abs(to - cur) < 0.5f) to else cur + (to - cur) * (1f - Math.exp((-dt / 0.085f).toDouble()).toFloat())
    val dtP = if (s.pageAt == 0L) 0.016f else minOf(0.05f, (now - s.pageAt) / 1000f)
    s.pageY = ease(s.pageY, target, dtP); var moving = s.pageY != target
    c.save(); if (res) c.clipRect(0f, BB, w, h); c.translate(0f, -s.pageY)
    // ---- back arrow and title: the same header as every other page (Playlists, Settings, ...) ----
    head(c, 36 * U, 28 * U, "Search", 400 * U)
    // ---- the field: big, no box; grey words until you type; blinking caret on the letters ----
    icon(c, "search", L + 28 * U, fy + 36 * U, 58 * U, 0x8CFFFFFF.toInt())
    val tx = L + 84 * U; val tmax = R - tx - 520 * U
    if (blank) txt(c, "Channels, Shows and More", tx, fy + 36 * U, tmax, 56f, 0x61FFFFFF, 600)
    else { var t = s.q; while (t.length > 1 && tw(t, 56f, 600) > tmax) t = t.drop(1); if (t != s.q) t = "…" + t.drop(1)
        txt(c, t, tx, fy + 36 * U, tmax, 56f, WHITE, 600)
        if (s.foc == 0 && s.acc == null && !bkF && (now / 530) % 2 == 0L) rr(c, tx + tw(t, 56f, 600) + 6 * U, fy + 8 * U, 4 * U, 58 * U, 2 * U, WHITE) }
    if (s.foc == 0) tick(530)
    if (s.busy) txt(c, "Searching…", R, fy + 36 * U, 400 * U, 30f, 0x61FFFFFF, 500, Paint.Align.RIGHT)
    else if (mic) { val t = "on the remote to talk"; val tw0 = tw(t, 30f, 500); txt(c, t, R - tw0, fy + 36 * U, tw0 + 4 * U, 30f, 0x61FFFFFF, 500)
        icon(c, "mic", R - tw0 - 26 * U, fy + 36 * U, 32 * U, 0x61FFFFFF); txt(c, "Press", R - tw0 - 52 * U, fy + 36 * U, 120 * U, 30f, 0x61FFFFFF, 500, Paint.Align.RIGHT) }
    // ---- one thin row: mic, 123, SPACE as small pills; the letters light grey; ⌫ at the end. The focused one is a white square ----
    val keys = searchKeys(s.num, mic); val kws = FloatArray(keys.size); val kxs = FloatArray(keys.size)
    run { var x = L + 30 * U
        keys.forEachIndexed { i, k -> val wd = when (k) { "mic" -> 60 * U; "123", "ABC" -> 72 * U; "SPACE" -> 104 * U; "del" -> 80 * U; else -> 52 * U }
            if (k == "del") x = maxOf(x + 20 * U, R - wd - 10 * U); kxs[i] = x; kws[i] = wd; x += wd + (if (k.length > 1 && k != "del") 22 * U else 0f) } }
    val fk = if (s.foc == 0 && !bkF) s.key.coerceIn(0, keys.lastIndex) else -1
    for (i in keys.indices.filter { it != fk } + listOfNotNull(fk.takeIf { it >= 0 })) {
        val k = keys[i]; val x = kxs[i]; val kw = kws[i]; val on = i == fk; val pill = k.length > 1 && k != "del"
        lift(c, on, x, ky, kw, kh, 1.12f) {
            if (on) rr(c, x, ky, kw, kh, 16 * U, FOC) else if (pill) rr(c, x + 4 * U, ky + 15 * U, kw - 8 * U, 36 * U, 8 * U, 0x38FFFFFF)
            val col = if (on) ONF else if (pill) 0xD9FFFFFF.toInt() else 0x9EFFFFFF.toInt()
            when (k) {
                "del" -> icon(c, "delete", x + kw / 2, ky + kh / 2, 36 * U, col)
                "mic" -> icon(c, "mic", x + kw / 2, ky + kh / 2, if (on) 32 * U else 26 * U, col)
                "123", "ABC", "SPACE" -> { tp.letterSpacing = 0.04f; txt(c, k, x + kw / 2, ky + kh / 2, kw, if (on) 22f else 18f, col, 700, Paint.Align.CENTER); tp.letterSpacing = 0f }
                else -> txt(c, k, x + kw / 2, ky + kh / 2, kw + 10 * U, 42f, col, if (on) 500 else 400, Paint.Align.CENTER)
            }
        }
    }
    fill(c, L, ruleY, R - L, 1.5f * U, 0x29FFFFFF)
    // ---- suggestions: plain words; the focused one on a white pill ----
    if (hasSug) {
        val sh = 56 * U; val ws = s.sugg.map { minOf(600 * U, tw(it, 28f, 500) + 48 * U) }; val xs = FloatArray(ws.size); var xx = 0f
        ws.forEachIndexed { i, wd -> xs[i] = xx; xx += wd + 10 * U }
        val sel = s.sugSel.coerceIn(0, ws.lastIndex); val off = maxOf(0f, xs[sel] + ws[sel] - (R - L))
        val fs = if (s.foc == 3) sel else -1
        for (i in s.sugg.indices.filter { it != fs } + listOfNotNull(fs.takeIf { it >= 0 })) {
            val x = L + xs[i] - off; if (x > R || x + ws[i] < L - 20 * U) continue
            val on = i == fs
            lift(c, on, x, sugY, ws[i], sh, 1.06f) {
                if (on) rr(c, x, sugY, ws[i], sh, sh / 2, FOC)
                txt(c, s.sugg[i], x + 24 * U, sugY + sh / 2, ws[i] - 48 * U, 28f, if (on) ONF else 0x9EFFFFFF.toInt(), 500)
            }
        }
    }
    // ---- empty states ----
    if (secs.isEmpty()) {
        if (blank) {   // before typing: one quiet line in the middle of the empty page
            txt(c, "Search channels and shows", w / 2, 590 * U, R - L, 38f, 0x9EFFFFFF.toInt(), 600, Paint.Align.CENTER)
            txt(c, if (mic) "Type a few letters, or press the mic on the remote and say it" else "Type a few letters", w / 2, 640 * U, R - L, 28f, 0x61FFFFFF, 500, Paint.Align.CENTER)
        } else if (s.q.trim().length < 2) txt(c, "Keep typing: results start at 2 letters", w / 2, 590 * U, R - L, 34f, 0x9EFFFFFF.toInt(), 600, Paint.Align.CENTER)
        else if (!s.busy && s.q.trim() == s.done) txt(c, "Nothing found for “${s.done}”", w / 2, 590 * U, R - L, 34f, 0x9EFFFFFF.toInt(), 600, Paint.Align.CENTER)
    }
    // ---- shelves: CHANNELS, ON NOW, LATER. Cards grow and lift when focused (no white fill, like Apple TV) ----
    val dtH = if (s.hAt == 0L) 0.016f else minOf(0.05f, (now - s.hAt) / 1000f)
    val curKey = when (s.foc) { 4 -> "4:${s.chSel}"; 1 -> "1:${s.nowSel}"; 2 -> "2:${s.laterSel}"; else -> "" }
    if (curKey != s.cardKey) { s.cardPrev = s.cardKey; s.cardKey = curKey; s.cardAt = now }
    val ct = if (animOn()) ((now - s.cardAt) / 200f).coerceIn(0f, 1f) else 1f; val ce = 1f - (1f - ct) * (1f - ct) * (1f - ct)
    if (ct < 1f) moving = true
    for ((si, f) in secs.withIndex()) {
        val ty = secY[f] ?: continue; if (ty - s.pageY > h || ty - s.pageY < -500 * U) continue
        val isCh = f == 4; val cw = if (isCh) 300 * U else 440 * U; val chh = if (isCh) 169 * U else 248 * U; val pitch = cw + 40 * U
        val n = when (f) { 4 -> r.nCh; 1 -> r.now.size - r.nCh; else -> r.later.size }
        val sel = when (f) { 4 -> s.chSel; 1 -> s.nowSel; else -> s.laterSel }.coerceIn(0, maxOf(0, n - 1))
        val title = when (f) { 4 -> "Channels"; 1 -> "On Now"; else -> "Later" }
        txt(c, title, L, ty + 18 * U, R - L, 32f, 0x9EFFFFFF.toInt(), 600)
        txt(c, "$n", L + tw(title, 32f, 600) + 14 * U, ty + 18 * U, 200 * U, 32f, 0x59FFFFFF, 500)
        val hi = when (f) { 4 -> 0; 1 -> 1; else -> 2 }
        val want = maxOf(0f, sel * pitch + cw - (R - L - 0.4f * pitch)).coerceAtMost(maxOf(0f, n * pitch - 40 * U - (R - L)))
        s.hOff[hi] = ease(s.hOff[hi], want, dtH); if (s.hOff[hi] != want) moving = true
        val cy = ty + 54 * U; val shelfOn = s.foc == f
        val order = (0 until n).filter { it != sel || !shelfOn } + (if (shelfOn && n > 0) listOf(sel) else emptyList())
        for (i in order) {
            val x = L + i * pitch - s.hOff[hi]; if (x > w + 20 * U || x + cw < -20 * U) continue
            val key = "$f:$i"; val gr = if (key == s.cardKey) ce else if (key == s.cardPrev) 1f - ce else 0f
            val (chn, pr) = when (f) { 4 -> r.now[i]; 1 -> r.now[r.nCh + i]; else -> r.later[i].let { it.first to it.second } }
            val sc = 1f + 0.09f * gr
            c.save(); c.scale(sc, sc, x + cw / 2, cy + chh / 2)
            if (gr > 0.01f) { shadowP.alpha = (110 * gr).toInt(); shadowP.setShadowLayer(34 * U * gr, 0f, 18 * U * gr, (0x8C * gr).toInt() shl 24)
                c.drawRoundRect(RectF(x + 6 * U, cy + 6 * U, x + cw - 6 * U, cy + chh - 6 * U), 18 * U, 18 * U, shadowP) }
            val tint = tintOf(chn); val dd = f != 2 && chn.url in dead; val dim = dd && gr < 0.5f
            if (dim) c.saveLayerAlpha(x, cy, x + cw, cy + chh, 97)   // tried and not working: greyed
            if (isCh) { val th = a.thumbOf(chn)
                if (th != null) { cardPic(c, th, x, cy, cw, chh); rr(c, x + 14 * U, cy + 14 * U, 84 * U, 40 * U, 9 * U, 0x4D000000); drawLogo(c, chn, x + 14 * U, cy + 14 * U, 84 * U, 40 * U) }   // a still of the channel, its logo small
                else { cardFill(c, x, cy, cw, chh, tint, true); drawLogo(c, chn, x + cw * 0.1f, cy + chh * 0.12f, cw * 0.8f, chh * 0.76f) }
                if (dd) deadTag(c, x + cw - 14 * U, cy + 32 * U) }
            else {
                val pic = (if (f == 1) a.thumbOf(chn) else null) ?: pr?.img?.let { a.picOf(it) }   // on now: the channel's own still first (what it really shows), else the guide's
                if (pic != null) cardPic(c, pic, x, cy, cw, chh) else cardFill(c, x, cy, cw, chh, tint, false)
                if (f == 1) {   // on now: small logo badge, LIVE, the show, how far it is
                    rr(c, x + 18 * U, cy + 18 * U, 100 * U, 48 * U, 10 * U, 0x4D000000); drawLogo(c, chn, x + 18 * U, cy + 18 * U, 100 * U, 48 * U)
                    if (dd) deadTag(c, x + cw - 18 * U, cy + 42 * U) else live(c, x + cw - 92 * U, cy + 42 * U, 17f)
                    pr?.let { val ls = split2(it.title, cw - 44 * U, 30f, 700).take(2); ls.forEachIndexed { li, l -> txt(c, l, x + 22 * U, cy + chh - 50 * U - (ls.size - 1 - li) * 36 * U, cw - 44 * U, 30f, WHITE, 700) }
                        progress(c, x + 22 * U, cy + chh - 22 * U, cw - 44 * U, it, 5f) }
                } else {   // later: day and time, a bell when a reminder is set, the show
                    val lab = r.days.getOrElse(i) { "" }; val lw = tw(lab, 22f, 700) + 24 * U
                    rr(c, x + 18 * U, cy + 18 * U, lw, 42 * U, 10 * U, 0x61000000); txt(c, lab, x + 30 * U, cy + 39 * U, lw, 22f, WHITE, 700)
                    if (pr != null && a.hasRem(chn, pr)) icon(c, "bell", x + cw - 38 * U, cy + 39 * U, 28 * U, WHITE)
                    pr?.let { val ls = split2(it.title, cw - 44 * U, 30f, 700).take(2); ls.forEachIndexed { li, l -> txt(c, l, x + 22 * U, cy + chh - 34 * U - (ls.size - 1 - li) * 36 * U, cw - 44 * U, 30f, WHITE, 700) } }
                }
            }
            if (dim) c.restore()
            if (gr > 0.01f) {   // focus ring: the theme colour just outside the card, fading in with the lift (the zoom alone was too subtle)
                val o = 5 * U; accentPaint(p); p.alpha = (255 * gr).toInt(); p.style = Paint.Style.STROKE; p.strokeWidth = 5 * U   // the theme's accent (New Year: the gold)
                c.drawRoundRect(RectF(x - o, cy - o, x + cw + o, cy + chh + o), 22 * U, 22 * U, p); p.style = Paint.Style.FILL; p.shader = null; p.alpha = 255 }
            c.restore()
            // caption under the card: brighter and a little lower when focused
            val capY = cy + chh + 30 * U + 12 * U * gr; val capC = if (gr > 0.5f) WHITE else if (f != 2 && chn.url in dead) 0x59FFFFFF else 0x9EFFFFFF.toInt()
            val cap = when (f) { 4 -> chn.name + (if (gr > 0.5f && pr != null) "  ·  ${pr.title}" else ""); 1 -> "${chn.name}  ·  ${pr?.let { minLeft(it) } ?: 0} min left"; else -> chn.name }
            txt(c, cap, x, capY, cw, 24f, capC, 500)
        }
    }
    s.hAt = if (moving) now else 0L; s.pageAt = if (s.pageY != target) now else 0L
    // the accented letters pop-up, above the held letter
    s.acc?.let { l ->
        val kx = kxs.getOrElse(s.key) { L } + kws.getOrElse(s.key) { 52 * U } / 2; val cwd = 72 * U; val pw = l.size * cwd + 24 * U
        val px = (kx - pw / 2).coerceIn(L, R - pw); val py = ky - 112 * U
        shadowP.alpha = 160; shadowP.setShadowLayer(36 * U, 0f, 16 * U, 0x8C000000.toInt()); c.drawRoundRect(RectF(px, py, px + pw, py + 96 * U), 24 * U, 24 * U, shadowP)
        rr(c, px, py, pw, 96 * U, 24 * U, 0xF73A3640.toInt())
        l.forEachIndexed { i, v -> val x = px + 12 * U + i * cwd; val on = i == s.accSel
            lift(c, on, x + 3 * U, py + 15 * U, cwd - 6 * U, 66 * U, 1.12f) {
                if (on) rr(c, x + 3 * U, py + 15 * U, cwd - 6 * U, 66 * U, 16 * U, FOC)
                txt(c, v, x + cwd / 2, py + 48 * U, cwd, 42f, if (on) ONF else WHITE, 500, Paint.Align.CENTER) } }
    }
    c.restore()
    if (res) searchBand(c, w, fc, win, playing, failed, mine)
    if (moving || s.pageY != target) postInvalidateOnAnimation()
}
val holeP = android.graphics.Path()
fun Ui.deadTag(c: Canvas, right: Float, cy: Float) { val t = "NOT WORKING"; val tw0 = tw(t, 17f, 700) + 20 * U
    rr(c, right - tw0, cy - 17 * U, tw0, 34 * U, 7 * U, 0x99000000.toInt()); txt(c, t, right - tw0 + 10 * U, cy, tw0, 17f, WHITE, 700) }
// the band over the results: the focused show on the left, the preview window on the right (Later: no window, nothing to play yet)
fun Ui.searchBand(c: Canvas, w: Float, fc: Pair<Ch, Prog?>?, win: RectF, playing: Boolean, failed: Boolean, mine: Boolean) {
    val s = srch; val r = shownRes(); val L = 80 * U
    head(c, 36 * U, 28 * U, "Search “${s.done}”", 960 * U)
    val later = if (s.foc == 2) r.later.getOrNull(s.laterSel) else null
    val ch = fc?.first ?: later?.first ?: return; val pr = fc?.second ?: later?.second
    val iw = (if (fc != null) win.left - 60 * U else w - 80 * U) - L
    var y = 196 * U
    rr(c, L, y - 24 * U, 100 * U, 48 * U, 10 * U, 0x4D000000); drawLogo(c, ch, L, y - 24 * U, 100 * U, 48 * U)
    txt(c, if (later != null) "${r.days.getOrElse(s.laterSel) { "" }}  ·  ${ch.name}" else ch.name, L + 116 * U, y, iw - 116 * U, 26f, SEC, 500)
    y += 70 * U
    split2(pr?.title ?: ch.name, iw, 52f, 700).take(2).forEach { txt(c, it, L, y, iw, 52f, WHITE, 700); y += 62 * U }
    if (fc != null) {
        if (pr != null) { val lw = live(c, L, y, 17f); val t = "${minLeft(pr)} min left"; txt(c, t, L + lw + 14 * U, y, 300 * U, 24f, SEC)
            progress(c, L + lw + 34 * U + tw(t, 24f), y - 3 * U, 260 * U, pr, 6f) }
        else txt(c, "No TV guide for this channel", L, y, iw, 24f, SEC)
        keyHint(c, listOf("OK" to (if (mine && !failed) "Watch full screen" else "Preview"), "Hold OK" to "Program Info"), L, y + 62 * U, iw)
    } else if (later != null) keyHint(c, listOf("OK" to (if (a.hasRem(later.first, later.second)) "Remove Reminder" else "Remind Me"), "Hold OK" to "Program Info"), L, y, iw)
    if (fc == null) return
    // until the picture plays (or when it fails) a dark box in the window says what is going on
    if (playing) { live(c, win.right - 20 * U - tw("LIVE", 17f, 700) - 18 * U, win.top + 34 * U, 17f); return }
    if (!mine) {   // not playing here: the card's picture (or its logo on its colour), and what OK does
        val pic = a.thumbOf(ch) ?: pr?.img?.let { a.picOf(it) }
        if (pic != null) cardPic(c, pic, win.left, win.top, win.width(), win.height())
        else { cardFill(c, win.left, win.top, win.width(), win.height(), tintOf(ch), false); drawLogo(c, ch, win.left + win.width() * 0.3f, win.top + win.height() * 0.3f, win.width() * 0.4f, win.height() * 0.4f) }
        if (ch.url in deadSet()) deadTag(c, win.right - 20 * U, win.top + 34 * U)
        val hl = listOf("OK" to "Preview"); val hw = keyHintW(hl); rr(c, win.left + 20 * U, win.bottom - 66 * U, hw + 28 * U, 46 * U, 23 * U, 0x99000000.toInt())
        keyHint(c, hl, win.left + 34 * U, win.bottom - 43 * U, hw + 4 * U); return }
    rr(c, win.left, win.top, win.width(), win.height(), 24 * U, 0xFF0D0D10.toInt())
    val cx = win.centerX(); val cy = win.centerY(); val mw = win.width() - 60 * U; val was = ch.url in deadSet()
    if (failed) { txt(c, "This channel isn't working", cx, cy - 18 * U, mw, 30f, WHITE, 600, Paint.Align.CENTER)
        txt(c, "Marked on its card  ·  try the next one", cx, cy + 26 * U, mw, 23f, SEC, 400, Paint.Align.CENTER) }
    else { txt(c, "Opening ${ch.name}…", cx, cy - (if (was) 18 else 0) * U, mw, 26f, SEC, 500, Paint.Align.CENTER)
        if (was) txt(c, "It didn't work last time", cx, cy + 26 * U, mw, 23f, 0x61FFFFFF, 400, Paint.Align.CENTER) }
}
