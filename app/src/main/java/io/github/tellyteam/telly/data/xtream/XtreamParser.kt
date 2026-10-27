package io.github.tellyteam.telly.data.xtream

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.decodeFromStream
import io.github.tellyteam.telly.data.Channel
import io.github.tellyteam.telly.data.Details
import io.github.tellyteam.telly.data.Season
import io.github.tellyteam.telly.data.Series
import io.github.tellyteam.telly.data.UNCATEGORISED
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.io.InputStream

/**
 * Parsing for the Xtream Codes `player_api.php` API.
 *
 * Catalogue lists can run to tens of thousands of entries, so they are decoded straight from the
 * response stream into small DTOs rather than built as a JSON tree. Panels are inconsistent about
 * numbers versus strings and nulls, hence lenient decoding with every field optional.
 */
@OptIn(ExperimentalSerializationApi::class)
object XtreamParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true // accepts unquoted numbers where a string field is declared
        coerceInputValues = true
        explicitNulls = false
    }

    @Serializable
    internal data class Category(
        @SerialName("category_id") val id: String? = null,
        @SerialName("category_name") val name: String? = null,
    )

    @Serializable
    internal data class LiveStream(
        @SerialName("stream_id") val streamId: String? = null,
        val name: String? = null,
        @SerialName("stream_icon") val icon: String? = null,
        @SerialName("epg_channel_id") val epgId: String? = null,
        @SerialName("category_id") val categoryId: String? = null,
    )

    @Serializable
    internal data class VodStream(
        @SerialName("stream_id") val streamId: String? = null,
        val name: String? = null,
        @SerialName("stream_icon") val icon: String? = null,
        @SerialName("category_id") val categoryId: String? = null,
        @SerialName("container_extension") val extension: String? = null,
        val added: String? = null,
    )

    @Serializable
    internal data class SeriesEntry(
        @SerialName("series_id") val seriesId: String? = null,
        val name: String? = null,
        val cover: String? = null,
        val plot: String? = null,
        @SerialName("category_id") val categoryId: String? = null,
        @SerialName("last_modified") val lastModified: String? = null,
    )

    @Serializable
    internal data class Episode(
        val id: String? = null,
        val title: String? = null,
        @SerialName("episode_num") val number: String? = null,
        val season: String? = null,
        @SerialName("container_extension") val extension: String? = null,
        /** Usually an object, but some panels send `[]` when empty. */
        val info: JsonElement? = null,
    ) {
        val image: String? get() = ((info as? JsonObject)?.get("movie_image") as? JsonPrimitive)?.contentOrNull
    }

    // ---- URLs ----

    /** Accepts `host:port`, `http://host:port/` and so on; defaults to HTTPS when no scheme is given. */
    fun serverUrl(input: String): HttpUrl {
        val trimmed = input.trim().trimEnd('/')
        val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
        return withScheme.toHttpUrl()
    }

    fun apiUrl(
        server: HttpUrl,
        username: String,
        password: String,
        action: String? = null,
        extra: Map<String, String> = emptyMap(),
    ): HttpUrl = server.newBuilder()
        .addPathSegment("player_api.php")
        .addQueryParameter("username", username)
        .addQueryParameter("password", password)
        .apply {
            if (action != null) addQueryParameter("action", action)
            extra.forEach { (k, v) -> addQueryParameter(k, v) }
        }
        .build()

    /** `{server}/{kind}/{user}/{pass}/{id}.{ext}`, the standard Xtream stream path. */
    fun streamUrl(server: HttpUrl, kind: String, username: String, password: String, id: String, extension: String): String =
        server.newBuilder()
            .addPathSegment(kind)
            .addPathSegment(username)
            .addPathSegment(password)
            .addPathSegment("$id.$extension")
            .build()
            .toString()

    /**
     * Stream addresses for one kind, quickly: everything before the file name is worked out once
     * rather than building each of tens of thousands of addresses in full. Exactly what
     * [streamUrl] gives (progress is kept by address).
     */
    class StreamUrls(private val server: HttpUrl, private val kind: String, private val username: String, private val password: String) {
        private val prefix = streamUrl(server, kind, username, password, "x", "x").removeSuffix("x.x")

        fun of(id: String, extension: String): String {
            val file = "$id.$extension"
            return if (id.isNotEmpty() && file.all { it.isLetterOrDigit() && it.code < 128 || it in "._~-" }) prefix + file
            else streamUrl(server, kind, username, password, id, extension)
        }
    }

    // ---- Account ----

    data class Account(
        val authenticated: Boolean,
        val status: String?,
        val outputFormats: List<String>,
        /** When the subscription ends (epoch ms); null when the provider gives no end date. */
        val expiresAtMs: Long? = null,
        val maxConnections: Int? = null,
        val activeConnections: Int? = null,
        val trial: Boolean = false,
    )

    fun account(root: JsonElement): Account {
        val userInfo = (root as? JsonObject)?.get("user_info") as? JsonObject
            ?: return Account(authenticated = false, status = null, outputFormats = emptyList())
        fun number(key: String) = userInfo.string(key)?.trim()?.toLongOrNull()
        return Account(
            authenticated = userInfo.string("auth") == "1",
            status = userInfo.string("status"),
            outputFormats = (userInfo["allowed_output_formats"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .orEmpty(),
            // Seconds since 1970; panels send null, "null" or 0 for "never expires".
            expiresAtMs = number("exp_date")?.takeIf { it > 0 }?.let { it * 1000 },
            maxConnections = number("max_connections")?.toInt()?.takeIf { it > 0 },
            activeConnections = number("active_cons")?.toInt()?.takeIf { it >= 0 },
            trial = userInfo.string("is_trial") == "1",
        )
    }

    /** HLS plays most reliably in ExoPlayer, so prefer it when the panel allows it. */
    fun preferredFormat(allowed: List<String>): String = if (allowed.isEmpty() || "m3u8" in allowed) "m3u8" else "ts"

    // ---- Catalogues ----

    /** Category id to name, in the provider's order. */
    fun categories(input: InputStream): LinkedHashMap<String, String> {
        val result = LinkedHashMap<String, String>()
        decodeList<Category>(input).forEach { c -> c.id?.let { result[it] = c.name?.trim().orEmpty().ifEmpty { UNCATEGORISED } } }
        return result
    }

    fun liveChannels(
        server: HttpUrl, username: String, password: String, format: String,
        categories: Map<String, String>, input: InputStream,
    ): List<Channel> {
        val urls = StreamUrls(server, "live", username, password)
        return decodeList<LiveStream>(input).mapNotNull { s ->
        val id = s.streamId?.ifBlank { null } ?: return@mapNotNull null
        Channel(
            id = id,
            name = s.name?.trim().orEmpty().ifEmpty { "Channel $id" },
            group = categories[s.categoryId] ?: UNCATEGORISED,
            logoUrl = s.icon?.ifBlank { null },
            streamUrl = urls.of(id, format),
            epgId = s.epgId?.ifBlank { null },
        )
        }
    }

    fun films(
        server: HttpUrl, username: String, password: String,
        categories: Map<String, String>, input: InputStream,
    ): List<Channel> {
        val urls = StreamUrls(server, "movie", username, password)
        return decodeList<VodStream>(input).mapNotNull { s ->
        val id = s.streamId?.ifBlank { null } ?: return@mapNotNull null
        Channel(
            id = id,
            name = s.name?.trim().orEmpty().ifEmpty { "Film $id" },
            group = categories[s.categoryId] ?: UNCATEGORISED,
            logoUrl = s.icon?.ifBlank { null },
            streamUrl = urls.of(id, s.extension?.ifBlank { null } ?: "mp4"),
            added = s.added?.toLongOrNull() ?: 0L,
        )
        }
    }

    fun series(categories: Map<String, String>, input: InputStream): List<Series> =
        decodeList<SeriesEntry>(input).mapNotNull { s ->
            val id = s.seriesId?.ifBlank { null } ?: return@mapNotNull null
            Series(
                id = id,
                name = s.name?.trim().orEmpty().ifEmpty { "Series $id" },
                group = categories[s.categoryId] ?: UNCATEGORISED,
                coverUrl = s.cover?.ifBlank { null },
                plot = s.plot?.ifBlank { null },
                added = s.lastModified?.toLongOrNull() ?: 0L,
            )
        }

    /**
     * Seasons from `get_series_info`. `episodes` is usually an object keyed by season number,
     * but some panels send a list of lists instead.
     */
    fun seasons(server: HttpUrl, username: String, password: String, info: JsonElement): List<Season> {
        val episodes = (info as? JsonObject)?.get("episodes") ?: return emptyList()
        val bySeason: List<Pair<String?, JsonElement>> = when (episodes) {
            is JsonObject -> episodes.entries.map { it.key to it.value }
            is JsonArray -> episodes.map { null to it }
            else -> emptyList()
        }
        return bySeason.mapNotNull { (key, list) ->
            val parsed = (list as? JsonArray).orEmpty().mapNotNull { element ->
                runCatching { json.decodeFromJsonElement<Episode>(element) }.getOrNull()
            }
            val seasonName = key ?: parsed.firstNotNullOfOrNull { it.season } ?: return@mapNotNull null
            val channels = parsed.mapNotNull { e ->
                val id = e.id?.ifBlank { null } ?: return@mapNotNull null
                val label = listOfNotNull(e.number?.let { "E$it" }, e.title?.let(::episodeTitle)).joinToString("  ")
                Channel(
                    id = id,
                    name = label.ifEmpty { "Episode $id" },
                    group = "Season $seasonName",
                    logoUrl = e.image?.ifBlank { null },
                    streamUrl = streamUrl(server, "series", username, password, id, e.extension?.ifBlank { null } ?: "mp4"),
                )
            }
            if (channels.isEmpty()) null else Season("Season $seasonName", channels)
        }.sortedBy { it.name.removePrefix("Season ").toIntOrNull() ?: Int.MAX_VALUE }
    }

    private val showAndCode = Regex("""^.*?\bS\d{1,2}\s*E\d{1,3}\b\s*[-–:|]?\s*""", RegexOption.IGNORE_CASE)

    /** "Ted Lasso - S04E10 - Being Alive" → "Being Alive"; titles without a code are kept as they are. */
    internal fun episodeTitle(raw: String): String? {
        val title = raw.trim()
        return title.replace(showAndCode, "").trim().ifBlank { title }.ifBlank { null }
    }

    /**
     * The `info` block of `get_vod_info` or `get_series_info`. Panels disagree on field names,
     * numbers versus strings and whether backdrops are a list, so each value is read leniently.
     */
    fun details(root: JsonElement): Details {
        val info = (root as? JsonObject)?.get("info") as? JsonObject ?: return Details()
        fun text(vararg keys: String) = keys.firstNotNullOfOrNull { info.string(it)?.trim()?.ifBlank { null } }
        val backdrop = when (val b = info["backdrop_path"]) {
            is JsonArray -> b.firstNotNullOfOrNull { (it as? JsonPrimitive)?.contentOrNull?.ifBlank { null } }
            is JsonPrimitive -> b.contentOrNull?.ifBlank { null }
            else -> null
        }
        val rating = text("rating", "rating_5based")?.toDoubleOrNull()?.takeIf { it > 0 }?.let { r ->
            // rating_5based is out of five; scale it to ten like the rest.
            val ten = if (info.string("rating")?.toDoubleOrNull()?.takeIf { it > 0 } == null) r * 2 else r
            String.format(java.util.Locale.UK, "%.1f", ten)
        }
        return Details(
            plot = text("plot", "description"),
            genre = text("genre"),
            year = text("releasedate", "releaseDate", "release_date", "year")?.let { Regex("""\d{4}""").find(it)?.value },
            rating = rating,
            runtime = runtime(info),
            cast = text("cast", "actors"),
            backdropUrl = backdrop ?: text("cover_big", "movie_image"),
        )
    }

    private fun runtime(info: JsonObject): String? {
        info.string("duration_secs")?.toLongOrNull()?.takeIf { it > 0 }?.let { return minutesLabel(it / 60) }
        info.string("duration")?.let { d ->
            val parts = d.split(':').mapNotNull { it.trim().toLongOrNull() }
            if (parts.size == 3) return minutesLabel(parts[0] * 60 + parts[1])
        }
        info.string("episode_run_time")?.toLongOrNull()?.takeIf { it > 0 }?.let { return "$it min" }
        return null
    }

    private fun minutesLabel(minutes: Long): String? = when {
        minutes <= 0 -> null
        minutes < 60 -> "$minutes min"
        else -> "${minutes / 60}h ${minutes % 60}m"
    }

    /** Some panels answer an empty catalogue with `{}` or an error object instead of `[]`. */
    private inline fun <reified T> decodeList(input: InputStream): List<T> = try {
        json.decodeFromStream<List<T>>(input)
    } catch (e: SerializationException) {
        throw IOException("The provider sent a list in an unexpected format.", e)
    } catch (e: IllegalArgumentException) {
        throw IOException("The provider sent a list in an unexpected format.", e)
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
