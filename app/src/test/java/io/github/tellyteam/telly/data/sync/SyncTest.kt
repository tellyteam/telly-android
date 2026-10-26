package io.github.tellyteam.telly.data.sync

import io.github.tellyteam.telly.data.store.LastWatchedStore.Item
import io.github.tellyteam.telly.data.store.LastWatchedStore.LastWatched
import io.github.tellyteam.telly.data.store.WatchProgressStore.Progress
import io.github.tellyteam.telly.data.sync.SyncCrypto.toHex
import kotlinx.coroutines.runBlocking
import io.github.tellyteam.telly.data.Provider
import io.github.tellyteam.telly.data.syncIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncCryptoTest {
    private val keys = SyncCrypto.deriveKeys("0123456789ABCDEF")
    private val nonce = ByteArray(12) { it.toByte() }

    /** The shared vector in telly-sync's PROTOCOL.md; telly-lg's tests pin the same values. */
    @Test
    fun `matches the shared test vector`() {
        assertEquals("964c34622b2ead29bde1300eddb81273", keys.mailbox)
        assertEquals("b98184ea929152cda7eb1b420ea2d4445b5c01aa84480b86670365cb2310586e", keys.key.toHex())
        assertEquals("U4UwJNTeVMR0DU0Bem7LRlBoeCR4UAL9ouKh7CuGXjQ", keys.auth)
        assertEquals(
            "01000102030405060708090a0b8c30ca4029013ae61434a3351cb312ca06d33a229d9dd9",
            SyncCrypto.seal(keys, "00112233aabbccdd", """{"v":1}""", nonce).toHex(),
        )
    }

    @Test
    fun `opens only with the right key and device`() {
        val sealed = SyncCrypto.seal(keys, "00112233aabbccdd", "hello")
        assertEquals("hello", SyncCrypto.open(keys, "00112233aabbccdd", sealed))
        assertNull(SyncCrypto.open(keys, "ffffffffffffffff", sealed))
        assertNull(SyncCrypto.open(SyncCrypto.deriveKeys("1123456789ABCDEF"), "00112233aabbccdd", sealed))
        assertNull(SyncCrypto.open(keys, "00112233aabbccdd", sealed.copyOf().also { it[20] = (it[20].toInt() xor 1).toByte() }))
    }

    @Test
    fun `codes are 16 unambiguous characters and forgive typing`() {
        val code = SyncCrypto.newCode()
        assertEquals(16, code.length)
        assertEquals(code, SyncCrypto.normaliseCode(code))
        assertEquals("ABCDEFGHJKMNPQRS", SyncCrypto.normaliseCode("abcd-efgh jkmn-pqrs"))
        assertEquals("0110000000000000", SyncCrypto.normaliseCode("OIL0-0000-0000-0000"))
        assertNull(SyncCrypto.normaliseCode("ABCD-EFGH-JKMN-PQRU"))
        assertEquals("ABCD-EFGH-JKMN-PQRS", SyncCrypto.formatCode("ABCDEFGHJKMNPQRS"))
    }

    @Test
    fun `base64 round trips and reads the relay's encoding`() {
        val bytes = ByteArray(40) { (it * 37).toByte() }
        assertEquals(bytes.toHex(), Base64Text.decode(Base64Text.encode(bytes))!!.toHex())
        assertEquals("AQID", Base64Text.encode(byteArrayOf(1, 2, 3)))
        assertEquals("aGVsbG8=", Base64Text.encode("hello".toByteArray()))
        assertEquals("hello", String(Base64Text.decode("aGVsbG8=")!!))
    }
}

