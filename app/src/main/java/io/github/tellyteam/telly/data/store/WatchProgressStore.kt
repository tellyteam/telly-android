package io.github.tellyteam.telly.data.store

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import io.github.tellyteam.telly.data.PlaybackRules
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Where the user got to in each film and episode, so playback resumes there. */
class WatchProgressStore(private val context: Context, scope: String? = null) {
    private var prefs = open(scope)
    private val _changes = MutableStateFlow(0)

    /** Switches to another provider's positions. */
    fun use(scope: String?) {
        prefs = open(scope)
        _changes.value++
    }

    private fun open(scope: String?) = context.getSharedPreferences(Scoped.name("watch_progress", scope), Context.MODE_PRIVATE)

    /** Bumped on every save so screens can redraw progress bars. */
    val changes: StateFlow<Int> = _changes

    /**
     * [savedAt] is when it was saved (epoch ms; 0 for older records), so synced devices keep the newest.
     * [film] comes from the key, not what's stored: a film's credits count as watched sooner.
     */
    data class Progress(val positionMs: Long, val durationMs: Long, val savedAt: Long = 0, val film: Boolean = false) {
        val fraction: Float get() = (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
        val finished: Boolean get() = PlaybackRules.watched(positionMs, durationMs, film)
    }

    fun get(streamUrl: String): Progress? = key(streamUrl).let { k -> prefs.getString(k, null)?.let { decode(it, PlaybackRules.isFilm(k)) } }

    fun save(streamUrl: String, positionMs: Long, durationMs: Long, now: Long = System.currentTimeMillis()) {
        if (durationMs <= 0) return
        prefs.edit().putString(key(streamUrl), encode(Progress(positionMs.coerceIn(0, durationMs), durationMs, now))).apply()
        _changes.value++
    }

    /** Everything saved, by key (`series/123.mkv`), for sync. */
    fun all(): Map<String, Progress> =
        prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { decode(it, PlaybackRules.isFilm(k)) }?.let { k to it } }.toMap()

    /** Forgets every position. */
    fun clear() {
        prefs.edit().clear().apply()
        _changes.value++
    }

    /** Sets every position at once, by key, after a sync. */
    fun replace(all: Map<String, Progress>) {
        prefs.edit().clear().apply { all.forEach { (k, p) -> putString(k, encode(p)) } }.apply()
        _changes.value++
    }

    fun markFinished(streamUrl: String, durationMs: Long) = save(streamUrl, durationMs, durationMs)

    /** Where to start: the saved position, unless barely started or already finished. */
    fun resumePosition(streamUrl: String): Long = get(streamUrl).let { PlaybackRules.startAt(it?.positionMs, it?.durationMs, it?.film == true) }

    companion object {
        /**
         * Xtream stream URLs embed the username and password (`/series/user/pass/123.mkv`), so the
         * key keeps only the kind and file name: `series/123.mkv`.
         */
        internal fun key(streamUrl: String): String {
            val segments = streamUrl.toHttpUrlOrNull()?.pathSegments ?: return streamUrl
            return if (segments.size >= 4) "${segments[segments.size - 4]}/${segments.last()}" else streamUrl
        }

        internal fun encode(p: Progress) = "${p.positionMs}/${p.durationMs}/${p.savedAt}"

        internal fun decode(text: String, film: Boolean = false): Progress? {
            val parts = text.split('/').takeIf { it.size >= 2 } ?: return null
            return Progress(
                parts[0].toLongOrNull() ?: return null,
                parts[1].toLongOrNull() ?: return null,
                parts.getOrNull(2)?.toLongOrNull() ?: 0,
                film,
            )
        }
    }
}
