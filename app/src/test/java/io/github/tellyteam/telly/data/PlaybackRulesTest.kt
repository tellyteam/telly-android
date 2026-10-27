package io.github.tellyteam.telly.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Test

/** telly-sync's playback-cases.json (copied into test resources): every app runs the same cases. */
class PlaybackRulesTest {
    private val cases: JsonObject = Json.parseToJsonElement(
        javaClass.classLoader!!.getResource("playback-cases.json")!!.readText(),
    ).jsonObject

    private fun each(section: String, check: (JsonObject, String) -> Unit) {
        val list = cases[section]!!.jsonArray
        check(list.isNotEmpty())
        list.forEach { c -> c.jsonObject.let { check(it, "$section: ${it.str("name")}") } }
    }

    private fun JsonObject.str(k: String) = getValue(k).jsonPrimitive.content
    private fun JsonObject.long(k: String) = getValue(k).jsonPrimitive.long
    private fun JsonObject.bool(k: String) = getValue(k).jsonPrimitive.boolean
    private fun JsonObject.int(k: String) = getValue(k).jsonPrimitive.int
    private fun JsonObject.indexes(k: String) = getValue(k).jsonArray.map { it.jsonPrimitive.int }.toSet()
    private fun JsonElement.orNullLong() = if (this is JsonNull) null else jsonPrimitive.longOrNull

    @Test
    fun prompt() = each("prompt") { c, name ->
        val got = PlaybackRules.promptSeconds(c.long("durationMs"), c.long("positionMs"), c.bool("hasNext"), c.bool("autoNext"), c.bool("dismissed"), c.bool("live"))
        assertEquals(name, c.getValue("seconds").let { if (it is JsonNull) null else it.jsonPrimitive.intOrNull }, got)
    }

    @Test
    fun atEnd() = each("atEnd") { c, name ->
        assertEquals(name, c.bool("advance"), PlaybackRules.advanceAtEnd(c.bool("hasNext"), c.bool("autoNext"), c.bool("dismissed")))
    }

    @Test
    fun watched() = each("watched") { c, name ->
        assertEquals(name, c.bool("watched"), PlaybackRules.watched(c.long("positionMs"), c.long("durationMs")))
    }

    @Test
    fun resume() = each("resume") { c, name ->
        assertEquals(name, c.long("startAt"), PlaybackRules.startAt(c.getValue("positionMs").orNullLong(), c.getValue("durationMs").orNullLong()))
    }

    @Test
    fun continueWatching() = each("continue") { c, name ->
        val watched = c.indexes("watched")
        assertEquals(name, c.int("continueIndex"), PlaybackRules.continueIndex(c.int("index"), c.int("items")) { it in watched })
        assertEquals(name, c.bool("shown"), PlaybackRules.shownInContinue(c.int("index"), c.int("items")) { it in watched })
    }

    @Test
    fun seriesNext() = each("seriesNext") { c, name ->
        val seasons = c.getValue("seasons").jsonArray.mapIndexed { n, ids ->
            Season("Season ${n + 1}", ids.jsonArray.map { val id = it.jsonPrimitive.content; Channel(id, id, "", null, "http://host/series/u/p/$id.mkv") })
        }
        val want = c.getValue("next").let { if (it is JsonNull) null else it.jsonArray.map { i -> i.jsonPrimitive.int } }
        assertEquals(name, want, NewEpisodes.after(seasons, c.str("episode"))?.let { (s, i) -> listOf(seasons.indexOf(s), i) })
    }

    @Test
    fun myList() {
        each("myList") { c, name ->
            val watched = c.getValue("watched").jsonArray.map { it.jsonPrimitive.content }.toSet()
            val list = c.getValue("list").jsonArray.map { it.jsonPrimitive.content }
            assertEquals(name, c.getValue("order").jsonArray.map { it.jsonPrimitive.content }, PlaybackRules.myListOrder(list) { it in watched })
        }
        each("leavesMyList") { c, name -> assertEquals(name, c.bool("leaves"), PlaybackRules.leavesMyList(c.str("kind"))) }
    }

    @Test
    fun nextPlayable() = each("nextPlayable") { c, name ->
        val downloaded = c.indexes("downloaded")
        val want = c.getValue("next").let { if (it is JsonNull) null else it.jsonPrimitive.int }
        assertEquals(name, want, PlaybackRules.nextPlayable(c.int("index"), c.int("items"), { it in downloaded }, c.bool("online")))
    }
}