class SyncParcelTest {
    @Test
    fun `drops anything that could carry a login`() {
        val p = SyncParcel.parse(
            """{"v":1,"at":5,
              "progress":{"series/12.mkv":[1,2,3],"http://host/user/pass/12.ts":[1,2,3],"movie/5.mp4":[1,0,3]},
              "history":[{"kind":"film","id":"5","at":1},{"kind":"live","id":"http://host/u/p/1.ts","at":1},
                         {"kind":"series","id":"9","at":1},{"kind":"series","id":"9","series":"4","at":2,"removed":true}],
              "list":{"film:5":[1,true],"live:http://x":[1,true],"series:4":[1,"yes"]}}""",
        )!!
        assertEquals(setOf("series/12.mkv"), p.progress.keys)
        assertEquals(listOf(SyncEntry(SyncEntry.Kind.FILM, "5", at = 1), SyncEntry(SyncEntry.Kind.SERIES, "9", "4", 2, removed = true)), p.history)
        assertEquals(mapOf("film:5" to (1L to true)), p.list)
        assertNull(SyncParcel.parse("""{"v":2}"""))
    }

    @Test
    fun `takes fractional times rather than dropping the entry`() {
        val p = SyncParcel.parse("""{"v":1,"at":1.6,"progress":{"movie/1.mp4":[5.4,10,3.5]},"history":[],"list":{}}""")!!
        assertEquals(SyncProgress(5, 10, 4), p.progress["movie/1.mp4"])
    }

    @Test
    fun `round trips through JSON, as the LG app writes it`() {
        val p = SyncParcel(
            at = 9,
            progress = mapOf("series/1.mkv" to SyncProgress(5, 10, 3)),
            history = listOf(SyncEntry(SyncEntry.Kind.SERIES, "1", "77", 9), SyncEntry(SyncEntry.Kind.FILM, "5", at = 4, removed = true)),
            list = mapOf("film:5" to (4L to false)),
            cleared = 2,
        )
        assertEquals(p, SyncParcel.parse(p.toJson()))
    }

    @Test
    fun `merges newest-wins per key, removals included`() {
        val own = SyncParcel(
            progress = mapOf("series/1.mkv" to SyncProgress(100, 1000, 10), "series/2.mkv" to SyncProgress(500, 1000, 50)),
            history = listOf(SyncEntry(SyncEntry.Kind.FILM, "7", at = 20)),
            list = mapOf("film:7" to (10L to true)),
        )
        val other = SyncParcel(
            progress = mapOf("series/1.mkv" to SyncProgress(900, 1000, 30), "series/2.mkv" to SyncProgress(10, 1000, 40)),
            history = listOf(SyncEntry(SyncEntry.Kind.FILM, "7", at = 30, removed = true)),
            list = mapOf("film:7" to (20L to false)),
        )
        val m = SyncParcel.merge(own, listOf(other), 100)
        assertEquals(SyncProgress(900, 1000, 30), m.progress["series/1.mkv"])
        assertEquals(SyncProgress(500, 1000, 50), m.progress["series/2.mkv"])
        assertEquals(listOf(SyncEntry(SyncEntry.Kind.FILM, "7", at = 30, removed = true)), m.history)
        assertEquals(20L to false, m.list["film:7"])
    }

    @Test
    fun `a clear on any device drops older progress everywhere`() {
        val m = SyncParcel.merge(
            SyncParcel(progress = mapOf("series/1.mkv" to SyncProgress(1, 9, 10), "series/2.mkv" to SyncProgress(1, 9, 40))),
            listOf(SyncParcel(cleared = 30)),
            50,
        )
        assertEquals(setOf("series/2.mkv"), m.progress.keys)
        assertEquals(30L, m.cleared)
    }
}

class SyncLocalTest {
    private fun item(id: String, kind: String) = Item(id, "E$id", "Season 1", null, "http://host/$kind/user/secret/$id.mkv")
    private fun film(id: String, at: Long) = LastWatched("Film", false, 0, listOf(item(id, "movie")), savedAt = at)
    private fun local(
        progress: Map<String, Progress> = emptyMap(),
        history: List<LastWatched> = emptyList(),
        favourites: List<String> = emptyList(),
        changes: Map<String, Pair<Long, Boolean>> = emptyMap(),
        removed: Map<String, Long> = emptyMap(),
    ) = SyncLocal.Local(progress, history, favourites, changes, removed)

