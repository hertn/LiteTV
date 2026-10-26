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

const val PORT = 8765
const val UI_ALPHA = 0.98f   // 1.0 = solid; lower = more see-through
const val REVERSED = true   // Telia style: higher channel numbers above, UP = higher. Set false for normal order.
const val ROW_SCALE = 0.95f // 1.0 = original layout; lower = rows slightly shorter, so the options bar, info box and chips sit higher (everything stays aligned)

class MainActivity : Activity() {
    lateinit var root: FrameLayout; lateinit var pv: PlayerView; lateinit var osd: TextView; lateinit var ui: Ui; lateinit var banner: Banner; lateinit var msg: TextView; lateinit var spin: ProgressBar
    lateinit var player: ExoPlayer; lateinit var prefs: SharedPreferences
    lateinit var catchupBar: CatchupBar
    var channels = listOf<Ch>(); var shown = listOf<Ch>(); var groups = listOf("★ Favorites", "All")
    var sources = listOf<Src>(); var epgs = listOf<Epg>(); var act = 0; var grp = 1
    var prev: Ch? = null; var cur: Ch? = null; var numBuf = ""; var arMode = 0; var showHidden = false
    val hidden = HashSet<String>(); val favs = HashSet<String>(); var searching = false
    val favSeq = java.util.concurrent.CopyOnWriteArrayList<String>()          // favorite urls in the order they were added
    val favGroupOrder = java.util.concurrent.CopyOnWriteArrayList<String>()   // your order of the favorite groups (lower-case group names)
    var favSort = 0; var favInner = 2                                          // favorites sort mode, and the sort inside a group (Custom groups mode)
    val FONT_NAMES = arrayOf("Smaller", "Small", "Normal", "Large"); val FONT_SCALES = floatArrayOf(0.8f, 0.9f, 1f, 1.12f)
    var fontIdx = 1                                                            // text size on the TV screens (default Small)
    val fontScale: Float get() = FONT_SCALES[fontIdx]
    val FAV_SORTS = arrayOf("Custom groups", "Order added", "Channel number", "A to Z", "Z to A")
    val FAV_INNER = arrayOf("Order added", "Channel number", "A to Z")
    var errShown = false
    val arNames = arrayOf("Fit", "Zoom", "Stretch")
    // a stream that fails is retried a few times with a growing pause, then left alone until you pick the channel again
    var retryN = 0; val MAX_RETRY = 5
    // the last problems since the app started (newest first), shown in Settings > Error log
    val errLog = java.util.concurrent.CopyOnWriteArrayList<String>()
    fun logErr(m: String) {
        synchronized(errLog) {
            errLog.add(0, java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.US).format(System.currentTimeMillis()) + "  " + m)
            while (errLog.size > 30) errLog.removeAt(errLog.size - 1)
        }
        try { ui.postInvalidate() } catch (e: Exception) {}
    }
    fun hostOf(u: String): String = try { URL(u).host } catch (e: Exception) { "?" }   // only the host is logged: playlist urls can contain a password
    // buffer size: how much of a channel is loaded ahead (bigger = fewer stutters, slower channel start); changing it builds a new player when you leave Settings
    val BUF_NAMES = arrayOf("Small", "Normal", "Large")
    var bufIdx = 0; var bufDirty = false
    var plDays = 7   // playlists are downloaded again when their file is older than this many days (0 = only by hand)
    val retryRun = Runnable { if (cur != null) player.prepare() }
    // a spinner in the middle of the screen shows when a channel is still buffering after a short moment
    var loadingShown = false
    val loadRun = Runnable { if (player.playbackState == Player.STATE_BUFFERING && !errShown) { loadingShown = true; spin.visibility = View.VISIBLE } }
    fun hideLoading() { loadingShown = false; spin.visibility = View.GONE }
    // stream errors are shown in the middle of the screen
    val hideMsg = Runnable { msg.visibility = View.GONE }
    fun flashMid(t: String, ms: Long) { msg.text = t; msg.visibility = View.VISIBLE; msg.removeCallbacks(hideMsg); msg.postDelayed(hideMsg, ms) }
    fun clearMid() { msg.removeCallbacks(hideMsg); msg.visibility = View.GONE }
    // sleep timer: closes the app after the chosen time; a warning shows 1 minute before and any button cancels it
    var sleepMin = 0; var sleepWarned = false
    val sleepWarn = Runnable { sleepWarned = true; flash("Sleep timer: closing in 1 minute. Press any button to cancel", 60000) }
    val sleepRun = Runnable { if (sleepMin > 0) exitApp() }
    fun setSleep(m: Int) {
        pv.removeCallbacks(sleepWarn); pv.removeCallbacks(sleepRun); sleepWarned = false; sleepMin = m
        if (m > 0) { pv.postDelayed(sleepWarn, m * 60_000L - 60_000L); pv.postDelayed(sleepRun, m * 60_000L); flash("Sleep timer: $m min", 2000) }
        else flash("Sleep timer off", 2000)
    }
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
    val progCache = android.util.LruCache<String, List<Prog>>(60)   // channel id -> its programmes (read from the database on first use)
    val descCache = android.util.LruCache<String, String>(40)       // descriptions of far-away programmes, read on demand
    val keyCache = java.util.concurrent.ConcurrentHashMap<String, List<String>>()   // channel id -> the guide keys it can be found under
    val hitKey = java.util.concurrent.ConcurrentHashMap<String, String>()           // channel id -> the guide key that really had programmes
    @Volatile var epgBusy = false
    val epgJobs = java.util.concurrent.atomic.AtomicInteger()   // how many guide updates are running right now (0 = idle)
    @Volatile var epgNow = ""; @Volatile var epgCnt = 0; @Volatile var epgDoneAt = 0L; @Volatile var epgFailed = false   // what Settings shows under "Update EPG now"
    @Volatile var endCache = 0L; @Volatile var endVer = -1
    var exiting = false
    val loading: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet<String>())
    val failed: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet<String>())
    val pool: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newFixedThreadPool(2)
    val hideOsd = Runnable { osd.visibility = View.GONE }

    // the app version shown at the bottom of Settings; read from the installed app, so it always matches versionName in build.gradle.kts
    val verLabel: String by lazy { try { "v" + packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "" } }

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
        bufIdx = prefs.getInt("buf", 0).coerceIn(0, BUF_NAMES.lastIndex); plDays = prefs.getInt("plD", 7).coerceIn(0, 60)
        Thread { try { epgDb.isOpen } catch (e: Exception) {} }.start()   // open the guide database in the background
        seasonOn = prefs.getBoolean("season", true)
        favs.addAll(prefs.getStringSet("favs", emptySet())!!); hidden.addAll(prefs.getStringSet("hidden", emptySet())!!)
        loadFavPrefs(); fontIdx = prefs.getInt("fontSize", 1).coerceIn(0, FONT_NAMES.lastIndex)
        root = FrameLayout(this); root.setBackgroundColor(C_BG)
        pv = PlayerView(this).apply { useController = false; keepScreenOn = true }; root.addView(pv)
        osd = TextView(this).apply { setTextColor(-1); textSize = 20f; setPadding(40, 20, 40, 20); setBackgroundColor(C_BG); visibility = View.GONE }
        root.addView(osd, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        msg = TextView(this).apply {   // stream errors: in the middle of the screen
            setTextColor(-1); textSize = 22f; gravity = Gravity.CENTER; setPadding(48, 28, 48, 28); setBackgroundColor(C_BG)
            maxWidth = (resources.displayMetrics.widthPixels * 0.7).toInt(); visibility = View.GONE
        }
        root.addView(msg, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        spin = ProgressBar(this).apply { isIndeterminate = true; indeterminateTintList = android.content.res.ColorStateList.valueOf(-1); visibility = View.GONE }   // buffering circle, no text
        val spinSize = (72 * resources.displayMetrics.density).toInt()
        root.addView(spin, FrameLayout.LayoutParams(spinSize, spinSize, Gravity.CENTER))
        banner = Banner(this, this).apply { visibility = View.GONE }; root.addView(banner, FrameLayout.LayoutParams(-1, -1))
        catchupBar = CatchupBar(this, this).apply { visibility = View.GONE }; root.addView(catchupBar, FrameLayout.LayoutParams(-1, -1))
        ui = Ui(this, this).apply { visibility = View.GONE }; root.addView(ui, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        player = makePlayer(); pv.player = player
        sources = readSrcs(); epgs = readEpgs()
        if (sources.isEmpty()) settings() else activate(prefs.getInt("act", 0), false, true)
    }

    fun loadControl(): DefaultLoadControl = when (bufIdx) {
        1 -> DefaultLoadControl.Builder().setBufferDurationsMs(8000, 30000, 1500, 3000)
        2 -> DefaultLoadControl.Builder().setBufferDurationsMs(15000, 60000, 2500, 5000)
        else -> DefaultLoadControl.Builder().setBufferDurationsMs(4000, 20000, 800, 1500)
    }.build()

    // builds the player (the buffer size is chosen here)
    fun makePlayer(): ExoPlayer {
        val ds = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true).setUserAgent("Mozilla/5.0").setConnectTimeoutMs(10000)
        val p = ExoPlayer.Builder(this, DefaultRenderersFactory(this).setEnableDecoderFallback(true))
            .setMediaSourceFactory(DefaultMediaSourceFactory(ds))
            .setLoadControl(loadControl()).build()
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()   // subtitles off by default
        p.addListener(object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                pv.removeCallbacks(loadRun); pv.removeCallbacks(retryRun); hideLoading()
                if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && retryN < MAX_RETRY) {   // fell behind a live stream: jump back to live
                    retryN++; player.seekToDefaultPosition(); player.prepare(); return
                }
                errShown = true
                logErr((cur?.name ?: "Stream") + (if (catchupProg != null) " (catch-up)" else "") + ": " + errText(e))
                val head = cur?.let { "${it.num}: ${it.name}" } ?: "Stream"
                if (retryN < MAX_RETRY) {
                    val wait = minOf(30000L, 2500L shl retryN); retryN++
                    flashMid("$head\n${errText(e)}\nRetry $retryN of $MAX_RETRY in ${wait / 1000} s", wait)
                    pv.postDelayed(retryRun, wait)
                } else flashMid("$head\n${errText(e)}\nGave up. Select the channel again to retry", 10000)
            }
            override fun onPlaybackStateChanged(st: Int) {
                pv.removeCallbacks(loadRun)
                if (st == Player.STATE_BUFFERING) { if (!errShown) pv.postDelayed(loadRun, 700) } else hideLoading()
                if (st == Player.STATE_READY) { retryN = 0; if (errShown) { errShown = false; clearMid() } }
                if (st == Player.STATE_ENDED && catchupProg != null) { cur?.let { flash("Catch-up ended, back to live", 2500); play(it) } }
            }
        })
        return p
    }

    // a new buffer size needs a new player: the channel that was playing starts again
    fun rebuildPlayer() {
        val c = cur
        pv.removeCallbacks(retryRun); pv.removeCallbacks(loadRun)
        player.release(); player = makePlayer(); pv.player = player
        if (c != null) play(c)
    }



    val epgTick: Runnable = object : Runnable { override fun run() { checkPlaylists(); checkEpg(); pv.postDelayed(this, 3600_000L) } }

    // leaving Settings: a changed guide length re-reads the files already on disk (no download)
    fun settingsDone() {
        if (bufDirty) { bufDirty = false; rebuildPlayer() }
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
                val all = loadPlaylist(s, refresh); val urls = epgUrls(s)
                val ready = urls.all { epgAt.containsKey(it) }
                val fresh = urls.all { (epgAt[it] ?: 0L) > System.currentTimeMillis() - epgMaxAge() }
                runOnUiThread { setChannels(all, boot, keepFav); syncScope() }   // the guide already in the database shows straight away
                cleanCache()   // only after the screen has switched: it writes to the guide database, which is locked while a guide is being imported
                preloadChannels()
                ensureEpg(urls, refresh || (ready && !fresh)); epgChanged()
                preloadOthers(refresh)
            } catch (e: Exception) { logErr("Playlist load failed: " + (e.message ?: e.javaClass.simpleName)); runOnUiThread { Toast.makeText(this, "Load failed: ${e.message}", Toast.LENGTH_LONG).show() } }
        }.start()
    }


    fun vis() = if (showHidden) channels else channels.filter { it.url !in hidden }

    // favorited channels from every playlist that is loaded (the active one is always included)
    fun favRaw(): List<Ch> {
        val out = ArrayList<Ch>(); val seen = HashSet<String>(); val activeId = sources.getOrNull(act)?.id
        for (s in sources.toList()) {
            val l = chCache[s.id] ?: (if (s.id == activeId) channels else emptyList())
            for (c in l) if (c.url in favs && (showHidden || c.url !in hidden) && seen.add(c.url)) out.add(c)
        }
        return out
    }

    val coll: java.text.Collator by lazy { java.text.Collator.getInstance(java.util.Locale("fi")).apply { strength = java.text.Collator.SECONDARY } }   // ignores case, knows ä ö å

    // SQL LIKE pattern for the typed text; % and _ are escaped so they are searched as normal characters
    fun likeArg(q: String) = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

    // guide keys of the programmes that are on right now and have the typed text in their title (one query for every playlist's guide)
    fun keysOnNow(q: String): HashSet<String> {
        val out = HashSet<String>(); val now = System.currentTimeMillis()
        val sc = sources.toList().flatMap { epgUrls(it) }.distinct().map { hx(it) }; if (sc.isEmpty()) return out
        try {
            val sql = "SELECT DISTINCT ch FROM p WHERE s <= $now AND e > $now AND t LIKE ? ESCAPE '\\' AND src IN (" + sc.joinToString(",") { "?" } + ")"
            epgDb.rawQuery(sql, (listOf(likeArg(q)) + sc).toTypedArray()).use { cur -> while (cur.moveToNext()) out.add(cur.getString(0)) }
        } catch (e: Exception) {}
        return out
    }

    // searches every playlist (they are all kept in memory) by channel name and by the title of the show that is on now;
    // name matches come first; the guide of all playlists is merged while the results are shown
    fun search(q: String) {
        Thread {
            val byName = ArrayList<Ch>(); val byShow = ArrayList<Ch>(); val seen = HashSet<String>()
            val on = keysOnNow(q)
            for (src in sources.toList()) {
                val l = chCache[src.id] ?: try { loadPlaylist(src, false) } catch (e: Exception) { emptyList() }
                for (c in l) {
                    if (!showHidden && c.url in hidden) continue
                    if (c.name.contains(q, true)) { if (seen.add(c.url)) byName.add(c) }
                    else if (on.isNotEmpty() && keysOf(c).any { it in on } && seen.add(c.url)) byShow.add(c)
                }
            }
            runOnUiThread { searching = true; shown = byName + byShow; syncScope(); ui.open(1) }
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
    var backKey: List<Ch>? = null; var backVal = 0
    // how many days back the guide should reach: the longest catch-up of the channels on screen (at most 14)
    fun backDays(): Int {
        val k = shown
        if (k !== backKey) { backKey = k; backVal = minOf(14, k.maxOfOrNull { catchupDays(it) } ?: 0) }
        return backVal
    }

    @Volatile var firstCache = 0L; @Volatile var firstVer = -1
    // start of the first programme in the database (so only days that really have data are listed)
    fun epgFirst(): Long {
        val v = epgVer
        if (firstVer == v) return firstCache
        val sc = scope; var m = 0L
        if (sc.isNotEmpty()) {
            try {
                epgDb.rawQuery("SELECT MIN(s) FROM p WHERE src IN (" + sc.joinToString(",") { "?" } + ")", sc.toTypedArray()).use { cur ->
                    if (cur.moveToFirst() && !cur.isNull(0)) m = cur.getLong(0)
                }
            } catch (e: Exception) {}
        }
        firstCache = m; firstVer = v
        return m
    }

    fun days(): List<Long> {
        val out = ArrayList<Long>()
        val back = backDays(); val first = epgFirst()
        val pc = java.util.Calendar.getInstance(); pc.timeInMillis = dayStart()
        for (i in 1..back) {   // earlier days, only as far back as the guide has data
            pc.add(java.util.Calendar.DAY_OF_MONTH, -1)
            if (first <= 0 || pc.timeInMillis + 86400000L <= first) break
            out.add(0, pc.timeInMillis)
        }
        val c = java.util.Calendar.getInstance(); c.timeInMillis = dayStart()
        val end = epgEnd()
        for (i in 0 until maxOf(2, epgDaysN)) {
            if (i >= 2 && c.timeInMillis >= end) break
            out.add(c.timeInMillis); c.add(java.util.Calendar.DAY_OF_MONTH, 1)
        }
        return out
    }

    fun todayIdx(): Int = days().indexOf(dayStart()).coerceAtLeast(0)
    fun dayProgs(ch: Ch, day: Long): List<Prog> = progsOf(ch).filter { it.stop > day && it.start < day + 86400000L }

    // options bar next to a channel / program. ids: 0 Watch, 1 TV guide, 2 Program info, 3 Channel settings, 4 favorite, 5 hide
    // the TV guide button is only in the channel list (LEFT); inside the guide itself it is not shown
    fun optIds(guide: Boolean, c: Ch? = null, p: Prog? = null): List<Int> {
        if (!guide) return listOf(0, 1, 2, 3, 4, 5)
        if (c != null && p != null && canCatchup(c, p))
            return if (p.stop <= System.currentTimeMillis()) listOf(7, 0, 2, 3, 4, 5) else listOf(0, 6, 2, 3, 4, 5)
        return listOf(0, 2, 3, 4, 5)
    }
    fun nOpts(guide: Boolean, c: Ch? = null, p: Prog? = null) = optIds(guide, c, p).size
    fun chOptions(c: Ch, guide: Boolean, p: Prog? = null) = optIds(guide, c, p).map { id ->
        when (id) {
            0 -> if (guide && p != null && p.stop <= System.currentTimeMillis() && canCatchup(c, p)) "Watch live" else "Watch"
            1 -> "TV guide"; 2 -> "Program info"; 3 -> "Channel settings"
            4 -> if (c.url in favs) "Remove from favorites" else "Add to favorites"
            6 -> "Watch from start"; 7 -> "Watch catch-up"
            else -> if (c.url in hidden) "Unhide channel" else "Hide channel"
        }
    }

    fun toggle(set: HashSet<String>, key: String, u: String) { if (!set.remove(u)) set.add(u); prefs.edit().putStringSet(key, HashSet(set)).apply() }

    fun chOption(c: Ch, i: Int, p: Prog? = null) {
        val guide = ui.mode == 2
        when (optIds(guide, c, p).getOrNull(i)) {
            0 -> { if (c == cur) { ui.close(); switchTo(c) } else play(c) }   // 1st Watch: change channel, UI stays; 2nd Watch: close UI
            1 -> ui.openGuide(c)
            2 -> ui.openInfo(c, p ?: now(c))
            3 -> { if (c != cur) play(c); ui.openSub() }
            4 -> { toggleFav(c.url); selectGroup(grp, true) }
            5 -> { toggle(hidden, "hidden", c.url); selectGroup(grp, true) }
            6, 7 -> if (p != null) { ui.close(); playCatchup(c, p); switchTo(c) }
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
        try {
            if (i == 2) {
                arMode = k.coerceIn(0, 2)
                pv.resizeMode = intArrayOf(AspectRatioFrameLayout.RESIZE_MODE_FIT, AspectRatioFrameLayout.RESIZE_MODE_ZOOM, AspectRatioFrameLayout.RESIZE_MODE_FILL)[arMode]
                flash("Aspect ratio: ${arNames[arMode]}", 1500)
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
        } catch (e: Exception) {
            flash("Track change failed: ${e.javaClass.simpleName} ${e.message ?: ""}", 6000)
        }
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
        val input = dEdit("Channel or show name"); var d: android.app.Dialog? = null
        fun go() { val q = input.text.toString().trim(); d?.dismiss(); if (q.isNotEmpty()) search(q) }
        input.setOnEditorActionListener { _, _, _ -> go(); true }
        d = showPanel("Search channels and shows on now", listOf(input), listOf(
            DBtn("Cancel", C_FIELD) { it.dismiss() },
            DBtn("Search", C_PUR) { go() }), keyboard = true)
    }

    // main menu (OK button); the number is the icon id
    fun menuItems() = listOf(0 to "Search", 9 to "Playlists", 2 to "TV guide", 3 to "On air now", 4 to (if (showHidden) "Hide hidden" else "Show hidden"),
        6 to "Last channel", 11 to "Settings" + (if (updState == 3 || updState == 5) "  \u25CF" else ""))

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

    // ---- app update from the GitHub releases of hertn/LiteTV: check the latest release, download its APK, hand it to Android's installer ----
    @Volatile var updState = 0   // 0 not checked, 1 checking, 2 up to date, 3 update available, 4 downloading, 5 ready to install, 6 failed
    @Volatile var updTag = ""; @Volatile var updUrl = ""; @Volatile var updPct = 0; @Volatile var updErr = ""
    val UPDATE_API = "https://api.github.com/repos/hertn/LiteTV/releases/latest"

    // "v1.2.0" -> [1, 2, 0]; anything after a "-" (like "-dev") is ignored
    fun verParts(v: String): List<Int> = v.trim().removePrefix("v").removePrefix("V").substringBefore('-').split('.').map { x -> x.filter { it.isDigit() }.toIntOrNull() ?: 0 }
    fun isNewer(remote: String, local: String): Boolean {
        val r = verParts(remote); val l = verParts(local)
        for (i in 0 until maxOf(r.size, l.size)) { val x = r.getOrElse(i) { 0 }; val y = l.getOrElse(i) { 0 }; if (x != y) return x > y }
        return false
    }
    fun updRefresh() { runOnUiThread { ui.invalidate() } }

    // silent = the background check: a failed one stays quiet, and a new version is announced once
    fun checkUpdate(silent: Boolean = false) {
        if (updState == 1 || updState == 4 || (silent && (updState == 3 || updState == 5))) return
        updState = 1; updRefresh()
        Thread {
            try {
                val cn = java.net.URL(UPDATE_API).openConnection() as java.net.HttpURLConnection
                cn.connectTimeout = 10000; cn.readTimeout = 10000
                cn.setRequestProperty("Accept", "application/vnd.github+json"); cn.setRequestProperty("User-Agent", "LiteTV")
                if (cn.responseCode != 200) throw Exception("GitHub answered ${cn.responseCode}")
                val o = JSONObject(cn.inputStream.bufferedReader().use { it.readText() })
                val tag = o.getString("tag_name"); val assets = o.optJSONArray("assets") ?: JSONArray()
                var url = ""
                for (k in 0 until assets.length()) { val ast = assets.getJSONObject(k); if (ast.getString("name").endsWith(".apk", true)) { url = ast.getString("browser_download_url"); break } }
                val local = packageManager.getPackageInfo(packageName, 0).versionName ?: "0"
                when {
                    !isNewer(tag, local) -> updState = 2
                    url.isEmpty() -> { updErr = "No APK attached to $tag"; updState = 6 }
                    else -> { updTag = tag; updUrl = url; updState = 3 }
                }
            } catch (e: Exception) { updErr = e.message ?: "No connection"; updState = 6 }
            if (updState == 6) logErr("Update check failed: $updErr")
            if (updState == 2 || updState == 3) prefs.edit().putLong("updAt", System.currentTimeMillis()).apply()
            if (silent && updState == 6) updState = 0
            if (silent && updState == 3 && prefs.getString("updSeen", "") != updTag) {
                prefs.edit().putString("updSeen", updTag).apply()
                runOnUiThread { flash("Update available: $updTag. Open Settings > App update", 8000) }
            }
            updRefresh()
        }.start()
    }

    // quiet check for a newer version: shortly after the app starts, then about every 12 hours while it is on screen
    fun autoCheckUpdate() {
        if (System.currentTimeMillis() - prefs.getLong("updAt", 0L) < 12 * 3600_000L) return
        checkUpdate(true)
    }
    val updTick: Runnable = object : Runnable { override fun run() { autoCheckUpdate(); pv.postDelayed(this, 3600_000L) } }

    fun downloadUpdate() {
        if (updState != 3) return
        updState = 4; updPct = 0; updRefresh()
        val url = updUrl
        Thread {
            try {
                val dir = java.io.File(cacheDir, "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val f = java.io.File(dir, "LiteTV-update.apk")
                val cn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                cn.connectTimeout = 15000; cn.readTimeout = 20000; cn.setRequestProperty("User-Agent", "LiteTV")
                if (cn.responseCode != 200) throw Exception("Download failed (${cn.responseCode})")
                val total = cn.contentLength.toLong(); var got = 0L; var last = -1
                cn.inputStream.use { ins -> f.outputStream().use { out ->
                    val buf = ByteArray(32768)
                    while (true) {
                        val n = ins.read(buf); if (n < 0) break
                        out.write(buf, 0, n); got += n
                        if (total > 0) { val pc = (got * 100 / total).toInt(); if (pc != last) { last = pc; updPct = pc; updRefresh() } }
                    }
                } }
                if (f.length() < 100000L) throw Exception("The downloaded file looks wrong")
                updState = 5
            } catch (e: Exception) { updErr = e.message ?: "Download failed"; updState = 6; logErr("Update download failed: $updErr") }
            updRefresh()
        }.start()
    }

    fun installUpdate() {
        val f = java.io.File(java.io.File(cacheDir, "updates"), "LiteTV-update.apk")
        if (!f.exists()) { updState = 3; updRefresh(); return }
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
                // first time only: Android wants you to allow this app to install apps
                Toast.makeText(this, "Allow LiteTV to install apps, then go back and press OK again", Toast.LENGTH_LONG).show()
                startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:$packageName")))
                return
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { Toast.makeText(this, "Could not open the installer: ${e.message}", Toast.LENGTH_LONG).show() }
    }

    fun now(c: Ch): Prog? { val t = System.currentTimeMillis(); return progsOf(c).firstOrNull { t >= it.start && t < it.stop } }
    fun next(c: Ch): Prog? { val n = now(c) ?: return null; return progsOf(c).firstOrNull { it.start >= n.stop } }

    var catchupProg: Prog? = null   // the past programme that is playing right now (null = live)
    var catchupSrc: String? = null   // the link of the catch-up that is playing (the seek bar uses it to ask for preview pictures)

    fun play(c: Ch) = startPlay(c, c.url, null)

    fun startPlay(c: Ch, url: String, cp: Prog?) {
        if (cur != null && cur != c) prev = cur
        cur = c; catchupProg = cp
        catchupSrc = if (cp != null) url else null
        if (cp != null) { catchupBar.resetThumbs(); catchupBar.target = -1L; catchupBar.show() } else catchupBar.visibility = View.GONE
        errShown = false; retryN = 0; hideLoading(); clearMid(); pv.removeCallbacks(retryRun); pv.removeCallbacks(loadRun)
        osd.removeCallbacks(hideOsd); osd.visibility = View.GONE
        val item = MediaItem.Builder().setUri(url).apply {
            // catch-up links have no .m3u8 extension, so tell ExoPlayer it is HLS
            if (cp != null) setMimeType(MimeTypes.APPLICATION_M3U8)
        }.build()
        player.setMediaItem(item); player.prepare(); player.playWhenReady = true
        if (cp == null) {
            prefs.edit().putString("last", c.url).apply()
            if (ui.mode == 0) banner.show(c)   // no banner while the lists are open
        }
    }

    // a programme can be rewatched when its channel has catch-up, it has started, and it is not older than the channel's catch-up days
    fun canCatchup(c: Ch, p: Prog): Boolean {
        if (catchupMode(c).isEmpty()) return false
        val now = System.currentTimeMillis()
        return p.start <= now && p.start >= now - catchupDays(c) * 86400_000L
    }

    fun playCatchup(c: Ch, p: Prog) {
        val u = catchupUrl(c, p)
        logErr("CATCHUP URL: $u")
        if (u == null) { flash("Catch-up is not available for this channel", 3000); return }
        startPlay(c, u, p)
    }

    fun skip(ms: Long) {
        if (player.isCurrentMediaItemSeekable) { player.seekTo(maxOf(0L, player.currentPosition + ms)); flash(if (ms > 0) "+${ms / 1000} s" else "${ms / 1000} s", 1000) }
        else flash("This stream cannot be skipped", 1500)
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

    fun zap(d: Int) {
        val l = if (shown.isNotEmpty()) shown else channels; if (l.isEmpty()) return
        val i = l.indexOf(cur); play(l[((if (i < 0) 0 else i) + d + l.size) % l.size])
    }

    var dlg: android.app.Dialog? = null
    var srv: java.net.ServerSocket? = null


    fun settings() {
        startServer()
        val ip = localIp(); val url = ip?.let { "http://$it:$PORT" }
        val dp = resources.displayMetrics.density
        fun label(t: String, sp: Float, col: Int, bold: Boolean = false, top: Int = 0) = TextView(this).apply {
            text = t; textSize = sp; setTextColor(col); if (bold) typeface = Typeface.DEFAULT_BOLD; setLineSpacing(0f, 1.1f)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (top * dp).toInt() }
        }
        // text on the left, QR code on the right
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
            if (url != null) {
                addView(label("Manage from your phone", 20f, -1, true))
                addView(label("Scan the code, or open this address in a browser on the same network", 16f, 0xFFB8BECC.toInt(), top = 6))
                addView(label(url, 18f, C_PUR, true, 12))
            } else addView(label("No network", 20f, -1, true))
            addView(View(this@MainActivity).apply { setBackgroundColor(0x33FFFFFF); layoutParams = LinearLayout.LayoutParams(-1, (1 * dp).toInt()).apply { topMargin = (22 * dp).toInt() } })
            addView(label("Reload", 18f, -1, true, 20))
            addView(label("Downloads the playlists, guides and channel logos again from scratch. If a download fails, the previous copy is kept.", 16f, 0xFF8A90A0.toInt(), top = 6))
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(info, LinearLayout.LayoutParams(0, -2, 1f))
            if (url != null) addView(ImageView(this@MainActivity).apply {
                setImageBitmap(qr(url, 300)); background = dBg(0xFFFFFFFF.toInt())
            }, LinearLayout.LayoutParams((220 * dp).toInt(), (220 * dp).toInt()).apply { leftMargin = (32 * dp).toInt() })
        }
        val views = listOf<View>(body)
        val d = showPanel("Playlist & EPG Setup", views, listOf(
            DBtn("Close", C_FIELD) { it.dismiss() },
            DBtn("Reload", C_FIELD) { it.dismiss(); Thread { clearLogoAndGuideCaches(); activate(act, true) }.start() },
            DBtn("Add here", C_PUR) { it.dismiss(); addSource() }), widthFrac = 0.62)
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

    val numRun = Runnable { val n = numBuf.toIntOrNull(); numBuf = ""; if (n != null) channels.firstOrNull { it.num == n }?.let { play(it) } }

    // BACK is handled here (before any view sees it): a short press goes back, holding it exits the app completely
    var backLong = false
    override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
        if (sleepWarned && ev.action == KeyEvent.ACTION_DOWN) { setSleep(0); return true }
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
        if (catchupProg != null && catchupBar.visibility == View.VISIBLE) { catchupBar.close(); return }   // BACK first closes the seek bar (and cancels a jump you haven't confirmed)
        if (catchupProg != null) { cur?.let { flash("Back to live", 1500); play(it) }; return }
        if (prev != null) prev?.let { play(it) } else flash("Hold BACK to exit the app", 2500)
    }

    // closes the app for real and frees all of its memory
    fun exitApp() { exiting = true; finishAndRemoveTask() }

    override fun onKeyDown(k: Int, e: KeyEvent): Boolean {
        if (ui.mode != 0) { ui.requestFocus(); return ui.onKeyDown(k, e) }
        if (catchupProg != null) {
            when (k) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { catchupBar.nudge(-1, e.repeatCount); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { catchupBar.nudge(1, e.repeatCount); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { catchupBar.okPress(); return true }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { catchupBar.show(); return true }
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN -> { catchupBar.show(); return true }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { catchupBar.nudge(1, 8); return true }
                KeyEvent.KEYCODE_MEDIA_REWIND -> { catchupBar.nudge(-1, 8); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { player.playWhenReady = !player.playWhenReady; catchupBar.show(); return true }
            }
        }
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
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> player.playWhenReady = !player.playWhenReady
            KeyEvent.KEYCODE_MEDIA_PLAY -> player.playWhenReady = true
            KeyEvent.KEYCODE_MEDIA_PAUSE -> player.playWhenReady = false
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> skip(30000L)
            KeyEvent.KEYCODE_MEDIA_REWIND -> skip(-30000L)
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

    override fun onStop() { super.onStop(); if (catchupProg == null) player.stop() else player.pause();   // live: let go of the hardware decoders while in the background; catch-up keeps its position
        stopServer(); pv.removeCallbacks(epgTick); pv.removeCallbacks(updTick) }
    override fun onStart() {
        super.onStart(); if (cur != null) { if (player.playbackState == Player.STATE_IDLE) { player.prepare(); player.playWhenReady = true } else player.play() }
        pv.removeCallbacks(updTick); pv.postDelayed(updTick, 30_000L)
        pv.removeCallbacks(epgTick); pv.postDelayed(epgTick, 60_000L)   // coming back from Home: refresh the guide a minute later if it is older than the refresh setting
    }
    // pressing Home: give back the memory that is only a cache (logos, programmes read for the screen); they are re-read when needed
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) { logos.evictAll(); progCache.evictAll(); descCache.evictAll(); hitKey.clear() }
    }
    override fun onDestroy() {
        super.onDestroy(); player.release()
        if (exiting) { try { epgDb.close() } catch (e: Exception) {}; android.os.Process.killProcess(android.os.Process.myPid()) }
    }
}