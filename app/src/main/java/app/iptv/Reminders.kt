package app.iptv

// "Remind me" for shows that have not started yet: saved as "channel url \t start \t title" in the prefs;
// a check every 20 s pops up the "Starting now" card when one is due (only while the app is open)
fun remKey(c: Ch, p: Prog) = c.url + "\t" + p.start + "\t" + p.title

fun MainActivity.reminders(): Set<String> = prefs.getStringSet("rem", emptySet()) ?: emptySet()
fun MainActivity.hasRem(c: Ch, p: Prog) = remKey(c, p) in reminders()

fun MainActivity.toggleRem(c: Ch, p: Prog) {
    val s = HashSet(reminders()); val k = remKey(c, p)
    if (!s.remove(k)) { s.add(k); flash("Reminder set: ${p.title} at ${hm.format(p.start)}", 2500) } else flash("Reminder removed", 1500)
    prefs.edit().putStringSet("rem", s).apply()
}

// the channel of a reminder: the current playlist first, then the other playlists already in memory
fun MainActivity.remChannel(url: String): Ch? = channels.firstOrNull { it.url == url } ?: chCache.values.asSequence().flatten().firstOrNull { it.url == url }

fun MainActivity.checkReminders() {
    val now = System.currentTimeMillis(); val all = reminders(); if (all.isEmpty()) return
    val keep = HashSet<String>(); var due: Pair<Ch, Prog>? = null
    for (k in all) {
        val f = k.split('\t'); val st = f.getOrNull(1)?.toLongOrNull() ?: continue
        when {
            st > now + 30_000L -> keep.add(k)                                   // not yet
            st > now - 10 * 60_000L -> {                                         // due (up to 10 min late, e.g. the app was just opened)
                if (due != null) { keep.add(k); continue }                       // one card at a time: the next one shows 20 s later
                val c = remChannel(f[0])
                if (c != null) due = c to (progsOf(c).firstOrNull { it.start == st } ?: Prog(st, st + 30 * 60_000L, f.getOrElse(2) { "" }, ""))
            }
        }                                                                        // older ones are dropped
    }
    if (keep.size != all.size) prefs.edit().putStringSet("rem", keep).apply()
    due?.let { (c, p) -> if (c != cur) ui.showReminder(c, p) }
}
