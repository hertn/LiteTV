package app.iptv

import java.util.TimeZone

// "Remind me" sticks to the show, not one airing: saved as "channel url \t minute of the day \t title" in the prefs, so every
// airing of that title on that channel at that time (tomorrow, next week...) has the reminder until it is removed.
// A check every 20 s pops up the "Starting now" card when one is due (only while the app is open).
fun remTod(t: Long) = (((t + TimeZone.getDefault().getOffset(t)) / 60_000L) % 1440).toInt()
private val EPISODE = Regex("""\s*\(\d+(/\d+)?\)\s*$""")
fun remTitle(t: String) = t.replace(EPISODE, "").trim()   // "Reeturid (1/8)" and "(2/8)" are the same show
fun remKey(c: Ch, p: Prog) = c.url + "\t" + remTod(p.start) + "\t" + remTitle(p.title)

// older versions saved one airing ("url \t start time in ms \t title"): read as the show it belongs to
fun MainActivity.reminders(): Set<String> = (prefs.getStringSet("rem", emptySet()) ?: emptySet()).mapTo(HashSet()) { k ->
    val f = k.split('\t'); val t = f.getOrNull(1)?.toLongOrNull()
    if (t != null && t > 1440) f[0] + "\t" + remTod(t) + "\t" + remTitle(f.getOrElse(2) { "" }) else k
}
fun MainActivity.hasRem(c: Ch, p: Prog) = remKey(c, p) in reminders()

fun MainActivity.toggleRem(c: Ch, p: Prog) {
    val s = HashSet(reminders()); val k = remKey(c, p)
    if (!s.remove(k)) { s.add(k); flash("Reminder set: ${remTitle(p.title)} at ${hm.format(p.start)}, every time it's on", 3000) } else flash("Reminder removed", 1500)
    prefs.edit().putStringSet("rem", s).apply()
}

// the coming airings of every reminder, soonest first, for the Reminders page (key = the saved show, used to remove it)
class RemItem(val key: String, val ch: Ch?, val prog: Prog)
fun MainActivity.remAirings(k: String, from: Long): List<Pair<Ch, Prog>> {
    val f = k.split('\t'); val c = remChannel(f[0]) ?: return emptyList()
    return progsOf(c).filter { it.start >= from && remKey(c, it) == k }.map { c to it }
}
fun MainActivity.reminderList(): List<RemItem> {
    val now = System.currentTimeMillis()
    return reminders().flatMap { k -> remAirings(k, now).map { (c, p) -> RemItem(k, c, p) } }.sortedBy { it.prog.start }
}
fun MainActivity.removeRem(k: String) {
    prefs.edit().putStringSet("rem", HashSet(reminders()) - k).apply(); flash("Reminder removed", 1500)
}

// the channel of a reminder: the current playlist first, then the other playlists already in memory
fun MainActivity.remChannel(url: String): Ch? = channels.firstOrNull { it.url == url } ?: chCache.values.asSequence().flatten().firstOrNull { it.url == url }

fun MainActivity.checkReminders() {
    val now = System.currentTimeMillis(); val all = reminders(); if (all.isEmpty()) return
    // airings already shown ("key \t start"), kept for a day so the card pops up once per airing
    val fired = (prefs.getStringSet("remFired", emptySet()) ?: emptySet()).filterTo(HashSet()) { (it.substringAfterLast('\t').toLongOrNull() ?: 0L) > now - 86_400_000L }
    // due: started up to 10 min ago (e.g. the app was just opened) or starting within 30 s; one card at a time, the next 20 s later
    val due = all.asSequence().flatMap { k -> remAirings(k, now - 10 * 60_000L).asSequence().map { Triple(k, it.first, it.second) } }
        .filter { (k, _, p) -> p.start <= now + 30_000L && (k + "\t" + p.start) !in fired }.minByOrNull { it.third.start }
    if (due != null) { fired.add(due.first + "\t" + due.third.start); if (due.second != cur) ui.showReminder(due.second, due.third) }
    prefs.edit().putStringSet("remFired", fired).apply()
}
