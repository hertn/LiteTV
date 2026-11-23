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
import androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LivePlaybackSpeedControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
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
const val UI_ALPHA = 0.98f   // with the blur off: 1.0 = solid; lower = more see-through
const val REVERSED = true   // Telia style: higher channel numbers above, UP = higher. Set false for normal order.
const val ROW_SCALE = 0.95f // 1.0 = original layout; lower = rows slightly shorter, so the options bar, info box and chips sit higher (everything stays aligned)

class MainActivity : Activity() {
    lateinit var root: FrameLayout; lateinit var pv: PlayerView; lateinit var pip: Pip; lateinit var ghost: Box; lateinit var heldV: TextView; lateinit var osd: TextView; lateinit var numV: TextView; lateinit var ui: Ui; lateinit var snow: Snow; lateinit var blurLayer: BlurLayer; lateinit var banner: Banner; lateinit var msg: TextView; lateinit var spin: ProgressBar
    lateinit var player: ExoPlayer; lateinit var prefs: SharedPreferences
    lateinit var catchupBar: CatchupBar
    var channels = listOf<Ch>(); var shown = listOf<Ch>(); var groups = listOf("★ Favorites", "All")
    var sources = listOf<Src>(); var epgs = listOf<Epg>(); var act = 0; var grp = 1
    var favGrp: String? = null   // the favorite group shown in Favorites (lowercase key), null = all favorites
    var prev: Ch? = null; var cur: Ch? = null; var numBuf = ""; var arMode = 0
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
    val arNames = arrayOf("Fit", "Zoom", "Wider", "Taller")   // Wider for bars at the sides (4:3 shows), Taller for bars top and bottom (films)
    // audio delay per channel (saved as "avd:" + channel url, in ms); + = sound later, - = sound earlier
    // every 50 ms from -30 s to +30 s; in the list Up/Down step 50 ms, Left/Right jump AVD_JUMP steps (1 s)
    val AVD_MS = IntArray(1201) { (it - 600) * 50 }; val AVD_JUMP = 20
    fun avdName(ms: Int): String {
        val a = Math.abs(ms); val sign = if (ms > 0) "+" else if (ms < 0) "\u2212" else ""
        return sign + if (a < 1000) "$a ms" else "${a / 1000}.${(a % 1000 / 10).toString().padStart(2, '0')} s"
    }
    val avdNames by lazy { AVD_MS.map { avdName(it) } }
    @Volatile var avdUs = 0L   // read on the playback thread by the audio clock
    fun avdMs(c: Ch) = prefs.getInt("avd:" + c.url, 0)
    // the player's clock runs ahead/behind by the delay; this is the position seekTo() works in
    fun pos() = maxOf(0L, player.currentPosition - avdUs / 1000)
    // the channel the settings tiles show: the playing one, else the highlighted one in the list
    fun subCh() = cur ?: shown.getOrNull(ui.sel)
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
    val BUF_NAMES = arrayOf("Fast Start", "Steady")
    var bufIdx = 0; var bufDirty = false
    var plDays = 7   // playlists are downloaded again when their file is older than this many days (0 = only by hand)
    val retryRun = Runnable { if (cur != null) player.prepare() }
    // a spinner in the middle of the screen shows when a channel is still buffering after a short moment
    var loadingShown = false
    val loadRun = Runnable { if (player.playbackState == Player.STATE_BUFFERING && !errShown) { loadingShown = true; spin.visibility = View.VISIBLE } }
    fun hideLoading() { loadingShown = false; spin.visibility = View.GONE }
    // stream errors are shown in the middle of the screen
    val hideMsg = Runnable { msg.visibility = View.GONE }
    fun flashMid(t: String, ms: Long) { msg.text = t; msg.visibility = View.VISIBLE; msg.bringToFront(); msg.removeCallbacks(hideMsg); msg.postDelayed(hideMsg, ms) }
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
    var themeIdx = 0   // saved theme, index into THEMES (the redesign uses a new key: the old theme list no longer exists)
    var blurOn = false   // Settings > Blur behind menus
    var seeThru = true   // Settings > Transparency (off = solid menus, no blur)
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
    fun themeNames(): List<String> = THEMES.map { it.name }

    fun loadTheme() {
        themeIdx = prefs.getInt("theme2", 0).coerceIn(0, THEMES.lastIndex)
        T = THEMES[themeIdx]
        blurOn = prefs.getBoolean("blur", defaultBlur()); seeThru = prefs.getBoolean("seeThru", true)
    }

    fun refreshTheme() {
        root.setBackgroundColor(0xFF000000.toInt()); osd.background = dBg(withAlpha(T.panel, 0xF0), 0x1AFFFFFF, 28f); numV.background = dBg(withAlpha(T.panel, 0xF0), 0x1AFFFFFF, 28f); msg.background = dBg(withAlpha(T.panel, 0xF0), 0, 24f)
        ui.invalidate(); banner.invalidate()
    }

    // save = false: only a preview (undone with setTheme(themeIdx, false)); save = true: remember it
    fun setTheme(i: Int, save: Boolean) {
        T = THEMES[i.coerceIn(0, THEMES.lastIndex)]
        if (save) { themeIdx = i; prefs.edit().putInt("theme2", i).apply() }
        refreshTheme()
    }

