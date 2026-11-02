package io.github.tellyteam.telly.data

import android.content.Context

/**
 * Spotting new episodes of series the user has watched up to the latest episode they had. Kept
 * free of Android so it's unit-tested; [NewEpisodeChecks] remembers what has been looked at.
 */
object NewEpisodes {

    /** Where watching continues after [watchedId]: that season and the episode's index, or null if it's the latest. */
    fun after(seasons: List<Season>, watchedId: String): Pair<Season, Int>? {
        for ((s, season) in seasons.withIndex()) {
            val i = season.episodes.indexOfFirst { it.id == watchedId }
            if (i < 0) continue
            if (i + 1 < season.episodes.size) return season to i + 1
            return seasons.drop(s + 1).firstOrNull { it.episodes.isNotEmpty() }?.let { it to 0 }
        }
        return null
    }

    /**
     * Like [after], past episodes already watched (on another device, say): where watching
     * continues, or null when everything after [watchedId] is watched too. [Next.upTo] is the last
     * of those watched in a row from [watchedId], what the series is then watched up to.
     */
    fun afterWatched(seasons: List<Season>, watchedId: String, watched: (Channel) -> Boolean): Next {
        var upTo = watchedId
        while (true) {
            val (season, i) = after(seasons, upTo) ?: return Next(null, upTo)
            val episode = season.episodes[i]
            if (!watched(episode)) return Next(season to i, upTo)
            upTo = episode.id
        }
    }

    data class Next(val next: Pair<Season, Int>?, val upTo: String)

    /**
     * Whether a series needs its episode list fetched again. Providers stamp a series when it
     * changes ([Series.added], epoch seconds), so it's re-fetched after that moves, and at least
     * once a day: a provider's list can show a series' change hours after the series does.
     */
    fun due(series: Series, checked: Check?, nowMs: Long): Boolean =
        checked == null || series.added != checked.added || nowMs - checked.atMs >= DAY_MS

    /**
     * Whether what comes next arrived after the user watched (so it's "new", not a season that was
     * already there): by the episode's own added time, or the series' when the provider doesn't give
     * it (epoch seconds). Older history has no save time, so it never claims "new".
     */
    fun isNew(episodeAdded: Long, seriesAdded: Long, watchedAtMs: Long): Boolean {
        val added = if (episodeAdded > 0) episodeAdded else seriesAdded
        return watchedAtMs > 0 && added > 0 && added * 1000 > watchedAtMs
    }

    data class Check(val added: Long, val atMs: Long)

    private const val DAY_MS = 24L * 60 * 60 * 1000
}

/** When each finished series was last looked at, keyed by series id. */
class NewEpisodeChecks(context: Context) {
    private val prefs = context.getSharedPreferences("new_episode_checks", Context.MODE_PRIVATE)

    fun get(seriesId: String): NewEpisodes.Check? = prefs.getString(seriesId, null)?.split('/')?.let { parts ->
        val added = parts.getOrNull(0)?.toLongOrNull() ?: return@let null
        val at = parts.getOrNull(1)?.toLongOrNull() ?: return@let null
        NewEpisodes.Check(added, at)
    }

    fun mark(seriesId: String, added: Long, nowMs: Long = System.currentTimeMillis()) {
        prefs.edit().putString(seriesId, "$added/$nowMs").apply()
    }
}
