package io.github.tellyteam.telly.data.store

import io.github.tellyteam.telly.data.ChannelGroup
import io.github.tellyteam.telly.data.Series
import io.github.tellyteam.telly.data.SeriesGroup
import io.github.tellyteam.telly.data.xtream.XtreamParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.StringWriter

class CompactTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val server = XtreamParser.serverUrl("http://tv.example:8080/")

    private fun pack(write: (java.io.Writer) -> Unit) = StringWriter().also(write).toString()

    @Test
    fun `films unpack to exactly what the provider's answer parses to, without any stream address`() {
        val films = XtreamParser.films(
            server, "me", "p@ss", mapOf("1" to "Drama", "2" to "Kids"),
            """[{"stream_id":10,"name":"A","category_id":"1","stream_icon":"https://i.example/a.jpg","container_extension":"mkv","added":"1700000000"},
                {"stream_id":11,"name":"B\nline","category_id":"2"}]""".byteInputStream(),
        )
        val groups = listOf(ChannelGroup("Drama", films.filter { it.group == "Drama" }), ChannelGroup("Kids", films.filter { it.group == "Kids" }))
        val packed = pack { Compact.writeChannels(groups, it) }
        assertFalse("p%40ss" in packed || "tv.example" in packed)
        val urls = XtreamParser.StreamUrls(server, "movie", "me", "p@ss")
        val back = Compact.readChannels(packed.reader().buffered(), urls)!!
        assertEquals(groups.map { g -> g.copy(channels = g.channels.map { it.copy(name = it.name.replace('\n', ' ')) }) }, back)
        assertNull(Compact.readChannels("not saved".reader().buffered(), urls))
    }

    @Test
    fun `series keep everything but the plot`() {
        val groups = listOf(SeriesGroup("Crime", listOf(Series("5", "S", "Crime", "c.jpg", "A plot", 1700000001))))
        val back = Compact.readSeries(pack { Compact.writeSeries(groups, it) }.reader().buffered())
        assertEquals(listOf(SeriesGroup("Crime", listOf(Series("5", "S", "Crime", "c.jpg", null, 1700000001)))), back)
    }

    @Test
    fun `quick stream addresses are exactly the full ones`() {
        for ((user, pass) in listOf("me" to "p@ss word", "a/b" to "ü")) {
            val urls = XtreamParser.StreamUrls(server, "live", user, pass)
            for ((id, ext) in listOf("10" to "m3u8", "a b" to "ts", "x%" to "mp4", "ü1" to "mkv")) {
                assertEquals(XtreamParser.streamUrl(server, "live", user, pass, id, ext), urls.of(id, ext))
            }
        }
    }

    @Test
    fun `an unchanged list isn't saved again, a changed one is`() {
        val cache = CatalogCache(tmp.root)
        assertTrue(cache.keep("p", "compact-films", known = true) { it.write("one") })
        assertFalse(cache.keep("p", "compact-films", known = true) { it.write("one") })
        // Not showing yet: saved (and shown) whatever the fingerprint.
        assertTrue(cache.keep("p", "compact-films", known = false) { it.write("one") })
        assertTrue(cache.keep("p", "compact-films", known = true) { it.write("two") })
        assertEquals("two", cache.read("p", "compact-films") { it.bufferedReader().readText() })
    }
}