    // blur starts off on TVs with 1 GB of memory or less, and where the video frame cannot be copied (Android 6 and older)
    fun defaultBlur(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 24) return false
        val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo(); am.getMemoryInfo(mi)
        return !am.isLowRamDevice && mi.totalMem > 1_300_000_000L
    }
    fun setBlur(on: Boolean) { blurOn = on && android.os.Build.VERSION.SDK_INT >= 24; prefs.edit().putBoolean("blur", blurOn).apply(); ui.blur.reset(); applyAlpha(); ui.invalidate() }
    // with the blur on, the menus must be fully opaque: the blur already shows the video, and any leak shows it a second time, sharp
    fun toggleSeeThru(on: Boolean) { seeThru = on; prefs.edit().putBoolean("seeThru", on).apply(); ui.blur.reset(); applyAlpha(); ui.invalidate(); banner.invalidate(); catchupBar.invalidate() }
    fun uiAlpha() = if (blurOn || !seeThru) 1f else UI_ALPHA
    fun applyAlpha() { val al = uiAlpha(); ui.alpha = al; banner.alpha = al; catchupBar.alpha = al }
    // small cards (notices, the channel number) drop in: fade + 12 px slide, 240 ms
    fun popIn(v: View) { v.animate().cancel(); if (!ui.animOn()) { v.alpha = 1f; v.translationY = 0f; return }; v.alpha = 0f; v.translationY = -px(12f); v.animate().alpha(1f).translationY(0f).setDuration(240).setInterpolator(ui.EASE).start() }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = getSharedPreferences("p", 0); keys = RemoteKeys(prefs)
        prefs.getString("updTag", null)?.let { t ->   // an update found before the restart, unless it is installed by now
            if (isNewer(t, packageManager.getPackageInfo(packageName, 0).versionName ?: "0")) { updTag = t; updUrl = prefs.getString("updUrl", "") ?: ""; updState = 3 } }
        loadTheme()
        epgHours = prefs.getInt("epgH", 12); epgDaysN = prefs.getInt("epgD", 7).coerceIn(1, 30)
        bufIdx = prefs.getInt("buf", 0).coerceIn(0, BUF_NAMES.lastIndex); plDays = prefs.getInt("plD", 7).coerceIn(0, 60)
        Thread { try { epgDb.isOpen } catch (e: Exception) {} }.start()   // open the guide database in the background
        seasonOn = prefs.getBoolean("season", true)
        favs.addAll(prefs.getStringSet("favs", emptySet())!!); hidden.addAll(prefs.getStringSet("hidden", emptySet())!!)
        loadFavPrefs(); fontIdx = prefs.getInt("fontSize", 1).coerceIn(0, FONT_NAMES.lastIndex)
        root = FrameLayout(this); root.setBackgroundColor(0xFF000000.toInt())
        pv = PlayerView(this).apply { useController = false; keepScreenOn = true }; root.addView(pv)
        // pinned channel: the "is pinned" notice in place of the video, the corner picture, and the still frame used by Swap
        heldV = TextView(this).apply {
            setBackgroundColor(0xFF000000.toInt()); setTextColor(-1); gravity = Gravity.CENTER; setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(40f)); setLineSpacing(px(14f), 1f)
            compoundDrawablePadding = px(34f).toInt(); visibility = View.GONE }
        root.addView(heldV, FrameLayout.LayoutParams(-1, -1))
        val pw = px(360f).toInt(); val ph = px(203f).toInt()
        pip = Pip(this, this); root.addView(pip, FrameLayout.LayoutParams(pw, ph, Gravity.TOP or Gravity.START))
        ghost = Box(this, this).apply { addView(ImageView(this@MainActivity).apply { scaleType = ImageView.ScaleType.FIT_XY }, FrameLayout.LayoutParams(-1, -1)); visibility = View.GONE }
        root.addView(ghost, FrameLayout.LayoutParams(pw, ph, Gravity.TOP or Gravity.START))
        // notices: a small card in the top-right corner, where Apple TV shows notifications; nothing else lives there
        osd = TextView(this).apply {
            setTextColor(-1); setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(28f)); gravity = Gravity.CENTER_VERTICAL; setLineSpacing(0f, 1.15f)
            setPadding(px(30f).toInt(), px(26f).toInt(), px(34f).toInt(), px(26f).toInt()); compoundDrawablePadding = px(20f).toInt()
            maxWidth = px(760f).toInt(); visibility = View.GONE }
        root.addView(osd, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { topMargin = px(36f).toInt(); rightMargin = px(36f).toInt() })
        // the channel number while typing it: just the digits, big, in the same corner
        numV = TextView(this).apply {
            setTextColor(-1); setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(84f)); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            minWidth = px(240f).toInt(); minHeight = px(150f).toInt(); setPadding(px(40f).toInt(), 0, px(40f).toInt(), 0); visibility = View.GONE }
        root.addView(numV, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { topMargin = px(36f).toInt(); rightMargin = px(36f).toInt() })
        msg = TextView(this).apply {   // stream errors: in the middle of the screen
            setTextColor(-1); textSize = 22f; gravity = Gravity.CENTER; setPadding(px(56f).toInt(), px(36f).toInt(), px(56f).toInt(), px(36f).toInt())
            maxWidth = (resources.displayMetrics.widthPixels * 0.7).toInt(); visibility = View.GONE
        }
        root.addView(msg, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        spin = ProgressBar(this).apply { isIndeterminate = true; indeterminateTintList = android.content.res.ColorStateList.valueOf(-1); visibility = View.GONE }   // buffering circle, no text
        val spinSize = (72 * resources.displayMetrics.density).toInt()
        root.addView(spin, FrameLayout.LayoutParams(spinSize, spinSize, Gravity.CENTER))
        blurLayer = BlurLayer(this, this); root.addView(blurLayer, FrameLayout.LayoutParams(-1, -1))
        banner = Banner(this, this).apply { visibility = View.GONE }; root.addView(banner, FrameLayout.LayoutParams(-1, -1))
        catchupBar = CatchupBar(this, this).apply { visibility = View.GONE }; root.addView(catchupBar, FrameLayout.LayoutParams(-1, -1))
        ui = Ui(this, this).apply { visibility = View.GONE }; root.addView(ui, FrameLayout.LayoutParams(-1, -1)); applyAlpha()
        snow = Snow(this).apply { visibility = View.GONE }; root.addView(snow, FrameLayout.LayoutParams(-1, -1))   // above the menus
        setContentView(root); refreshTheme()
        player = makePlayer(); pv.player = player
        sources = readSrcs(); epgs = readEpgs()
        if (sources.isEmpty()) settings() else activate(prefs.getInt("act", 0), false, true)
    }

    // min = max: keep downloading all the time. With min 4 s the player sat idle for ~20 s until only 4 s were left, then raced
    // to refill, so any slow moment on the network ran the buffer dry (seen on a Chromecast HD); start-up time is unchanged
    fun loadControl(): DefaultLoadControl = when (bufIdx) {
        1 -> DefaultLoadControl.Builder().setBufferDurationsMs(30000, 30000, 1500, 3000)
        else -> DefaultLoadControl.Builder().setBufferDurationsMs(20000, 20000, 800, 1500)
    }.build()

    // builds the player (the buffer size is chosen here)
    fun makePlayer(): ExoPlayer {
        val ds = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true).setUserAgent("Mozilla/5.0").setConnectTimeoutMs(10000)
        // the audio clock is shifted by the channel's delay; video follows that clock, so the picture moves against the sound
        val rf = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink? =
                super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)?.let { sink ->
                    object : ForwardingAudioSink(sink) {
                        override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
                            val pos = super.getCurrentPositionUs(sourceEnded)
                            return if (pos == AudioSink.CURRENT_POSITION_NOT_SET) pos else pos + avdUs
                        }
                    }
                }
        }
        // when the picture is held back (sound earlier) the player's position trails the sound, so it would think it has
        // more buffered than the sound really has: take the delay off before the buffer decisions
        val lc = loadControl()
        val lcAvd = object : LoadControl by lc {
            fun real(b: Long) = if (avdUs < 0) maxOf(0L, b + avdUs) else b
            // Java default methods are not delegated by `by`, so these are forwarded by hand
            override fun onTracksSelected(timeline: Timeline, mediaPeriodId: MediaSource.MediaPeriodId, renderers: Array<out Renderer>, trackGroups: TrackGroupArray, trackSelections: Array<out ExoTrackSelection>) =
                lc.onTracksSelected(timeline, mediaPeriodId, renderers, trackGroups, trackSelections)
            override fun onTracksSelected(renderers: Array<out Renderer>, trackGroups: TrackGroupArray, trackSelections: Array<out ExoTrackSelection>) =
                lc.onTracksSelected(renderers, trackGroups, trackSelections)
            override fun shouldContinueLoading(playbackPositionUs: Long, bufferedDurationUs: Long, playbackSpeed: Float) =
                lc.shouldContinueLoading(playbackPositionUs, real(bufferedDurationUs), playbackSpeed)
            override fun shouldStartPlayback(timeline: Timeline, mediaPeriodId: MediaSource.MediaPeriodId, bufferedDurationUs: Long, playbackSpeed: Float, rebuffering: Boolean, targetLiveOffsetUs: Long) =
                lc.shouldStartPlayback(timeline, mediaPeriodId, real(bufferedDurationUs), playbackSpeed, rebuffering, targetLiveOffsetUs)
            override fun shouldStartPlayback(bufferedDurationUs: Long, playbackSpeed: Float, rebuffering: Boolean, targetLiveOffsetUs: Long) =
                lc.shouldStartPlayback(real(bufferedDurationUs), playbackSpeed, rebuffering, targetLiveOffsetUs)
        }
        // with a delay set, live streams play at normal speed (catching up with the live edge would eat into the delay)
        val ls = DefaultLivePlaybackSpeedControl.Builder().build()
        val lsAvd = object : LivePlaybackSpeedControl by ls {
            override fun getAdjustedPlaybackSpeed(liveOffsetUs: Long, bufferedDurationUs: Long) =
                if (avdUs != 0L) 1f else ls.getAdjustedPlaybackSpeed(liveOffsetUs, bufferedDurationUs)
        }
        val p = ExoPlayer.Builder(this, rf.setEnableDecoderFallback(true))
            .setMediaSourceFactory(DefaultMediaSourceFactory(ds))
            .setLoadControl(lcAvd).setLivePlaybackSpeedControl(lsAvd).build()
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
            override fun onRenderedFirstFrame() { mainFrame() }
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



    val remRun: Runnable = object : Runnable { override fun run() { checkReminders(); pv.postDelayed(this, 20_000L) } }
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
            } catch (e: Exception) { logErr("Playlist load failed: " + (e.message ?: e.javaClass.simpleName)); runOnUiThread { flash("Load failed: ${e.message}", 5000) } }
        }.start()
    }


    fun vis() = channels.filter { it.url !in hidden }

    // favorited channels from every playlist that is loaded (the active one is always included)
    fun favRaw(): List<Ch> {
        val out = ArrayList<Ch>(); val seen = HashSet<String>(); val activeId = sources.getOrNull(act)?.id
        for (s in sources.toList()) {
            val l = chCache[s.id] ?: (if (s.id == activeId) channels else emptyList())
            for (c in l) if (c.url in favs && c.url !in hidden && seen.add(c.url)) out.add(c)
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
                    if (c.url in hidden) continue
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
        else if (ui.side) { ui.catSel = ui.catActive(); ui.plSel = ui.plActive(); ui.invalidate() }   // playlist picked in the side panels: keep them open
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

    // options bar next to a channel / program. ids: 0 Watch, 1 TV guide, 2 Program info, 3 Channel settings, 4 favorite, 5 hide,
    // 6 watch from start, 7 watch catch-up, 8 remind me. Watch is always first; the TV guide button is only in the channel list
    fun optIds(guide: Boolean, c: Ch? = null, p: Prog? = null): List<Int> {
        if (!guide) return listOf(0, 1, 2, 3, 4, 5)
        val now = System.currentTimeMillis()
        if (p != null && p.start > now) return listOf(0, 2, 8, 3, 4, 5)
        if (c != null && p != null && canCatchup(c, p))
            return if (p.stop <= now) listOf(7, 0, 2, 3, 4, 5) else listOf(0, 6, 2, 3, 4, 5)
        return listOf(0, 2, 3, 4, 5)
    }
    fun nOpts(guide: Boolean, c: Ch? = null, p: Prog? = null) = optIds(guide, c, p).size
    // label to icon name
    fun chOptions(c: Ch, guide: Boolean, p: Prog? = null) = optIds(guide, c, p).map { id ->
        when (id) {
            0 -> (if (guide && p != null && p.stop <= System.currentTimeMillis() && canCatchup(c, p)) "Watch Live" else "Watch") to "play"
            1 -> "TV Guide" to "guide"; 2 -> "Program Info" to "info"; 3 -> "Channel Settings" to "sliders"
            4 -> (if (c.url in favs) "Remove from Favorites" else "Add to Favorites") to "star"
            6 -> "Watch from Start" to "back15"; 7 -> "Watch Catch-up" to "back15"
            8 -> (if (p != null && hasRem(c, p)) "Remove Reminder" else "Remind Me") to "bell"
            else -> (if (c.url in hidden) "Unhide Channel" else "Hide Channel") to "hide"
        }
    }

    // hidden channels found in the playlists loaded so far (current one first), by number
    fun hiddenList(): List<Ch> = (channels.asSequence() + chCache.values.asSequence().flatten()).filter { it.url in hidden }.distinctBy { it.url }.sortedBy { it.num }.toList()
    fun unhide(c: Ch) { toggle(hidden, "hidden", c.url); selectGroup(grp, true); flash("Unhidden: ${c.name}", 2000) }

    fun toggle(set: HashSet<String>, key: String, u: String) { if (!set.remove(u)) set.add(u); prefs.edit().putStringSet(key, HashSet(set)).apply() }

    fun chOption(c: Ch, i: Int, p: Prog? = null) {
        val guide = ui.mode == 2
        when (optIds(guide, c, p).getOrNull(i)) {
            0 -> { if (c == cur) { ui.close(); switchTo(c) } else play(c) }   // 1st Watch: change channel, UI stays; 2nd Watch: close UI
            1 -> if (guide) ui.openGuide(c) else ui.openGrid(c, 1)   // from the channel list: the grid guide, BACK returns to the list
            2 -> ui.openInfo(c, p ?: now(c))
            3 -> { if (c != cur) play(c); ui.openSub() }
            4 -> { toggleFav(c.url); selectGroup(grp, true) }
            5 -> { toggle(hidden, "hidden", c.url); selectGroup(grp, true) }
            6, 7 -> if (p != null) { ui.close(); playCatchup(c, p); switchTo(c) }
            8 -> if (p != null) toggleRem(c, p)
        }
        ui.invalidate()
    }

    fun trackName(f: Format, n: Int): String {
        f.label?.let { return it }
        f.language?.let { l -> if (l != "und") { val nm = java.util.Locale(l).displayLanguage; return if (nm.isEmpty()) l else nm } }
        return "Track $n"
    }

    // options + index of the selected one for the channel-settings tiles: 0 audio, 1 subtitles, 2 aspect, 3 audio delay
    fun subOpts(i: Int): Pair<List<String>, Int> {
        if (i == 2) return Pair(arNames.toList(), arMode)
        if (i == 3) {
            val ms = subCh()?.let { avdMs(it) } ?: 0; val k = ms / 50 + 600   // AVD_MS[k] = (k - 600) * 50
            return Pair(avdNames, if (ms % 50 == 0 && k in AVD_MS.indices) k else 600)
        }
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
                arMode = k.coerceIn(0, arNames.lastIndex)
                // channels send 16:9 with any 4:3 show or wide film inside it, black bars included, so the picture itself is scaled:
                // Zoom enlarges it 1.33x (fills the screen, trims the edges), Wider / Taller stretch it one way only; subtitles stay as they are
                pv.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                (pv.videoSurfaceView?.parent as? ViewGroup)?.clipChildren = false; pv.clipChildren = false   // a real 4:3 stream's frame is narrower than the screen
                pv.videoSurfaceView?.apply { scaleX = if (arMode == 1 || arMode == 2) 4f / 3f else 1f; scaleY = if (arMode == 1 || arMode == 3) 4f / 3f else 1f }
                flash("Aspect ratio: ${arNames[arMode]}", 1500)
                return
            }
            if (i == 3) {
                val c = subCh() ?: return; val ms = AVD_MS[k.coerceIn(0, AVD_MS.lastIndex)]
                if (ms == 0) prefs.edit().remove("avd:" + c.url).apply() else prefs.edit().putInt("avd:" + c.url, ms).apply()
                if (c == cur) {
                    // the clock jumps by the change and the player won't let it run backwards (picture would freeze); a seek resets it
                    val p = pos(); avdUs = ms * 1000L
                    if (player.isCurrentMediaItemSeekable) player.seekTo(p) else player.seekToDefaultPosition()
                }
                flash("Audio delay: ${avdName(ms)}" + if (Math.abs(ms) >= 3000) "\nThe picture may pause while it catches up" else "", 2500)
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

    // ---- dialogs in the Apple TV style: rounded menu-coloured panel, faint buttons, the focused one turns white and grows ----
    fun px(v: Float) = v * resources.displayMetrics.widthPixels / 1920f   // 1920-px design units, like Ui
    fun dBg(color: Int, stroke: Int = 0, r: Float = 22f): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = px(r); if (stroke != 0) setStroke(px(2f).toInt(), stroke) }

    fun ipx(v: Float) = px(v).toInt()
    fun dEdit(h: String, icon: String = ""): EditText = EditText(this).apply {
        hint = h; setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(30f)); setSingleLine()
        setPadding(ipx(24f), ipx(18f), ipx(24f), ipx(18f))
        val ic = if (icon.isNotEmpty()) IconDrawable(icon, 0).also { it.setBounds(0, 0, ipx(30f), ipx(30f)); setCompoundDrawables(it, null, null, null); compoundDrawablePadding = ipx(16f) } else null
        fun look(f: Boolean) {   // focused field is white with dark text, like tvOS
            background = dBg(if (f) -1 else 0x1FFFFFFF); setTextColor(if (f) 0xFF1C1C1E.toInt() else -1)
            setHintTextColor(if (f) 0x8C1C1C1E.toInt() else 0x85FFFFFF.toInt())
            ic?.let { it.color = if (f) 0x8C1C1C1E.toInt() else 0x85FFFFFF.toInt(); it.invalidateSelf() }
        }
        look(false); setOnFocusChangeListener { _, f -> look(f) }
    }
    fun dText(t: String, size: Float, color: Int, tf: Typeface = Typeface.DEFAULT) = TextView(this).apply {
        text = t; setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(size)); setTextColor(color); typeface = tf; setLineSpacing(0f, 1.15f)
    }

    var panelBox: View? = null   // the open popup, so the blur can redraw it
    // pop-ups are cards on the left like the main menu: title, content, then rows like the menu's (the last one Cancel or Close)
    fun showPanel(title: String, views: List<View>, buttons: List<DBtn>, keyboard: Boolean = false, width: Float = 520f): android.app.Dialog {
        val dlg = android.app.Dialog(this); dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val ink = ui.ONF; val foc = ui.FOC; val med = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        buttons.forEachIndexed { i, b ->
            val ic = IconDrawable(b.icon, -1)
            val name = dText(b.label, 34f, -1, med).apply { setSingleLine() }
            val tip = dText(b.tip, 24f, if (ui.FOC == -1) 0xA81C1C1E.toInt() else ui.ONF2).apply { visibility = View.GONE; setPadding(0, ipx(4f), ipx(24f), 0) }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; minimumHeight = ipx(80f); setPadding(ipx(26f), ipx(19f), 0, ipx(19f))
                isFocusable = true; isClickable = true
                addView(ImageView(this@MainActivity).apply { setImageDrawable(ic) }, LinearLayout.LayoutParams(ipx(34f), ipx(34f)).apply { topMargin = ipx(4f); rightMargin = ipx(24f) })
                addView(LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; addView(name); addView(tip) }, LinearLayout.LayoutParams(0, -2, 1f))
                fun look(f: Boolean) {   // the selected row turns white and grows a little, and shows its tip
                    background = if (f) dBg(foc) else null
                    name.setTextColor(if (f) ink else -1); name.typeface = if (f) Typeface.DEFAULT_BOLD else med
                    ic.color = if (f) ink else -1; ic.invalidateSelf()
                    tip.visibility = if (f && b.tip.isNotEmpty()) View.VISIBLE else View.GONE
                    animate().scaleX(if (f) 1.04f else 1f).scaleY(if (f) 1.04f else 1f).setDuration(120).start()
                    elevation = if (f) px(10f) else 0f
                }
                look(false); setOnFocusChangeListener { _, f -> look(f) }
                setOnClickListener { b.act(dlg) }
            }
            rows.addView(row, LinearLayout.LayoutParams(-1, -2).apply { if (i > 0) topMargin = ipx(10f) })
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = MatBg(this@MainActivity, this, 40f)
            clipChildren = false; clipToPadding = false; setPadding(0, ipx(44f), 0, ipx(20f))
            fun lp(top: Float) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = ipx(top); leftMargin = ipx(40f); rightMargin = ipx(40f) }
            // title row like Ui.head: the round ‹ (selectable, OK closes) in the rows' icon column, the title beside it
            val bic = IconDrawable("back", -1)
            val back = android.widget.FrameLayout(this@MainActivity).apply {
                tag = "back"; isFocusable = true; isClickable = true; setOnClickListener { dlg.dismiss() }
                addView(ImageView(this@MainActivity).apply { setImageDrawable(bic); setPadding(ipx(14f), ipx(14f), ipx(16f), ipx(14f)) }, -1, -1)
                fun look(f: Boolean) { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (f) foc else 0x24FFFFFF) }
                    bic.color = if (f) ink else -1; bic.invalidateSelf(); animate().scaleX(if (f) 1.08f else 1f).scaleY(if (f) 1.08f else 1f).setDuration(120).start() }
                look(false); setOnFocusChangeListener { _, f -> look(f) }
            }
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false
                addView(back, LinearLayout.LayoutParams(ipx(56f), ipx(56f)))
                addView(dText(title, 44f, -1, Typeface.DEFAULT_BOLD), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = ipx(18f) })
            }, LinearLayout.LayoutParams(-1, -2).apply { leftMargin = ipx(36f); rightMargin = ipx(40f) })
            views.forEachIndexed { i, v -> addView(v, lp(if (i == 0) 28f else 22f)) }
            addView(View(this@MainActivity).apply { setBackgroundColor(0x14FFFFFF) }, LinearLayout.LayoutParams(-1, maxOf(1, ipx(1f))).apply { topMargin = ipx(26f); leftMargin = ipx(40f); rightMargin = ipx(40f) })
            addView(rows, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ipx(14f); leftMargin = ipx(20f); rightMargin = ipx(20f) })
        }
        dlg.setContentView(box)
        dlg.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(ipx(width), -2)
            attributes = attributes.apply { gravity = Gravity.START or Gravity.TOP; x = ipx(36f); y = ipx(100f) }   // the left edge and top (Ui.PT), like every other card
            if (keyboard) setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        panelBox = box; dlg.show(); ui.blur.start()
        // Christmas: the pop-up's card collects snow too (it lives in its own window, so it tells the snow layer where it is)
        val snowAt = { val l = IntArray(2); box.getLocationOnScreen(l); snow.popup(dlg, l[0].toFloat(), l[1].toFloat(), box.width.toFloat(), box.height.toFloat(), px(40f)) }
        box.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> snowAt() }
        box.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) { snow.popupGone(dlg) }
        })
        box.post { box.findFocus()?.takeIf { it.tag == "back" }?.let { box.focusSearch(it, View.FOCUS_DOWN)?.requestFocus() } }   // start on the first field or row, not the back button   // the blur keeps refreshing while the popup is open
        return dlg
    }

    fun searchBox() {
        val input = dEdit("Channel or show", "search"); var d: android.app.Dialog? = null
        fun go() { val q = input.text.toString().trim(); d?.dismiss(); if (q.isNotEmpty()) search(q) }
        input.setOnEditorActionListener { _, _, _ -> go(); true }
        d = showPanel("Search", listOf(input, dText("Finds channels and shows on now", 24f, 0xA3FFFFFF.toInt())), listOf(
            DBtn("Search", C_PUR, "search") { go() }), keyboard = true)
    }

    // main menu (OK button); the number is the icon id
    fun menuItems() = listOf("search" to "Search", "phone" to "Playlists", "grid" to "TV Guide", "bell" to "Reminders", "hide" to "Hidden Channels", "gear" to (if (updState == 3 || updState == 5) "Settings  \u25CF Update" else "Settings"))

    fun menuAct(i: Int) {
        when (i) {
            0 -> { ui.close(); searchBox() }
            1 -> settings()   // the pop-up sits over the menu; closing it goes back to the menu
            2 -> (cur ?: shown.firstOrNull())?.let { ui.openGrid(it, 3) }   // the grid guide; RIGHT while watching opens the quick guide
            3 -> ui.openReminders()
            4 -> ui.openHidden()   // "Show Hidden" became this page
            5 -> ui.openSettings()   // Last Channel left (Back does it); Remote Buttons moved into Settings
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
        if (updState == 1 || updState == 4 || (silent && updState == 5)) return
        val before = updState; updState = 1; updRefresh()
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
                // remember what was found, so a restart still shows it in the menu and Settings
                when (updState) {
                    2 -> prefs.edit().remove("updTag").remove("updUrl").apply()
                    3 -> prefs.edit().putString("updTag", updTag).putString("updUrl", updUrl).apply()
                }
            } catch (e: Exception) { updErr = e.message ?: "No connection"; updState = 6 }
            if (updState == 6) logErr("Update check failed: $updErr")
            if (updState == 2 || updState == 3) prefs.edit().putLong("updAt", System.currentTimeMillis()).apply()
            if (silent && updState == 6) updState = if (before == 3) 3 else 0   // offline: keep a known update
            if (silent && updState == 3) runOnUiThread { flash("New version $updTag is available. Open Settings > App update to install it", 15000) }
            updRefresh()
        }.start()
    }

    // quiet check for a newer version: every time the app starts, then about every 12 hours while it is on screen
    var updStarted = false
    fun autoCheckUpdate() {
        if (updStarted && System.currentTimeMillis() - prefs.getLong("updAt", 0L) < 12 * 3600_000L) return
        updStarted = true
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
                flash("Allow LiteTV to install apps, then go back and press OK again", 5000)
                startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:$packageName")))
                return
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { flash("Could not open the installer: ${e.message}", 5000) }
    }

    fun now(c: Ch): Prog? { val t = System.currentTimeMillis(); return progsOf(c).firstOrNull { t >= it.start && t < it.stop } }
    fun next(c: Ch): Prog? { val n = now(c) ?: return null; return progsOf(c).firstOrNull { it.start >= n.stop } }

    var catchupProg: Prog? = null   // the past programme that is playing right now (null = live)
    var catchupSrc: String? = null   // the link of the catch-up that is playing (the seek bar uses it to ask for preview pictures)

    // info: show the channel info for a few seconds (only for Channel Up / Down and typed numbers, not BACK or swaps)
    fun play(c: Ch, info: Boolean = false) = startPlay(c, c.url, null, info)

    fun startPlay(c: Ch, url: String, cp: Prog?, info: Boolean = false) {
        if (cur != null && cur != c) prev = cur
        cur = c; catchupProg = cp
        catchupSrc = if (cp != null) url else null
        if (cp != null) { catchupBar.resetThumbs(); catchupBar.target = -1L; catchupBar.show() } else catchupBar.visibility = View.GONE
        errShown = false; retryN = 0; hideLoading(); clearMid(); pv.removeCallbacks(retryRun); pv.removeCallbacks(loadRun)
        osd.removeCallbacks(hideOsd); osd.visibility = View.GONE
        val held = cp == null && pip.ch?.url == c.url && !pip.parked   // the pinned channel: it already plays in the corner
        showHeld(if (held) c else null)
        if (held) { player.stop(); player.clearMediaItems() } else {
        val item = MediaItem.Builder().setUri(url).apply {
            // catch-up links have no .m3u8 extension, so tell ExoPlayer it is HLS
            if (cp != null) setMimeType(MimeTypes.APPLICATION_M3U8)
        }.build()
        player.setMediaItem(item); player.prepare(); player.playWhenReady = true
        }
        // set the new delay on the playback thread after the switch, so the old stream's last frames keep their clock
        val us = avdMs(c) * 1000L; player.createMessage { _, _ -> avdUs = us }.send()
        if (cp == null) {
            prefs.edit().putString("last", c.url).apply()
            if (info && (ui.mode == 0 || (ui.mode == 4 && ui.backMode == 0))) ui.openSubPlayer(true)   // channel settings overlay, not while the lists are open
        }
    }

    // a programme can be rewatched when its channel has catch-up, it has started, and it is not older than the channel's catch-up days
    fun dayOf0(): Long = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }.timeInMillis   // start of today
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
        if (player.isCurrentMediaItemSeekable) { player.seekTo(maxOf(0L, pos() + ms)); flash(if (ms > 0) "+${ms / 1000} s" else "${ms / 1000} s", 1000) }
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

    // a notice: the words before ": " in white, the rest in grey, with an icon that fits the message
    fun flash(t: String, ms: Long = 2500) {
        val k = t.indexOf(": ").let { if (it < 0 || t.indexOf('\n').let { n -> n in 0 until it }) t.length else it }
        osd.text = android.text.SpannableString(t.replace(": ", "  ")).apply {
            setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, k, 0)
            if (k < length) setSpan(android.text.style.ForegroundColorSpan(0xA3FFFFFF.toInt()), k, length, 0)
        }
        val ic = when { t.startsWith("Reminder") -> "bell"; t.startsWith("Sleep") -> "moon"; t.startsWith("Aspect") -> "aspect"; t.startsWith("Audio delay") -> "delay"
            t.contains("EPG") || t.contains("source") || t.startsWith("Load failed") -> "refresh"; t.contains("version") -> "down"; else -> "info" }
        osd.setCompoundDrawables(IconDrawable(ic, -1).apply { setBounds(0, 0, px(30f).toInt(), px(30f).toInt()) }, null, null, null)
        val wasOn = osd.visibility == View.VISIBLE
        numV.visibility = View.GONE; osd.visibility = View.VISIBLE; osd.bringToFront()   // above the menus, e.g. while changing subtitles
        if (!wasOn) popIn(osd)
        osd.removeCallbacks(hideOsd); osd.postDelayed(hideOsd, ms)
    }

    // ---------- pinned channel (Pip), Apple PiP style ----------
    // the big picture while the pinned channel itself is chosen: black, a pin, "<name> is pinned" (null = hide)
    fun showHeld(c: Ch?) {
        if (c == null) { heldV.visibility = View.GONE; return }
        val t = "${c.name} is pinned\nIt plays in the corner. Change channel, or press OK to watch it full screen."
        heldV.text = android.text.SpannableString(t).apply {
            val k = t.indexOf('\n'); setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, k, 0)
            setSpan(android.text.style.ForegroundColorSpan(0x99FFFFFF.toInt()), k, t.length, 0); setSpan(android.text.style.RelativeSizeSpan(0.65f), k, t.length, 0) }
        heldV.setCompoundDrawables(null, IconDrawable("pin", 0x99FFFFFF.toInt()).apply { setBounds(0, 0, px(96f).toInt(), px(96f).toInt()) }, null, null)
        heldV.visibility = View.VISIBLE
    }
    fun held() = heldV.visibility == View.VISIBLE
    // a still of the main picture (Android 7+), for the picture that shrinks while the stream moves to the corner
    fun snapMain(done: (Bitmap?) -> Unit) {
        val sv = pv.videoSurfaceView as? SurfaceView
        if (sv == null || android.os.Build.VERSION.SDK_INT < 24 || sv.width == 0 || player.videoSize.width == 0) { done(null); return }
        val b = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        try { android.view.PixelCopy.request(sv, b, { r -> done(if (r == android.view.PixelCopy.SUCCESS) b else null) }, android.os.Handler(mainLooper)) } catch (e: Exception) { done(null) }
    }
    // runs once when the main player shows its next first picture (or after 8 s, so a stream that fails cannot strand the card)
    var onMainFrame: (() -> Unit)? = null
    val mainFrameLate = Runnable { mainFrame() }
    fun mainFrame() { pv.removeCallbacks(mainFrameLate); onMainFrame?.let { onMainFrame = null; it() } }
    fun afterMainFrame(f: () -> Unit) { onMainFrame = f; pv.removeCallbacks(mainFrameLate); pv.postDelayed(mainFrameLate, 8000L) }

    // Pin: the picture shrinks into the corner; the big picture becomes the "pinned" notice (one stream, not two)
    fun pinCh(c: Ch) {
        ui.close()
        snapMain { b -> pip.pinFromFull(c, b); player.stop(); player.clearMediaItems(); showHeld(c) }
    }
    // OK on the notice (or Unpin while on it): the corner picture grows to full screen and the pin ends
    fun pinFull() {
        val c = pip.ch ?: return; ui.close()
        pip.growFull { afterMainFrame { pip.drop() }; play(c) }   // play() runs with pip parked, so it opens the real stream under the card
    }
    // Swap: the corner picture grows to full screen while the big one shrinks into the corner, then they trade players
    fun swapPin() {
        val p = pip.ch ?: return; val c = cur ?: return
        if (held()) { pinFull(); return }
        ui.close()
        snapMain { b ->
            val g = ghost.getChildAt(0) as ImageView; g.setImageBitmap(b); ghost.visibility = View.VISIBLE; ghost.setBox(ghost.full(), 0f)
            ghost.animBox(pip.cornerBox(), 18 * pip.u(), 450)
            pip.growFull {
                afterMainFrame { pip.becomeCorner(c, b); ghost.visibility = View.GONE; g.setImageBitmap(null) }
                play(p); switchTo(p)
            }
        }
    }
    fun unpin() { if (held()) pinFull() else pip.unpin() }

    fun zap(d: Int) {
        val l = if (shown.isNotEmpty()) shown else channels; if (l.isEmpty()) return
        val i = l.indexOf(cur); play(l[((if (i < 0) 0 else i) + d + l.size) % l.size], true)
    }

    var dlg: android.app.Dialog? = null
    var srv: java.net.ServerSocket? = null


    fun settings() {
        startServer()
        val ip = localIp(); val url = ip?.let { "http://$it:$PORT" }
        // QR code on the left, beside the biggest text (what to do); the address is the small fallback
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (url != null) {
                addView(dText("Manage from your phone", 34f, -1, Typeface.DEFAULT_BOLD))
                addView(dText("Scan with your phone camera", 25f, 0xA3FFFFFF.toInt()).apply { setPadding(0, ipx(10f), 0, 0) })
                addView(dText("or open " + url.removePrefix("http://"), 22f, 0x85FFFFFF.toInt()).apply { setPadding(0, ipx(6f), 0, 0) })
            } else addView(dText("No network", 34f, -1, Typeface.DEFAULT_BOLD))
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            if (url != null) addView(ImageView(this@MainActivity).apply {
                setImageBitmap(qr(url, 300)); background = dBg(-1, 0, 14f); setPadding(ipx(12f), ipx(12f), ipx(12f), ipx(12f))
            }, LinearLayout.LayoutParams(ipx(200f), ipx(200f)).apply { rightMargin = ipx(30f) })
            addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        }
        val d = showPanel("Playlists & TV Guides", listOf(body), listOf(
            DBtn("Add Playlist Here", C_PUR, "plus", "Type the address with the remote instead") { it.dismiss(); addSource() },
            DBtn("Reload", C_FIELD, "refresh", "Downloads the playlists, guides and logos again. If a download fails, the old copy is kept.") { it.dismiss(); Thread { clearLogoAndGuideCaches(); activate(act, true) }.start() }), width = 680f)
        d.setOnDismissListener { stopServer(); if (ui.mode == 3) { ui.touch(); ui.requestFocus() } }
        dlg = d
    }

    fun addSource() {
        val n = dEdit("Name"); val m = dEdit("M3U URL"); val e = dEdit("EPG URL (optional)")
        showPanel("Add Playlist", listOf(n, m, e), listOf(
            DBtn("Add", C_PUR, "plus") { d ->
                d.dismiss()
                if (m.text.isNotBlank()) {
                    val sid = newId(); val nm = n.text.toString().trim().ifEmpty { "Source ${sources.size + 1}" }
                    sources = sources + Src(sid, nm, m.text.toString().trim())
                    if (e.text.isNotBlank()) { epgs = epgs + Epg("o_$sid", "$nm EPG", e.text.toString().trim(), false, listOf(sid), sid); saveEpgs(epgs) }
                    saveSrcs(sources); activate(sources.lastIndex, true)
                }
            }), width = 680f, keyboard = true)
    }

    val numRun = Runnable { numV.visibility = View.GONE; val n = numBuf.toIntOrNull(); numBuf = ""; if (n != null) channels.firstOrNull { it.num == n }?.let { if (it != cur) play(it, true) } }   // typing the channel already on does nothing

    // BACK is handled here (before any view sees it): a short press goes back, holding it exits the app completely
    var backLong = false; var okHold = false; var okLong = false
    override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
        if (sleepWarned && ev.action == KeyEvent.ACTION_DOWN) { setSleep(0); return true }
        // while watching (or on the overlay after a channel change) holding OK opens the main menu, so remotes without a Menu
        // button can get there; a short press does OK's own job when it is let go
        val okKey = ev.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || ev.keyCode == KeyEvent.KEYCODE_ENTER
        if (okKey && (okHold || ui.mode == 16 || (catchupProg == null && (ui.mode == 0 || (ui.mode == 4 && ui.backMode == 0))))) {
            if (ev.action == KeyEvent.ACTION_DOWN) {
                if (ev.repeatCount == 0) { okHold = true; okLong = false }
                else if (okHold && !okLong && (ev.isLongPress || ev.repeatCount >= 5)) { okLong = true; if (ui.mode == 16) ui.gridOk(true) else ui.openMenu() }
            } else if (ev.action == KeyEvent.ACTION_UP) {
                val short = okHold && !okLong; okHold = false; okLong = false
                if (short) onKeyDown(ev.keyCode, KeyEvent(KeyEvent.ACTION_DOWN, ev.keyCode))
            }
            return true
        }
        if (ev.keyCode == KeyEvent.KEYCODE_BACK) {
            if (ev.action == KeyEvent.ACTION_DOWN) {
                if (ev.repeatCount == 0) backLong = false
                else if (!backLong && (ev.isLongPress || ev.repeatCount >= 5)) { backLong = true; if (pip.ch != null) { unpin(); flash("Unpinned: hold BACK again to exit the app", 3000) } else exitApp() }   // with a pin, the first hold only unpins
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
        // with a channel pinned, BACK swaps with it (on its own "pinned" notice: back to the channel before)
        if (pip.ch != null && !held()) { swapPin(); return }
        if (prev != null) prev?.let { play(it) } else flash("Hold BACK to exit the app", 2500)
    }

    // closes the app for real and frees all of its memory
    lateinit var keys: RemoteKeys
    fun exitApp() { exiting = true; finishAndRemoveTask() }

    override fun onKeyDown(k: Int, e: KeyEvent): Boolean {
        // Swap Pinned also works over the info that shows for a few seconds after a channel change
        if ((ui.mode == 0 || (ui.mode == 4 && ui.backMode == 0)) && keys.actFor(k)?.id == "swap") {
            if (pip.ch == null) flash("Nothing pinned: press OK and choose Pin to pin the channel you are watching", 4000) else swapPin(); return true }
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
        when (keys.actFor(k)?.id) {   // the viewer's button choices (Remote Buttons)
            "menu" -> { ui.openMenu(); return true }
            "settings" -> { if (held()) pinFull() else if (cur != null) ui.openSubPlayer(false); return true }   // on the "pinned" notice OK watches it full screen
            "chUp" -> { zap(1); return true }
            "chDown" -> { zap(-1); return true }
            "list" -> { ui.open(1); return true }
            "guide" -> { (cur ?: shown.firstOrNull())?.let { ui.openGrid(it, 0) }; return true }
            "quick" -> { ui.open(2); return true }
            "last" -> { prev?.let { play(it) }; return true }
            "pause" -> { player.playWhenReady = !player.playWhenReady; return true }
            "fwd" -> { skip(30000L); return true }
            "rew" -> { skip(-30000L); return true }
        }
        when (k) {
            KeyEvent.KEYCODE_MEDIA_PLAY -> player.playWhenReady = true
            KeyEvent.KEYCODE_MEDIA_PAUSE -> player.playWhenReady = false
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                if (catchupProg != null) return true   // catch-up ignores the number keys, so a stray press can't leave it
                val d = k - KeyEvent.KEYCODE_0
                if (d == 0 && numBuf.isEmpty()) prev?.let { play(it) }
                else { numBuf += d; numV.text = numBuf; osd.visibility = View.GONE; if (numV.visibility != View.VISIBLE) { numV.visibility = View.VISIBLE; popIn(numV) }; numV.bringToFront(); pv.removeCallbacks(numRun); pv.postDelayed(numRun, 1500) }
            }
            else -> return super.onKeyDown(k, e)
        }
        return true
    }

    override fun onStop() { super.onStop(); if (catchupProg == null) player.stop() else player.pause(); pip.stop();   // live: let go of the hardware decoders while in the background; catch-up keeps its position
        stopServer(); pv.removeCallbacks(epgTick); pv.removeCallbacks(updTick); pv.removeCallbacks(remRun) }
    override fun onStart() {
        super.onStart(); pip.start(); if (cur != null) { if (player.playbackState == Player.STATE_IDLE) { player.prepare(); player.playWhenReady = true } else player.play() }
        pv.removeCallbacks(updTick); pv.postDelayed(updTick, 30_000L)
        pv.removeCallbacks(remRun); pv.postDelayed(remRun, 5_000L)
        pv.removeCallbacks(epgTick); pv.postDelayed(epgTick, 60_000L)   // coming back from Home: refresh the guide a minute later if it is older than the refresh setting
    }
    // pressing Home: give back the memory that is only a cache (logos, programmes read for the screen); they are re-read when needed
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) { logos.evictAll(); progCache.evictAll(); descCache.evictAll(); hitKey.clear() }
    }
    override fun onDestroy() {
        super.onDestroy(); player.release(); pip.drop()
        if (exiting) { try { epgDb.close() } catch (e: Exception) {}; android.os.Process.killProcess(android.os.Process.myPid()) }
    }
}