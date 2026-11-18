package io.github.tellyteam.telly.data.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchProgressStoreTest {

    @Test
    fun `key drops credentials from xtream urls`() {
        assertEquals("series/201.mkv", WatchProgressStore.key("https://tv.example:8080/series/me/secret/201.mkv"))
        assertEquals("movie/55.mp4", WatchProgressStore.key("https://tv.example/movie/me/secret/55.mp4"))
    }

    @Test
    fun `progress round-trips and rejects junk`() {
        val p = WatchProgressStore.Progress(90_000, 2_400_000)
        assertEquals(p, WatchProgressStore.decode(WatchProgressStore.encode(p)))
        assertNull(WatchProgressStore.decode("nonsense"))
    }

    @Test
    fun `last minute counts as finished`() {
        assertTrue(WatchProgressStore.Progress(1_160_000, 1_200_000).finished)
        assertFalse(WatchProgressStore.Progress(1_130_000, 1_200_000).finished)
    }

    @Test
    fun `long credits count as finished too`() {
        // 45 minutes: the last 5% is 2 min 15 s, so stopping 2 minutes from the end is watched.
        assertTrue(WatchProgressStore.Progress(2_580_000, 2_700_000).finished)
        assertFalse(WatchProgressStore.Progress(2_560_000, 2_700_000).finished)
    }

    @Test
    fun `a film's credits count from the last 10%`() {
        // A two-hour film stopped as its 8 min 38 s of credits start: watched, though an episode wouldn't be.
        assertTrue(WatchProgressStore.Progress(6_698_967, 7_216_864, film = true).finished)
        assertFalse(WatchProgressStore.Progress(6_698_967, 7_216_864).finished)
        assertTrue(WatchProgressStore.decode("6698967/7216864/1", film = true)!!.finished)
    }
}
