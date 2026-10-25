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

// favorites order: sort mode, group order (by the M3U group-title) and the order channels were added in
fun MainActivity.loadFavPrefs() {
    favSort = prefs.getInt("favSort", 0).coerceIn(0, FAV_SORTS.lastIndex)
    favInner = prefs.getInt("favInner", 2).coerceIn(0, FAV_INNER.lastIndex)
    favSeq.clear(); favGroupOrder.clear()
    try { val a = JSONArray(prefs.getString("favSeq", "[]")); for (k in 0 until a.length()) { val u = a.getString(k); if (u in favs && u !in favSeq) favSeq.add(u) } } catch (e: Exception) {}
    val seen = HashSet<String>(favSeq); favs.filter { it !in seen }.sorted().forEach { favSeq.add(it) }   // favorites from before this feature: no known order, kept sorted
    try { val a = JSONArray(prefs.getString("favGroups", "[]")); for (k in 0 until a.length()) favGroupOrder.add(a.getString(k)) } catch (e: Exception) {}
    saveFavSeq()
}
fun MainActivity.saveFavSeq() { prefs.edit().putString("favSeq", JSONArray(favSeq).toString()).apply() }
fun MainActivity.saveFavGroups() { prefs.edit().putString("favGroups", JSONArray(favGroupOrder).toString()).apply() }

fun MainActivity.toggleFav(u: String) {
    toggle(favs, "favs", u)
    if (u in favs) { if (u !in favSeq) favSeq.add(u) } else favSeq.remove(u)
    saveFavSeq()
}

// a favorite's group = the first group-title of its M3U entry ("News;Finnish" -> "News"), matched without case; no group = "Other"
fun MainActivity.favKey(c: Ch): String = c.group.substringBefore(';').trim().ifEmpty { "Other" }
fun MainActivity.favKeyL(c: Ch): String = favKey(c).lowercase()

// A to Z key: leading symbols and a leading "The" are ignored
fun MainActivity.favSortName(c: Ch): String {
    var s = c.name.trim().dropWhile { !it.isLetterOrDigit() }
    if (s.startsWith("the ", true)) s = s.substring(4)
    return s
}


// 0 = order added, 1 = channel number (playlist order first, then number), 2 = A to Z
fun MainActivity.innerCmp(mode: Int): Comparator<Ch> {
    val sp = HashMap<String, Int>(); favSeq.forEachIndexed { i, u -> sp[u] = i }
    val si = HashMap<String, Int>(); sources.forEachIndexed { i, s -> si[s.id] = i }
    return when (mode) {
        0 -> Comparator<Ch> { x, y -> (sp[x.url] ?: Int.MAX_VALUE).compareTo(sp[y.url] ?: Int.MAX_VALUE) }
        1 -> Comparator<Ch> { x, y -> val d = (si[x.src] ?: 0).compareTo(si[y.src] ?: 0); if (d != 0) d else x.num.compareTo(y.num) }
        else -> Comparator<Ch> { x, y -> coll.compare(favSortName(x), favSortName(y)) }
    }
}

// the groups that exist among these favorites, in your saved order; new groups go at the end, "Other" is always last (key to display name)
fun MainActivity.favGroupsOrdered(l: List<Ch>): List<Pair<String, String>> {
    val seen = LinkedHashMap<String, String>()
    for (c in l) { val k = favKeyL(c); if (k !in seen) seen[k] = favKey(c) }
    val ks = ArrayList<String>()
    for (k in favGroupOrder) if (k in seen && k != "other" && k !in ks) ks.add(k)
    for (k in seen.keys) if (k !in ks && k != "other") ks.add(k)
    if ("other" in seen) ks.add("other")
    return ks.map { it to seen[it]!! }
}
fun MainActivity.favGroupNames(): List<Pair<String, String>> = favGroupsOrdered(favRaw())

fun MainActivity.sortFav(l: List<Ch>): List<Ch> = when (favSort) {
    0 -> {
        val pos = HashMap<String, Int>(); favGroupsOrdered(l).forEachIndexed { i, g -> pos[g.first] = i }
        val byGroup = Comparator<Ch> { x, y -> (pos[favKeyL(x)] ?: 0).compareTo(pos[favKeyL(y)] ?: 0) }
        l.sortedWith(byGroup.then(innerCmp(favInner)))
    }
    1 -> l.sortedWith(innerCmp(0))
    2 -> l.sortedWith(innerCmp(1))
    3 -> l.sortedWith(innerCmp(2))
    else -> { val ic = innerCmp(2); l.sortedWith(Comparator<Ch> { x, y -> ic.compare(y, x) }) }
}
fun MainActivity.favChannels(): List<Ch> = sortFav(favRaw())

// keys = the groups in their new order; groups that are not among the favorites right now keep their saved place after them
fun MainActivity.setFavGroupOrder(keys: List<String>) {
    val k2 = keys.map { it.lowercase() }.filter { it != "other" }.distinct()
    val rest = favGroupOrder.filter { it !in k2 }
    favGroupOrder.clear(); favGroupOrder.addAll(k2 + rest); saveFavGroups()
}
fun MainActivity.moveFavGroup(from: Int, to: Int) {
    val ks = favGroupNames().map { it.first }.filter { it != "other" }.toMutableList()
    if (from !in ks.indices || to !in ks.indices) return
    ks.add(to, ks.removeAt(from))
    setFavGroupOrder(ks); applyFavSort()
}
// the sort setting or the group order changed: rebuild the list if the favorites are on screen
fun MainActivity.applyFavSort() { if (grp == 0 && !searching) selectGroup(0, true) }

fun MainActivity.selectGroup(i: Int, keep: Boolean = false) {
    searching = false; grp = i.coerceIn(0, groups.lastIndex); prefs.edit().putString("grp", groups[grp]).apply(); val v = vis()
    shown = when (grp) { 0 -> favChannels(); 1 -> v; else -> v.filter { it.group == groups[grp] } }
    syncScope()
    ui.reset(keep)
}
