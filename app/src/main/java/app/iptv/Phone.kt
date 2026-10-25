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

// the phone web page (QR code): playlists, shared EPGs, favorites order, backup / restore, and the tiny HTTP server behind it
fun MainActivity.localIp(): String? {
    try { for (n in java.net.NetworkInterface.getNetworkInterfaces()) for (a in n.inetAddresses)
        if (!a.isLoopbackAddress && a is java.net.Inet4Address) return a.hostAddress } catch (e: Exception) {}
    return null
}

fun MainActivity.qr(s: String, n: Int = 360): android.graphics.Bitmap {
    val m = com.google.zxing.qrcode.QRCodeWriter().encode(s, com.google.zxing.BarcodeFormat.QR_CODE, n, n)
    val bmp = android.graphics.Bitmap.createBitmap(n, n, android.graphics.Bitmap.Config.RGB_565)
    for (x in 0 until n) for (y in 0 until n) bmp.setPixel(x, y, if (m[x, y]) -16777216 else -1)
    return bmp
}

fun MainActivity.esc(s: String) = s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")

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

fun MainActivity.page(msg: String?): String {
    val json = JSONArray().apply { sources.forEach { sc -> put(JSONObject().put("i", sc.id).put("n", sc.name).put("m", sc.m3u).put("e", epgs.firstOrNull { e -> e.owner == sc.id }?.url ?: "")) } }.toString().replace("</", "<\\/")
    return """<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'><meta name=color-scheme content=dark><title>LiteTV playlists</title>$iconLink
$css<body><div class=hd>$logoSvg<h2>Lite<span>TV</span> playlists</h2></div><a href=/epg>Shared EPGs &rarr;</a> &nbsp;&nbsp; <a href=/favs>Favorites order &rarr;</a>${if (msg != null) "<p class=msg>$msg</p>" else ""}
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

fun MainActivity.epgPage(msg: String?): String {
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

// phone page: drag the favorite groups into the order you want; every change is saved on the TV straight away
fun MainActivity.favsPage(): String {
    val raw = favRaw(); val cnt = HashMap<String, Int>(); raw.forEach { c -> val k = favKeyL(c); cnt[k] = (cnt[k] ?: 0) + 1 }
    val g = JSONArray(); favGroupsOrdered(raw).forEach { (k, n) -> g.put(JSONObject().put("k", k).put("n", n).put("c", cnt[k] ?: 0)) }
    val json = g.toString().replace("</", "<\\/")
    val note = if (favSort == 0) "Drag a group by the handle, or use Top / the arrows. The TV updates as soon as you let go. Top of this list = lowest channel numbers."
    else "The TV sorts favorites by ${FAV_SORTS[favSort]} right now. This order is saved, but it is only used when Favorites sort is Custom groups (TV: OK, Settings)."
    return """<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'><meta name=color-scheme content=dark><title>LiteTV favorites order</title>$iconLink
$css<style>.nm{flex:1;font-weight:bold;font-size:18px}.cnt{color:#B8BECC;margin-right:12px;font-size:14px}.fix{opacity:.6}.card .top{margin-bottom:0}</style>
<body><div class=hd>$logoSvg<h2>Lite<span>TV</span> favorites order</h2></div><a href=/>&larr; Playlists</a>
<p class=sub>$note</p><p id=ms class=msg style='display:none;position:sticky;top:8px;z-index:5'></p>
<div id=rows></div>
<script>
var G=$json,R=document.getElementById('rows'),M=document.getElementById('ms'),tm;
function keys(){return Array.prototype.map.call(R.children,function(d){return d.dataset.k})}
function say(s){M.style.display='block';M.textContent=s;clearTimeout(tm);tm=setTimeout(function(){M.style.display='none'},1800)}
function save(){fetch('/favs',{method:'POST',body:JSON.stringify(keys())}).then(function(r){return r.text()}).then(function(x){say(x=='ok'?'Saved on TV':x)}).catch(function(){say('Could not reach the TV')})}
function fixed(d){return d.dataset.k=='other'}
function toTop(b){var d=b.closest('.card');if(d!=R.firstElementChild){R.insertBefore(d,R.firstElementChild);save()}}
function up(b){var d=b.closest('.card'),p=d.previousElementSibling;if(p){R.insertBefore(d,p);save()}}
function dn(b){var d=b.closest('.card'),n=d.nextElementSibling;if(n&&!fixed(n)){R.insertBefore(n,d);save()}}
function drag(ev,d){ev.preventDefault();var h=ev.target,b0=keys().join();h.setPointerCapture(ev.pointerId);d.classList.add('drag');
h.onpointermove=function(e){var p=d.previousElementSibling,n=d.nextElementSibling;
if(e.clientY<60)scrollBy(0,-12);if(e.clientY>innerHeight-60)scrollBy(0,12);
if(p){var a=p.getBoundingClientRect();if(e.clientY<a.top+a.height/2)R.insertBefore(d,p)}
if(n&&!fixed(n)){var c=n.getBoundingClientRect();if(e.clientY>c.top+c.height/2)R.insertBefore(n,d)}};
h.onpointerup=h.onpointercancel=function(){d.classList.remove('drag');h.onpointermove=null;if(keys().join()!=b0)save()}}
function add(g){var d=document.createElement('div'),f=g.k=='other';d.className='card'+(f?' fix':'');d.dataset.k=g.k;
d.innerHTML='<div class=top>'+(f?'':'<span class=hdl>&#9776;</span>')+'<span class=nm></span><span class=cnt></span>'+(f?'':'<span><button type=button class=a onclick="toTop(this)">Top</button><button type=button class=a onclick="up(this)">&#9650;</button><button type=button class=a onclick="dn(this)">&#9660;</button></span>')+'</div>';
d.querySelector('.nm').textContent=g.n;d.querySelector('.cnt').textContent=g.c+(g.c==1?' channel':' channels');
var h=d.querySelector('.hdl');if(h)h.onpointerdown=function(ev){drag(ev,d)};R.appendChild(d)}
G.forEach(add);
if(!G.length){var p=document.createElement('p');p.className='sub';p.textContent='No favorites yet. Add channels on the TV with "Add to favorites".';R.appendChild(p)}
</script>"""
}

// the phone sends the new group order as a JSON list of group keys
fun MainActivity.saveFavOrderFromPhone(txt: String): String = try {
    val a = JSONArray(txt); val ks = (0 until a.length()).map { a.getString(it) }
    runOnUiThread { setFavGroupOrder(ks); applyFavSort() }
    "ok"
} catch (e: Exception) { "Could not read the order" }

// backup = playlists + EPGs + favorites (in the order they were added) + favorites sort settings, read from the saved settings (safe to call from the web server thread)
fun MainActivity.backupJson(): String {
    val fv = prefs.getStringSet("favs", emptySet()) ?: emptySet()
    val ord = ArrayList<String>()
    try { val a = JSONArray(prefs.getString("favSeq", "[]")); for (k in 0 until a.length()) { val u = a.getString(k); if (u in fv && u !in ord) ord.add(u) } } catch (e: Exception) {}
    fv.filter { it !in ord }.sorted().forEach { ord.add(it) }
    return JSONObject().put("app", "LiteTV").put("v", 2)
        .put("sources", JSONArray(prefs.getString("srcs", "[]")))
        .put("epgs", JSONArray(prefs.getString("epgs", "[]")))
        .put("favs", JSONArray(ord))
        .put("favSort", prefs.getInt("favSort", 0)).put("favInner", prefs.getInt("favInner", 2))
        .put("favGroups", JSONArray(prefs.getString("favGroups", "[]")))
        .put("hidden", JSONArray((prefs.getStringSet("hidden", emptySet()) ?: emptySet()).sorted()))
        .put("theme", prefs.getInt("theme", 0)).put("cA", prefs.getInt("cA", 12)).put("cP", prefs.getInt("cP", 12)).put("cH", prefs.getInt("cH", 12))
        .put("font", prefs.getInt("fontSize", 1)).put("season", prefs.getBoolean("season", true))
        .put("epgH", prefs.getInt("epgH", 12)).put("epgD", prefs.getInt("epgD", 7)).put("plD", prefs.getInt("plD", 7)).put("buf", prefs.getInt("buf", 0))
        .toString(2)
}

// replaces playlists, EPGs and favorites with the ones in a backup file; returns an error text, or null when it worked
fun MainActivity.restoreBackup(txt: String): String? {
    try {
        val o = JSONObject(txt)
        if (o.optString("app") != "LiteTV") return "This is not a LiteTV backup file"
        val sa = o.optJSONArray("sources") ?: return "The file has no playlists"
        val ea = o.optJSONArray("epgs") ?: JSONArray()
        val fa = o.optJSONArray("favs") ?: JSONArray()
        val fv = LinkedHashSet<String>(); for (k in 0 until fa.length()) fv.add(fa.getString(k))
        val ga = o.optJSONArray("favGroups") ?: JSONArray()
        prefs.edit().putString("srcs", sa.toString()).putString("epgs", ea.toString()).putStringSet("favs", HashSet(fv))
            .putString("favSeq", JSONArray(fv.toList()).toString()).putString("favGroups", ga.toString())
            .putInt("favSort", o.optInt("favSort", 0).coerceIn(0, FAV_SORTS.lastIndex)).putInt("favInner", o.optInt("favInner", 2).coerceIn(0, FAV_INNER.lastIndex)).apply()
        // hidden channels and settings (older backup files do not have them: those settings stay as they are)
        val ed = prefs.edit()
        o.optJSONArray("hidden")?.let { ha -> val hs = HashSet<String>(); for (k in 0 until ha.length()) hs.add(ha.getString(k)); ed.putStringSet("hidden", hs) }
        if (o.has("theme")) ed.putInt("theme", o.optInt("theme", 0).coerceIn(0, THEMES.size)).putInt("cA", o.optInt("cA", 12).coerceIn(0, SW - 1)).putInt("cP", o.optInt("cP", 12).coerceIn(0, SW - 1)).putInt("cH", o.optInt("cH", 12).coerceIn(0, SW - 1))
        if (o.has("font")) ed.putInt("fontSize", o.optInt("font", 1).coerceIn(0, FONT_NAMES.lastIndex))
        if (o.has("season")) ed.putBoolean("season", o.optBoolean("season", true))
        if (o.has("epgH")) ed.putInt("epgH", o.optInt("epgH", 12))
        if (o.has("epgD")) ed.putInt("epgD", o.optInt("epgD", 7).coerceIn(1, 30))
        if (o.has("plD")) ed.putInt("plD", o.optInt("plD", 7).coerceIn(0, 60))
        if (o.has("buf")) ed.putInt("buf", o.optInt("buf", 0).coerceIn(0, BUF_NAMES.lastIndex))
        ed.apply()
        runOnUiThread {
            val curId = sources.getOrNull(act)?.id
            sources = readSrcs(); epgs = readEpgs(); favs.clear(); favs.addAll(fv); loadFavPrefs()
            hidden.clear(); hidden.addAll(prefs.getStringSet("hidden", emptySet())!!)
            loadTheme(); refreshTheme()
            fontIdx = prefs.getInt("fontSize", 1).coerceIn(0, FONT_NAMES.lastIndex); seasonOn = prefs.getBoolean("season", true)
            epgHours = prefs.getInt("epgH", 12); epgDaysN = prefs.getInt("epgD", 7).coerceIn(1, 30); plDays = prefs.getInt("plD", 7).coerceIn(0, 60)
            val ob = bufIdx; bufIdx = prefs.getInt("buf", 0).coerceIn(0, BUF_NAMES.lastIndex); if (ob != bufIdx) rebuildPlayer()
            act = maxOf(0, sources.indexOfFirst { it.id == curId })
            if (sources.isEmpty()) settings() else activate(act, true)
        }
        return null
    } catch (e: Exception) { return "Could not read the file" }
}

fun MainActivity.startServer() {
    if (srv != null) return
    Thread {
        try {
            val s = java.net.ServerSocket(PORT); srv = s
            while (true) { val c = s.accept(); Thread { handle(c) }.start() }
        } catch (e: Exception) {}
    }.start()
}

fun MainActivity.stopServer() { try { srv?.close() } catch (e: Exception) {}; srv = null }

fun MainActivity.handle(c: java.net.Socket) {
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
                if (path.startsWith("/favs")) { send(saveFavOrderFromPhone(String(buf, 0, r, Charsets.UTF_8)), "text/plain; charset=utf-8"); return@use }
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
            } else if (path.startsWith("/favs")) {
                send(favsPage())
            } else if (path.startsWith("/backup")) {
                send(backupJson(), "application/json; charset=utf-8", "Content-Disposition: attachment; filename=\"litetv-backup.json\"\r\n")
            } else send(if (epgPg) epgPage(null) else page(if (path.contains("restored")) "Backup restored. Loading on TV..." else null))
        }
    } catch (e: Exception) {}
}