    @Test
    fun `shares ids, positions and times, never URLs or names`() {
        val p = SyncLocal.toParcel(
            local(
                progress = mapOf("series/1.mkv" to Progress(5, 10, 3), "http://host/u/p/1.ts" to Progress(1, 2, 3)),
                history = listOf(
                    LastWatched("Ted", false, 0, listOf(item("1", "series")), seriesId = "77", savedAt = 9),
                    film("9", 8),
                    LastWatched("Old show", false, 0, listOf(item("2", "series")), savedAt = 7), // no series id
                ),
                favourites = listOf("film:9", "live:http://m3u/x"),
            ),
            100,
        )
        assertEquals(setOf("series/1.mkv"), p.progress.keys)
        assertEquals(listOf(SyncEntry(SyncEntry.Kind.SERIES, "1", "77", 9), SyncEntry(SyncEntry.Kind.FILM, "9", at = 8)), p.history)
        assertEquals(mapOf("film:9" to (0L to true)), p.list)
        val json = p.toJson()
        listOf("user", "secret", "host", "Ted", "E1").forEach { assertFalse(it, json.contains(it)) }
    }

    @Test
    fun `takes in newer things, keeps its own newer things, and plays shared history here`() = runBlocking {
        val next = SyncLocal.apply(
            local(
                progress = mapOf("series/1.mkv" to Progress(5, 10, 20)),
                history = listOf(film("5", 10), film("6", 40)),
                favourites = listOf("film:1"),
            ),
            SyncParcel(
                progress = mapOf("series/1.mkv" to SyncProgress(9, 10, 10), "series/2.mkv" to SyncProgress(8, 10, 5)),
                history = listOf(
                    SyncEntry(SyncEntry.Kind.FILM, "7", at = 30),
                    SyncEntry(SyncEntry.Kind.FILM, "5", at = 20, removed = true),
                    SyncEntry(SyncEntry.Kind.FILM, "6", at = 35, removed = true),
                ),
                list = mapOf("film:1" to (5L to false), "film:3" to (30L to true)),
            ),
            maxHistory = 20,
        ) { e -> film(e.id, 0) }
        assertEquals(Progress(5, 10, 20), next.progress["series/1.mkv"])
        assertEquals(Progress(8, 10, 5), next.progress["series/2.mkv"])
        assertEquals(listOf("6" to 40L, "7" to 30L), next.history.map { it.current.id to it.savedAt })
        assertEquals(mapOf("film:5" to 20L), next.removed)
        assertEquals(listOf("film:3"), next.favourites)
    }

    @Test
    fun `clearing drops older progress and history but keeps My List`() = runBlocking {
        val next = SyncLocal.apply(
            local(progress = mapOf("series/1.mkv" to Progress(5, 10, 20)), history = listOf(film("5", 10)), favourites = listOf("film:5")),
            SyncParcel(cleared = 30),
            maxHistory = 20,
        ) { null }
        assertEquals(emptyMap<String, Progress>(), next.progress)
        assertEquals(emptyList<LastWatched>(), next.history)
        assertEquals(listOf("film:5"), next.favourites)
        assertEquals(30L, next.cleared)
    }
}

/** Version 2: a section per provider. The vectors are PROTOCOL.md's, as the Apple and LG apps pin them. */
class SyncBundleTest {
    private val keys = SyncCrypto.deriveKeys("0123456789ABCDEF")

    @Test
    fun `provider tags match the shared vector`() {
        assertEquals("http://example.com:8080", Provider.normaliseServer(" Example.com:8080/ "))
        assertEquals("https://tv.example", Provider.normaliseServer("https://tv.example:443/"))
        assertEquals("http://tv.example", Provider.normaliseServer("HTTP://tv.example:80"))
        val xtream = Provider.Xtream("A", " Example.com:8080/ ", "me", "secret", true)
        val m3u = Provider.M3u("B", " https://example.com/list.m3u ", false)
        assertEquals("7a6c86b8dde4ea72", SyncCrypto.providerTag(keys, xtream.syncIdentity))
        assertEquals("ff2163ea5d23fc01", SyncCrypto.providerTag(keys, m3u.syncIdentity))
        // Never the password, and different codes give unrelated tags.
        assertFalse("secret" in xtream.syncIdentity)
        assertNotEquals("7a6c86b8dde4ea72", SyncCrypto.providerTag(SyncCrypto.deriveKeys("1123456789ABCDEF"), xtream.syncIdentity))
    }

