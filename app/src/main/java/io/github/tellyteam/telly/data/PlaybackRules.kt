package io.github.tellyteam.telly.data

import android.content.Context

/**
 * telly-sync's PLAYBACK.md as functions, so every app decides the same way; the unit tests run
 * its playback-cases.json. Change the spec and the cases first, then every app.
 */
object PlaybackRules {
    /** The prompt shows for the last minute, whatever the length. */
    const val PROMPT_MS = 60_000L

    /** Earlier than this, an item starts from the beginning. */
    const val MIN_RESUME_MS = 15_000L

    /** Seconds the next-episode prompt shows, or null when it doesn't. */
    fun promptSeconds(durationMs: Long, positionMs: Long, hasNext: Boolean, autoNext: Boolean, dismissed: Boolean, live: Boolean): Int? {
        if (live || !hasNext || !autoNext || dismissed || durationMs <= 0) return null
        val left = durationMs - positionMs
        return if (left in 1..PROMPT_MS) ((left + 999) / 1000).toInt() else null
    }

    /** Whether the next item starts when this one plays to its end. */
    fun advanceAtEnd(hasNext: Boolean, autoNext: Boolean, dismissed: Boolean) = hasNext && autoNext && !dismissed

    /** Within its credits: the last minute or the last 5%, whichever is longer. */
    fun watched(positionMs: Long, durationMs: Long) = durationMs - positionMs < maxOf(PROMPT_MS, durationMs / 20)

    /** Where an item starts: its saved position, unless barely started or watched. */
    fun startAt(positionMs: Long?, durationMs: Long?): Long {
        if (positionMs == null || durationMs == null || positionMs < MIN_RESUME_MS || watched(positionMs, durationMs)) return 0
        return positionMs
    }

    /** "Pick up" goes to the next item when this one was left watched (and there is one). */
    fun continueIndex(index: Int, count: Int, watched: (Int) -> Boolean): Int =
        if (index + 1 < count && watched(index)) index + 1 else index

    /** A Continue watching entry stays while something in it is left: not a watched film, nor a watched last episode. */
    fun shownInContinue(index: Int, count: Int, watched: (Int) -> Boolean): Boolean =
        !watched(continueIndex(index, count, watched))

    /** My List with watched titles after the rest, each part in My List order. */
    fun myListOrder(list: List<String>, watched: (String) -> Boolean): List<String> =
        list.filterNot(watched) + list.filter(watched)

    /** Whether a title leaves My List once watched: a film does, a series stays. */
    fun leavesMyList(kind: String) = kind == "film"

    /** What plays after [index]: the next item, or offline the next downloaded one; null when nothing can. */
    fun nextPlayable(index: Int, count: Int, downloaded: (Int) -> Boolean, online: Boolean): Int? =
        ((index + 1) until count).firstOrNull { online || downloaded(it) }
}

/** "Play the next episode automatically": on unless turned off. Kept on this device. */
class PlaybackSettings(context: Context) {
    private val prefs = context.getSharedPreferences("playback", Context.MODE_PRIVATE)

    var autoNext: Boolean
        get() = prefs.getBoolean("autoNext", true)
        set(value) = prefs.edit().putBoolean("autoNext", value).apply()
}
