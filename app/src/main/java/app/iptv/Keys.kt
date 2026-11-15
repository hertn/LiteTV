package app.iptv

import android.content.SharedPreferences
import android.view.KeyEvent as K

// what the remote's buttons do while watching (Remote Buttons page in the main menu). Each action has default buttons;
// the viewer can add more. A button belongs to one action at a time, so adding it somewhere moves it.
class KeyAct(val id: String, val name: String, val desc: String, val def: List<Int>, val fixed: String = "")

val KEY_ACTS = listOf(
    KeyAct("menu", "Open Main Menu", "Opens the main menu with Search, Playlists, TV Guide, Reminders, Hidden Channels and Settings. Holding OK always works too, so a remote without a Menu button can still get here.",
        listOf(K.KEYCODE_MENU), "Hold OK"),
    KeyAct("settings", "Channel Settings", "Audio, subtitles, aspect ratio and audio delay for the channel you are watching.",
        listOf(K.KEYCODE_DPAD_CENTER, K.KEYCODE_ENTER, K.KEYCODE_INFO)),
    KeyAct("chUp", "Channel Up", "The next channel up the list.", listOf(if (REVERSED) K.KEYCODE_DPAD_UP else K.KEYCODE_DPAD_DOWN, K.KEYCODE_CHANNEL_UP)),
    KeyAct("chDown", "Channel Down", "The next channel down the list.", listOf(if (REVERSED) K.KEYCODE_DPAD_DOWN else K.KEYCODE_DPAD_UP, K.KEYCODE_CHANNEL_DOWN)),
    KeyAct("list", "Channel List", "Opens the channel list. Press it again inside the list for the categories and playlists.", listOf(K.KEYCODE_DPAD_LEFT)),
    KeyAct("guide", "TV Guide", "Opens the TV guide grid: what is on now and later on every channel in your list.", listOf(K.KEYCODE_GUIDE, K.KEYCODE_PROG_BLUE)),
    KeyAct("quick", "Quick Guide", "Opens the quick guide: one channel at a time with its shows, a fast way to flip through channels.", listOf(K.KEYCODE_DPAD_RIGHT)),
    KeyAct("last", "Previous Channel", "Jumps back to the channel you watched before. Back does this too.", listOf(K.KEYCODE_LAST_CHANNEL), "Back"),
    KeyAct("pause", "Play / Pause", "Pauses the channel and plays it again.", listOf(K.KEYCODE_MEDIA_PLAY_PAUSE)),
    KeyAct("fwd", "Skip Forward 30 s", "Jumps 30 seconds ahead, when the channel allows it.", listOf(K.KEYCODE_MEDIA_FAST_FORWARD)),
    KeyAct("rew", "Skip Back 30 s", "Jumps 30 seconds back, when the channel allows it.", listOf(K.KEYCODE_MEDIA_REWIND)),
)

// buttons that can't be added: Back and the numbers keep their own jobs, volume and Home belong to the TV
fun keyUsable(k: Int) = k !in K.KEYCODE_0..K.KEYCODE_9 && k !in listOf(K.KEYCODE_BACK, K.KEYCODE_HOME, K.KEYCODE_VOLUME_UP, K.KEYCODE_VOLUME_DOWN,
    K.KEYCODE_VOLUME_MUTE, K.KEYCODE_MUTE, K.KEYCODE_POWER, K.KEYCODE_SLEEP, K.KEYCODE_WAKEUP, K.KEYCODE_UNKNOWN)

fun keyLabel(k: Int): String = when (k) {
    K.KEYCODE_DPAD_CENTER, K.KEYCODE_ENTER -> "OK"
    K.KEYCODE_DPAD_UP -> "▲"; K.KEYCODE_DPAD_DOWN -> "▼"; K.KEYCODE_DPAD_LEFT -> "◀"; K.KEYCODE_DPAD_RIGHT -> "▶"
    K.KEYCODE_CHANNEL_UP -> "CH+"; K.KEYCODE_CHANNEL_DOWN -> "CH−"
    K.KEYCODE_MEDIA_PLAY_PAUSE -> "Play/Pause"; K.KEYCODE_MEDIA_FAST_FORWARD -> "▶▶"; K.KEYCODE_MEDIA_REWIND -> "◀◀"
    K.KEYCODE_LAST_CHANNEL -> "Last"; K.KEYCODE_PROG_RED -> "Red"; K.KEYCODE_PROG_GREEN -> "Green"; K.KEYCODE_PROG_YELLOW -> "Yellow"; K.KEYCODE_PROG_BLUE -> "Blue"
    else -> K.keyCodeToString(k).removePrefix("KEYCODE_").replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
}

class RemoteKeys(private val prefs: SharedPreferences) {
    private val custom = HashMap<String, List<Int>>()   // only the actions the viewer changed: "id:code,code;id:code"
    init {
        for (part in prefs.getString("keys", "")!!.split(';')) {
            val p = part.split(':'); if (p.size == 2 && KEY_ACTS.any { it.id == p[0] }) custom[p[0]] = p[1].split(',').mapNotNull { it.toIntOrNull() }
        }
    }
    // the viewer's list, plus any default button no other action has taken (a reset elsewhere can free one; it comes home)
    fun keys(a: KeyAct) = ((custom[a.id] ?: emptyList()) + a.def.filter { d -> custom.none { it.key != a.id && d in it.value } }).distinct()
    fun actFor(k: Int) = KEY_ACTS.firstOrNull { k in keys(it) }
    // adds k to a; returns the action it was taken from, if any
    fun add(a: KeyAct, k: Int): KeyAct? {
        val from = KEY_ACTS.firstOrNull { it !== a && k in keys(it) }
        if (from != null) custom[from.id] = keys(from) - k
        custom[a.id] = keys(a) - k + k; save(); return from
    }
    // a == null resets every action; a single reset also takes its default buttons back from where they were moved
    fun reset(a: KeyAct?) {
        if (a == null) custom.clear() else {
            custom.remove(a.id)
            for (o in KEY_ACTS) if (o !== a && custom.containsKey(o.id)) custom[o.id] = keys(o).filter { it !in a.def }
        }
        save()
    }
    private fun save() = prefs.edit().putString("keys", custom.entries.joinToString(";") { "${it.key}:${it.value.joinToString(",")}" }).apply()
}
