package io.github.tellyteam.telly.data.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** A Continue watching entry as shared: ids and a time only. */
data class SyncEntry(
    val kind: Kind,
    /** The channel, film or episode id. */
    val id: String,
    /** Series only. */
    val series: String? = null,
    val at: Long,
    val removed: Boolean = false,
) {
    enum class Kind(val wire: String) { LIVE("live"), FILM("film"), SERIES("series") }

    val slot: String get() = when (kind) {
        Kind.LIVE -> "live"
        Kind.FILM -> "film:$id"
        Kind.SERIES -> "series:$series"
    }
}

data class SyncProgress(val positionMs: Long, val durationMs: Long, val at: Long)

/** Whether a title is watched, since [at]; [episode] is where a series was watched up to (empty for a film). */
data class SyncWatched(val at: Long, val on: Boolean, val episode: String = "")

/**
 * One provider's share of what a device syncs (telly-sync PROTOCOL.md): positions, history and
 * My List, by the provider's own ids. Version 1 parcels were exactly one of these; see [SyncBundle].
 */
data class SyncParcel(
    val at: Long = 0,
    val progress: Map<String, SyncProgress> = emptyMap(),
    val history: List<SyncEntry> = emptyList(),
    /** My List key → (changed at, on the list). */
    val list: Map<String, Pair<Long, Boolean>> = emptyMap(),
    /** When watch progress was last cleared on any device (0 if never): older progress and history are dropped. */
    val cleared: Long = 0,
    /** `film:<id>` or `series:<id>` → watched or not (PLAYBACK.md, Watched titles). */
    val watched: Map<String, SyncWatched> = emptyMap(),
) {
    /** Version 1's parcel, which is a section with `v` and `at` beside it. */
    fun toJson(): String = JsonObject(mapOf("v" to JsonPrimitive(1), "at" to JsonPrimitive(at)) + sectionObject()).toString()

    fun sectionObject(): JsonObject = buildJsonObject {
        put("progress", buildJsonObject {
            progress.forEach { (k, p) -> put(k, buildJsonArray { add(JsonPrimitive(p.positionMs)); add(JsonPrimitive(p.durationMs)); add(JsonPrimitive(p.at)) }) }
        })
        put("history", buildJsonArray {
            history.forEach { e ->
                add(buildJsonObject {
                    put("kind", e.kind.wire)
                    put("id", e.id)
                    if (e.kind == SyncEntry.Kind.SERIES) put("series", e.series)
                    put("at", e.at)
                    if (e.removed) put("removed", true)
                })
            }
        })
        put("list", buildJsonObject {
            list.forEach { (k, v) -> put(k, buildJsonArray { add(JsonPrimitive(v.first)); add(JsonPrimitive(v.second)) }) }
        })
        put("watched", buildJsonObject {
            watched.forEach { (k, v) -> put(k, buildJsonArray { add(JsonPrimitive(v.at)); add(JsonPrimitive(v.on)); add(JsonPrimitive(v.episode)) }) }
        })
        put("cleared", cleared)
    }

    companion object {
        const val MAX_PROGRESS = 2000
        const val MAX_HISTORY = 40
        const val MAX_LIST = 2000
        const val MAX_WATCHED = 2000

        /** Removals are shared for this long, then forgotten. */
        const val TOMBSTONE_MS = 90L * 86_400_000

        /** Plain keys only (`series/123.mkv`, `film:456`): anything URL-like might carry a login. */
        private val KEY = Regex("^[a-z]{1,8}[/:][A-Za-z0-9._-]{1,40}$")
        private val ID = Regex("^[A-Za-z0-9._-]{1,40}$")

        /** Whether something saved at [at] survives a clear at [cleared] (0: never cleared). */
        fun keptAfterClear(at: Long, cleared: Long) = cleared == 0L || at > cleared

        fun isSyncableKey(key: String) = KEY.matches(key)
        fun isSyncableId(id: String?) = id != null && ID.matches(id)

        /** Whole milliseconds, but take fractions from a client that writes them rather than drop the entry. */
        internal fun time(e: Any?) = (e as? JsonPrimitive)?.takeIf { !it.isString }
            ?.let { it.longOrNull ?: it.doubleOrNull?.takeIf(Double::isFinite)?.let(Math::round) }?.takeIf { it >= 0 }

        /** A version 1 parcel, reduced to well-formed entries; null if it isn't one. */
        fun parse(text: String): SyncParcel? {
            val raw = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
            if ((raw["v"] as? JsonPrimitive)?.longOrNull != 1L) return null
            return parseSection(raw, time(raw["at"]) ?: 0)
        }

        /** A section another device sent, reduced to well-formed entries. */
        fun parseSection(raw: JsonObject, at: Long): SyncParcel {
            fun JsonObject?.entries() = this?.entries.orEmpty()

            val progress = buildMap {
                (raw["progress"] as? JsonObject).entries().forEach { (k, v) ->
                    val a = v as? JsonArray ?: return@forEach
                    if (!isSyncableKey(k) || a.size != 3) return@forEach
                    val (pos, dur, at) = a.map(::time)
                    if (pos != null && dur != null && at != null && dur > 0) put(k, SyncProgress(pos, dur, at))
                }
            }
            val history = (raw["history"] as? JsonArray).orEmpty().mapNotNull { element ->
                val h = element as? JsonObject ?: return@mapNotNull null
                val kind = SyncEntry.Kind.entries.firstOrNull { it.wire == (h["kind"] as? JsonPrimitive)?.contentOrNull } ?: return@mapNotNull null
                val id = (h["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(::isSyncableId) ?: return@mapNotNull null
                val at = time(h["at"]) ?: return@mapNotNull null
                val series = (h["series"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (kind == SyncEntry.Kind.SERIES && !isSyncableId(series)) return@mapNotNull null
                val removed = (h["removed"] as? JsonPrimitive)?.booleanOrNull == true
                SyncEntry(kind, id, series.takeIf { kind == SyncEntry.Kind.SERIES }, at, removed)
            }
            val list = buildMap {
                (raw["list"] as? JsonObject).entries().forEach { (k, v) ->
                    val a = v as? JsonArray ?: return@forEach
                    if (!isSyncableKey(k) || a.size != 2) return@forEach
                    val at = time(a[0]) ?: return@forEach
                    val on = (a[1] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: return@forEach
                    put(k, at to on)
                }
            }
            val watched = buildMap {
                (raw["watched"] as? JsonObject).entries().forEach { (k, v) ->
                    val a = v as? JsonArray ?: return@forEach
                    if (!isSyncableKey(k) || a.size != 3) return@forEach
                    val at = time(a[0]) ?: return@forEach
                    val on = (a[1] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: return@forEach
                    val episode = (a[2] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@forEach
                    if (episode.isEmpty() || isSyncableId(episode)) put(k, SyncWatched(at, on, episode))
                }
            }
            return SyncParcel(at, progress, history, list, time(raw["cleared"]) ?: 0, watched)
        }

        /** Keeps a parcel within its limits and drops removals older than [TOMBSTONE_MS]. */
        fun trim(p: SyncParcel, now: Long): SyncParcel {
            fun fresh(at: Long, removal: Boolean) = !removal || now - at < TOMBSTONE_MS
            return SyncParcel(
                at = p.at,
                progress = p.progress.entries.filter { keptAfterClear(it.value.at, p.cleared) }.sortedByDescending { it.value.at }.take(MAX_PROGRESS).associate { it.key to it.value },
                history = p.history.filter { fresh(it.at, it.removed) && keptAfterClear(it.at, p.cleared) }.sortedByDescending { it.at }.take(MAX_HISTORY),
                list = p.list.entries.filter { fresh(it.value.first, !it.value.second) }
                    .sortedByDescending { it.value.first }.take(MAX_LIST).associate { it.key to it.value },
                cleared = p.cleared,
                watched = p.watched.entries.filter { fresh(it.value.at, !it.value.on) && keptAfterClear(it.value.at, p.cleared) }
                    .sortedByDescending { it.value.at }.take(MAX_WATCHED).associate { it.key to it.value },
            )
        }

        /** [own] combined with other devices' parcels: per key and history slot the newest wins; [own] wins a tie. */
        fun merge(own: SyncParcel, others: List<SyncParcel>, now: Long): SyncParcel {
            val progress = own.progress.toMutableMap()
            val list = own.list.toMutableMap()
            val watched = own.watched.toMutableMap()
            val history = LinkedHashMap<String, SyncEntry>().apply { own.history.forEach { put(it.slot, it) } }
            var cleared = own.cleared
            for (p in others) {
                cleared = maxOf(cleared, p.cleared)
                p.progress.forEach { (k, v) -> if ((progress[k]?.at ?: -1) < v.at) progress[k] = v }
                p.list.forEach { (k, v) -> if ((list[k]?.first ?: -1) < v.first) list[k] = v }
                p.watched.forEach { (k, v) -> if ((watched[k]?.at ?: -1) < v.at) watched[k] = v }
                p.history.forEach { h -> if ((history[h.slot]?.at ?: -1) < h.at) history[h.slot] = h }
            }
            return trim(SyncParcel(now, progress, history.values.toList(), list, cleared, watched), now)
        }
    }
}
