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

// the TV guide lives on disk in this small database (not in RAM): one row per programme
class EpgDbHelper(ctx: Context) : SQLiteOpenHelper(ctx, "epg.db", null, 1) {
    override fun onConfigure(db: SQLiteDatabase) { super.onConfigure(db); db.enableWriteAheadLogging() }   // reading never waits for an import
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE p(src TEXT, ch TEXT, s INTEGER, e INTEGER, t TEXT, d TEXT)")
        db.execSQL("CREATE INDEX pi ON p(ch, s)")
    }
    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) { db.execSQL("DROP TABLE IF EXISTS p"); onCreate(db) }
}