    @Test
    fun `keeps each provider apart`() {
        val a = SyncParcel(10, progress = mapOf("movie/1.mkv" to SyncProgress(5, 10, 10)))
        val b = SyncParcel(10, progress = mapOf("movie/1.mkv" to SyncProgress(7, 10, 20)))
        val back = SyncBundle.parse(SyncBundle(30, mapOf("aaaaaaaaaaaaaaaa" to a, "bbbbbbbbbbbbbbbb" to b)).toJson(), "cccccccccccccccc")!!
        assertEquals(5L, back.sections["aaaaaaaaaaaaaaaa"]?.progress?.get("movie/1.mkv")?.positionMs)
        assertEquals(7L, back.sections["bbbbbbbbbbbbbbbb"]?.progress?.get("movie/1.mkv")?.positionMs)
        assertNull(back.sections["cccccccccccccccc"])
        // Anything that isn't a tag is dropped.
        assertEquals(0, SyncBundle.parse("""{"v":2,"at":1,"providers":{"http://x":{"progress":{}}}}""", "aaaaaaaaaaaaaaaa")?.sections?.size)
    }

    @Test
    fun `reads version 1 as the only provider, and not at all with several`() {
        val v1 = """{"v":1,"at":5,"progress":{"movie/9.mp4":[1,2,5]},"history":[],"list":{},"cleared":0}"""
        val b = SyncBundle.parse(v1, "dddddddddddddddd")!!
        assertEquals(setOf("dddddddddddddddd"), b.sections.keys)
        assertEquals(1L, b.sections["dddddddddddddddd"]?.progress?.get("movie/9.mp4")?.positionMs)
        assertTrue(SyncBundle.parse(v1, null)!!.sections.isEmpty())
        assertNull(SyncBundle.parse("""{"v":3}""", "dddddddddddddddd"))
    }

    @Test
    fun `merges only the providers this device has`() {
        val mine = SyncParcel(1, progress = mapOf("movie/1.mkv" to SyncProgress(1, 10, 1)))
        val theirs = SyncParcel(2, progress = mapOf("movie/1.mkv" to SyncProgress(6, 10, 2)))
        val other = SyncBundle(2, mapOf("aaaaaaaaaaaaaaaa" to theirs, "eeeeeeeeeeeeeeee" to theirs))
        val m = SyncBundle.merge(SyncBundle(1, mapOf("aaaaaaaaaaaaaaaa" to mine)), listOf(other), 3)
        assertEquals(setOf("aaaaaaaaaaaaaaaa"), m.sections.keys)
        assertEquals(6L, m.sections["aaaaaaaaaaaaaaaa"]?.progress?.get("movie/1.mkv")?.positionMs)
    }

    @Test
    fun `an older app's logins are ignored and never written back`() {
        val old = """{"v":2,"at":5,"providers":{"aaaaaaaaaaaaaaaa":{"progress":{"movie/9.mp4":[1,2,5]}}},"logins":"AQABAgMEBQYHCAkKC7jlJIwRytFBbqbV37GRZNfWfA"}"""
        val b = SyncBundle.parse(old, null)!!
        assertEquals(1L, b.sections["aaaaaaaaaaaaaaaa"]?.progress?.get("movie/9.mp4")?.positionMs)
        val merged = SyncBundle.merge(SyncBundle(6, mapOf("aaaaaaaaaaaaaaaa" to SyncParcel(6))), listOf(b), 7)
        assertFalse("logins" in merged.toJson())
    }
}
