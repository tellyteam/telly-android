package io.github.tellyteam.telly.data.sync

import io.github.tellyteam.telly.data.store.LastWatchedStore.LastWatched
import io.github.tellyteam.telly.data.store.WatchProgressStore.Progress

/**
 * Between this device's own records and a sync parcel. The parcel gets ids and times only; what
 * comes back is turned into playable history using this device's own lists and login.
 */
object SyncLocal {
    data class Local(
        /** Progress by key (`series/123.mkv`). */
        val progress: Map<String, Progress>,
        /** Newest first. */
        val history: List<LastWatched>,
        /** My List, in its order. */
        val favourites: List<String>,
        /** My List key → (changed at, on). */
        val listChanges: Map<String, Pair<Long, Boolean>>,
        /** Sync slot → when it was taken out of Continue watching. */
        val removed: Map<String, Long>,
        /** When watch progress was last cleared, on any device (0 if never). */
        val cleared: Long = 0,
        /** The record of watched titles, by My List key. */
        val watched: Map<String, SyncWatched> = emptyMap(),
    )

    /** The shared form of a history entry; null for ones that can't be shared (no ids, or M3U URLs). */
    fun entryOf(h: LastWatched): SyncEntry? {
        val id = h.current.id.takeIf(SyncParcel::isSyncableId) ?: return null
        return when {
            h.live -> SyncEntry(SyncEntry.Kind.LIVE, id, at = h.savedAt)
            h.isFilm -> SyncEntry(SyncEntry.Kind.FILM, id, at = h.savedAt)
            SyncParcel.isSyncableId(h.seriesId) -> SyncEntry(SyncEntry.Kind.SERIES, id, h.seriesId, h.savedAt)
            else -> null
        }
    }

    private fun removalOf(slot: String, at: Long): SyncEntry? {
        if (slot == "live") return SyncEntry(SyncEntry.Kind.LIVE, "0", at = at, removed = true)
        val kind = slot.substringBefore(':')
        val id = slot.substringAfter(':').takeIf(SyncParcel::isSyncableId) ?: return null
        return when (kind) {
            "film" -> SyncEntry(SyncEntry.Kind.FILM, id, at = at, removed = true)
            "series" -> SyncEntry(SyncEntry.Kind.SERIES, id, id, at, removed = true)
            else -> null
        }
    }

    fun toParcel(local: Local, now: Long): SyncParcel {
        val progress = local.progress
            .filter { (k, p) -> SyncParcel.isSyncableKey(k) && p.durationMs > 0 }
            .mapValues { (_, p) -> SyncProgress(p.positionMs, p.durationMs, p.savedAt) }

        val history = LinkedHashMap<String, SyncEntry>()
        local.history.forEach { h -> entryOf(h)?.let { e -> history.putIfAbsent(e.slot, e) } }
        local.removed.forEach { (slot, at) ->
            val e = removalOf(slot, at) ?: return@forEach
            if ((history[slot]?.at ?: -1) < at) history[slot] = e
        }

        val list = LinkedHashMap<String, Pair<Long, Boolean>>()
        local.listChanges.forEach { (k, v) -> if (SyncParcel.isSyncableKey(k) && !v.second) list[k] = v }
        local.favourites.forEach { k -> if (SyncParcel.isSyncableKey(k)) list[k] = (local.listChanges[k]?.first ?: 0L) to true }

        val watched = local.watched.filterKeys(SyncParcel::isSyncableKey)
        return SyncParcel.trim(SyncParcel(now, progress, history.values.toList(), list, local.cleared, watched), now)
    }

    /** This device's records after taking in [merged]. Only newer things replace local ones. */
    suspend fun apply(local: Local, merged: SyncParcel, maxHistory: Int, resolve: suspend (SyncEntry) -> LastWatched?): Local {
        val cleared = maxOf(local.cleared, merged.cleared)
        val progress = local.progress.filterValues { SyncParcel.keptAfterClear(it.savedAt, cleared) }.toMutableMap()
        merged.progress.forEach { (k, v) ->
            if ((progress[k]?.savedAt ?: -1) < v.at) progress[k] = Progress(v.positionMs, v.durationMs, v.at)
        }

        val changes = local.listChanges.toMutableMap()
        val on = local.favourites.toMutableList()
        merged.list.forEach { (k, v) ->
            val mine = changes[k]?.first ?: if (k in on) 0L else -1L
            if (v.first <= mine) return@forEach
            changes[k] = v
            if (v.second) { if (k !in on) on += k } else on -= k
        }

        val removed = local.removed.toMutableMap()
        val bySlot = LinkedHashMap<String, LastWatched>()
        val unsynced = mutableListOf<LastWatched>()
        local.history.filter { SyncParcel.keptAfterClear(it.savedAt, cleared) }.forEach { h ->
            val slot = h.syncSlot
            if (slot == null) unsynced += h else bySlot.putIfAbsent(slot, h)
        }
        for (e in merged.history) {
            val mine = bySlot[e.slot]
            if (e.removed) {
                if (mine != null && mine.savedAt < e.at) bySlot.remove(e.slot)
                if ((removed[e.slot] ?: -1) < e.at && (mine == null || mine.savedAt < e.at)) removed[e.slot] = e.at
                continue
            }
            if (mine != null && mine.savedAt >= e.at) continue
            if ((removed[e.slot] ?: -1) >= e.at) continue
            val resolved = runCatching { resolve(e) }.getOrNull() ?: continue
            bySlot[e.slot] = resolved.copy(savedAt = e.at, newEpisodes = false)
        }
        // Newest first; entries from before sync (no time) keep their order after the rest.
        val history = (bySlot.values + unsynced).sortedByDescending { it.savedAt }.take(maxHistory)

        val watched = local.watched.filterValues { SyncParcel.keptAfterClear(it.at, cleared) }.toMutableMap()
        merged.watched.forEach { (k, v) -> if ((watched[k]?.at ?: -1) < v.at) watched[k] = v }

        return Local(progress, history, on, changes, removed, cleared, watched)
    }
}
