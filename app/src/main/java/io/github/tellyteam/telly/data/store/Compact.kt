package io.github.tellyteam.telly.data.store

import io.github.tellyteam.telly.data.Channel
import io.github.tellyteam.telly.data.ChannelGroup
import io.github.tellyteam.telly.data.Series
import io.github.tellyteam.telly.data.SeriesGroup
import io.github.tellyteam.telly.data.xtream.XtreamParser
import java.io.BufferedReader
import java.io.Writer

/**
 * Catalogues saved as telly uses them (telly-sync PLAYBACK.md, Lists): only the fields shown, one
 * item per line, read back many times faster than the provider's own answer, which can run to tens
 * of megabytes. Stream addresses are left out, as they carry the login, and rebuilt from each
 * item's id when read.
 */
object Compact {
    private const val HEADER = "telly-compact 1"
    private const val GROUP = '\u001E'
    private const val FIELD = '\u001F'

    // ---- Channels and films ----

    fun writeChannels(groups: List<ChannelGroup>, out: Writer) = write(groups, out, { it.name }, { it.channels }) { c ->
        listOf(c.id, c.name, c.logoUrl.orEmpty(), c.epgId.orEmpty(), if (c.added > 0) c.added.toString() else "", c.streamUrl.substringAfterLast('.'))
    }

    /** The groups again, each stream's address made by [urls]; null if [input] isn't a saved catalogue. */
    fun readChannels(input: BufferedReader, urls: XtreamParser.StreamUrls): List<ChannelGroup>? =
        read(input, ::ChannelGroup) { f, group ->
            if (f.size != 6) return@read null
            Channel(
                id = f[0], name = f[1], group = group, logoUrl = f[2].ifEmpty { null }, streamUrl = urls.of(f[0], f[5]),
                epgId = f[3].ifEmpty { null }, added = f[4].toLongOrNull() ?: 0L,
            )
        }

    // ---- Series ----

    /** No plot: it's shown only on the series' page, which fetches it, and it's most of the list's size. */
    fun writeSeries(groups: List<SeriesGroup>, out: Writer) = write(groups, out, { it.name }, { it.series }) { s ->
        listOf(s.id, s.name, s.coverUrl.orEmpty(), if (s.added > 0) s.added.toString() else "")
    }

    fun readSeries(input: BufferedReader): List<SeriesGroup>? = read(input, ::SeriesGroup) { f, group ->
        if (f.size != 4) return@read null
        Series(id = f[0], name = f[1], group = group, coverUrl = f[2].ifEmpty { null }, plot = null, added = f[3].toLongOrNull() ?: 0L)
    }

    // ---- The format ----

    private fun <G, T> write(groups: List<G>, out: Writer, name: (G) -> String, items: (G) -> List<T>, fields: (T) -> List<String>) {
        out.write(HEADER)
        out.write('\n'.code)
        for (g in groups) {
            out.write(GROUP.code)
            out.write(clean(name(g)))
            out.write('\n'.code)
            for (item in items(g)) {
                fields(item).forEachIndexed { i, f ->
                    if (i > 0) out.write(FIELD.code)
                    out.write(clean(f))
                }
                out.write('\n'.code)
            }
        }
    }

    private fun <G, T> read(input: BufferedReader, group: (String, List<T>) -> G, make: (List<String>, String) -> T?): List<G>? {
        if (input.readLine() != HEADER) return null
        val groups = ArrayList<G>()
        var name: String? = null
        var items = ArrayList<T>()
        while (true) {
            val line = input.readLine() ?: break
            if (line.isEmpty()) continue
            if (line[0] == GROUP) {
                name?.let { groups += group(it, items) }
                name = line.substring(1)
                items = ArrayList()
                continue
            }
            val current = name ?: continue
            make(line.split(FIELD), current)?.let { items += it }
        }
        name?.let { groups += group(it, items) }
        return groups
    }

    /** Fields can't hold the format's own separators: line breaks become spaces. */
    private fun clean(s: String): String =
        if (s.none { it == '\n' || it == '\r' || it == GROUP || it == FIELD }) s
        else s.map { if (it == '\n' || it == '\r' || it == GROUP || it == FIELD) ' ' else it }.joinToString("")
}
