package io.github.tellyteam.telly.data

import io.github.tellyteam.telly.data.store.LastWatchedStore.LastWatched
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NewEpisodesTest {
    private fun ep(id: String) = Channel(id, "E$id", "Season", null, "https://t/series/u/p/$id.mkv")
    private val seasons = listOf(
        Season("Season 1", listOf(ep("101"), ep("102"))),
        Season("Season 2", listOf(ep("201"), ep("202"))),
    )

    @Test
    fun `next episode is in the same season, then the next season`() {
        assertEquals("Season 1" to 1, NewEpisodes.after(seasons, "101")?.let { (s, i) -> s.name to i })
        assertEquals("Season 2" to 0, NewEpisodes.after(seasons, "102")?.let { (s, i) -> s.name to i })
        assertNull(NewEpisodes.after(seasons, "202"))
        assertNull(NewEpisodes.after(seasons, "999"))
    }

    @Test
    fun `a series is only fetched again after the provider changes it`() {
        val show = Series("9", "Ted Lasso", "Comedy", null, null, added = 1_700_000_000)
        val now = 1_800_000_000_000L
        assertTrue(NewEpisodes.due(show, null, now))
        assertFalse(NewEpisodes.due(show, NewEpisodes.Check(1_700_000_000, now), now))
        assertTrue(NewEpisodes.due(show.copy(added = 1_750_000_000), NewEpisodes.Check(1_700_000_000, now), now))
        // Daily even when the list says nothing changed: a provider's list can lag behind its series.
        assertTrue(NewEpisodes.due(show, NewEpisodes.Check(1_700_000_000, now), now + 24L * 60 * 60 * 1000))
        // No change stamp from the provider: once a day.
        val unstamped = show.copy(added = 0)
        assertFalse(NewEpisodes.due(unstamped, NewEpisodes.Check(0, now), now + 60_000))
        assertTrue(NewEpisodes.due(unstamped, NewEpisodes.Check(0, now), now + 25L * 60 * 60 * 1000))
    }

    @Test
    fun `only episodes added after watching are new`() {
        val show = Series("9", "Ted Lasso", "Comedy", null, null, added = 1_750_000_000)
        assertTrue(NewEpisodes.isNew(0, show.added, watchedAtMs = 1_700_000_000_000))
        assertFalse(NewEpisodes.isNew(0, show.added, watchedAtMs = 1_760_000_000_000))
        assertFalse(NewEpisodes.isNew(0, show.added, watchedAtMs = 0)) // older history: no save time
        // The episode's own time wins over a list that lags.
        assertTrue(NewEpisodes.isNew(1_770_000_000, show.added, watchedAtMs = 1_760_000_000_000))
    }

    @Test
    fun `older history without the new fields still loads`() {
        val old = """{"heading":"Ted Lasso","live":false,"index":0,"items":[{"id":"1","name":"E1","group":"Season 3","logoUrl":null,"streamUrl":"https://t/series/u/p/1.mkv"}]}"""
        val entry = Json { ignoreUnknownKeys = true }.decodeFromString(LastWatched.serializer(), old)
        assertNull(entry.seriesId)
        assertEquals(0L, entry.savedAt)
        assertFalse(entry.newEpisodes)
    }
}
