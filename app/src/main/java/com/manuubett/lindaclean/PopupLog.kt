package com.manuubett.lindaclean

import android.content.SharedPreferences
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The list of apps that popped something up by themselves. The Pop-up Watch
 * service writes it, and the main screen reads it. Only app names (package
 * names) and counts are stored, never anything from the screen.
 */
object PopupLog {

    const val KIND_AD_SCREEN = "ad"        // a known ad screen (closed)
    const val KIND_UNLOCK = "unlock"       // opened by itself right after unlock
    const val KIND_SELF = "self"           // opened by itself while using the phone
    const val KIND_BLOCKED = "blocked"     // app on the block list (closed)

    class Entry(
        val pkg: String,
        var total: Int,
        var ads: Int,
        var unlock: Int,
        var self: Int,
        var blocked: Int,
        var lastAt: Long
    )

    private const val KEY = "popup_log"
    private const val MAX_APPS = 100

    private fun load(prefs: SharedPreferences): MutableMap<String, Entry> {
        val map = LinkedHashMap<String, Entry>()

        try {
            val obj = JSONObject(prefs.getString(KEY, "{}") ?: "{}")
            val names = obj.keys()

            while (names.hasNext()) {
                val pkg = names.next()
                val o = obj.getJSONObject(pkg)

                map[pkg] = Entry(
                    pkg,
                    o.optInt("n"),
                    o.optInt("ad"),
                    o.optInt("u"),
                    o.optInt("s"),
                    o.optInt("b"),
                    o.optLong("t")
                )
            }
        } catch (e: Exception) {
            // Start fresh if the saved text is damaged
        }

        return map
    }

    private fun save(prefs: SharedPreferences, map: Map<String, Entry>) {
        val obj = JSONObject()

        for ((pkg, e) in map) {
            obj.put(
                pkg,
                JSONObject()
                    .put("n", e.total)
                    .put("ad", e.ads)
                    .put("u", e.unlock)
                    .put("s", e.self)
                    .put("b", e.blocked)
                    .put("t", e.lastAt)
            )
        }

        prefs.edit().putString(KEY, obj.toString()).apply()
    }

    @Synchronized
    fun record(prefs: SharedPreferences, pkg: String, kind: String) {
        val map = load(prefs)
        val e = map.getOrPut(pkg) { Entry(pkg, 0, 0, 0, 0, 0, 0L) }

        e.total++
        e.lastAt = System.currentTimeMillis()

        when (kind) {
            KIND_AD_SCREEN -> e.ads++
            KIND_UNLOCK -> e.unlock++
            KIND_SELF -> e.self++
            KIND_BLOCKED -> e.blocked++
        }

        // Keep the list short: drop the apps not seen for the longest time
        if (map.size > MAX_APPS) {
            val oldest = map.values.minByOrNull { it.lastAt }
            if (oldest != null) map.remove(oldest.pkg)
        }

        save(prefs, map)
    }

    @Synchronized
    fun entries(prefs: SharedPreferences): List<Entry> {
        return load(prefs).values
            .sortedWith(compareByDescending<Entry> { it.total }.thenByDescending { it.lastAt })
    }

    @Synchronized
    fun forget(prefs: SharedPreferences, pkg: String) {
        val map = load(prefs)
        map.remove(pkg)
        save(prefs, map)
    }

    @Synchronized
    fun clear(prefs: SharedPreferences) {
        prefs.edit()
            .remove(KEY)
            .putInt("closed_n", 0)
            .apply()
    }

    // ---- Ad screens closed today ----

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    @Synchronized
    fun addClosed(prefs: SharedPreferences) {
        val day = today()
        val count = if (prefs.getString("closed_day", "") == day) {
            prefs.getInt("closed_n", 0)
        } else {
            0
        }

        prefs.edit()
            .putString("closed_day", day)
            .putInt("closed_n", count + 1)
            .apply()
    }

    fun closedToday(prefs: SharedPreferences): Int {
        return if (prefs.getString("closed_day", "") == today()) {
            prefs.getInt("closed_n", 0)
        } else {
            0
        }
    }

    // ---- Lists the parent chose: "ignored_apps" and "blocked_apps" ----

    fun set(prefs: SharedPreferences, name: String): Set<String> =
        prefs.getStringSet(name, emptySet())?.toSet() ?: emptySet()

    fun addTo(prefs: SharedPreferences, name: String, pkg: String) {
        prefs.edit().putStringSet(name, set(prefs, name) + pkg).apply()
    }

    fun removeFrom(prefs: SharedPreferences, name: String, pkg: String) {
        prefs.edit().putStringSet(name, set(prefs, name) - pkg).apply()
    }
}
