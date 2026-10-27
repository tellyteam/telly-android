package io.github.tellyteam.telly.data.store

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The record of watched films and series (telly-sync PLAYBACK.md, Watched titles), apart from
 * Continue watching's short history. Keyed as My List is (`film:<id>`, `series:<id>`); a mark that's
 * off (new episodes arrived) is kept, with its time, so sync passes it on.
 */
class WatchedStore(private val context: Context, scope: String? = null) {
    private var prefs = open(scope)
    private val _marks = MutableStateFlow(load())
    val marks: StateFlow<Map<String, Mark>> = _marks

    /** [episode] is the episode a series was watched up to; empty for a film. */
    data class Mark(val at: Long, val on: Boolean, val episode: String = "")

    /** Switches to another provider's record. */
    fun use(scope: String?) {
        prefs = open(scope)
        _marks.value = load()
    }

    private fun open(scope: String?) = context.getSharedPreferences(Scoped.name("watched", scope), Context.MODE_PRIVATE)

    private fun load(): Map<String, Mark> = prefs.all.mapNotNull { (k, v) -> (v as? String)?.let(::decode)?.let { k to it } }.toMap()

    fun isWatched(key: String) = _marks.value[key]?.on == true

    fun set(key: String, on: Boolean, episode: String = "", now: Long = System.currentTimeMillis()) {
        val mark = Mark(now, on, episode)
        prefs.edit().putString(key, encode(mark)).apply()
        _marks.value = _marks.value + (key to mark)
    }

    /** Sets the whole record, after a sync. */
    fun replace(all: Map<String, Mark>) {
        val wasSeeded = seeded
        prefs.edit().clear().putBoolean(SEEDED, wasSeeded).apply { all.forEach { (k, m) -> putString(k, encode(m)) } }.apply()
        _marks.value = all
    }

    fun clear() = replace(emptyMap())

    /** Whether films already watched have been taken into the record (once, without leaving My List). */
    var seeded: Boolean
        get() = prefs.getBoolean(SEEDED, false)
        set(value) = prefs.edit().putBoolean(SEEDED, value).apply()

    companion object {
        private const val SEEDED = "_seeded"

        /** A film's id from its progress key (`movie/123.mkv`); null for anything else. */
        fun filmId(progressKey: String): String? =
            progressKey.takeIf { it.startsWith("movie/") }?.removePrefix("movie/")?.substringBeforeLast('.')

        internal fun encode(m: Mark) = "${m.at}/${if (m.on) 1 else 0}/${m.episode}"

        internal fun decode(s: String): Mark? {
            val parts = s.split('/')
            if (parts.size != 3) return null
            return Mark(parts[0].toLongOrNull() ?: return null, parts[1] == "1", parts[2])
        }
    }
}
