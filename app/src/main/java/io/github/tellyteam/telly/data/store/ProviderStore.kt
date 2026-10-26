package io.github.tellyteam.telly.data.store

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import io.github.tellyteam.telly.data.DEFAULT_PLAYLIST_NAME
import io.github.tellyteam.telly.data.DEFAULT_PROVIDER_NAME
import io.github.tellyteam.telly.data.Provider
import io.github.tellyteam.telly.data.sync.SyncCrypto.toHex
import java.security.SecureRandom

/** A provider saved on this device. [changedAt] is when it was added or last changed (epoch ms; 0 if unknown). */
data class SavedProvider(val id: String, val provider: Provider, val changedAt: Long = 0)

/**
 * The saved providers, encrypted at rest, and which one is in use. Each provider's My List,
 * progress and history are kept apart under its [SavedProvider.id] (see [Scoped]).
 */
class ProviderStore(context: Context) {
    private val prefs = context.getSharedPreferences("provider", Context.MODE_PRIVATE)
    private val cipher = KeystoreCipher("telly_provider")

    init {
        // Sharing logins between devices was removed: forget what it kept.
        if (prefs.contains(REMOVED)) prefs.edit().remove(REMOVED).apply()
    }

    /** Every saved provider; empty when there are none or they can no longer be decrypted. */
    fun loadAll(): List<SavedProvider> =
        runCatching { prefs.getString(LIST, null)?.let { decodeList(cipher.decrypt(it)) } }.getOrNull().orEmpty()

    fun saveAll(list: List<SavedProvider>) {
        prefs.edit().putString(LIST, cipher.encrypt(encodeList(list))).apply()
    }

    /** The provider in use, by id. */
    var activeId: String?
        get() = prefs.getString(ACTIVE, null)
        set(value) = prefs.edit().putString(ACTIVE, value).apply()

    /**
     * Before there could be several, the one provider was kept on its own and its records unscoped.
     * Makes it the first saved provider and returns its new id (the caller moves the records), or
     * null when there's nothing to move.
     */
    fun migrateSingle(): String? {
        if (prefs.contains(LIST)) return null
        val old = runCatching { prefs.getString(OLD_KEY, null)?.let { decode(cipher.decrypt(it)) } }.getOrNull() ?: return null
        val saved = SavedProvider(newId(), old, System.currentTimeMillis())
        saveAll(listOf(saved))
        prefs.edit().remove(OLD_KEY).putString(ACTIVE, saved.id).apply()
        return saved.id
    }

    internal companion object {
        private const val OLD_KEY = "provider"
        private const val LIST = "providers"
        private const val ACTIVE = "active"
        private const val REMOVED = "removedLogins"
        private val random = SecureRandom()

        fun newId(): String = ByteArray(6).also(random::nextBytes).toHex()

        fun encodeList(list: List<SavedProvider>): String = buildJsonArray {
            list.forEach { s ->
                add(buildJsonObject {
                    put("id", s.id)
                    put("changedAt", s.changedAt)
                    put("provider", Json.parseToJsonElement(encode(s.provider)))
                })
            }
        }.toString()

        fun decodeList(text: String): List<SavedProvider> =
            (Json.parseToJsonElement(text) as? JsonArray).orEmpty().mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val id = (o["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val provider = runCatching { decode(o["provider"].toString()) }.getOrNull() ?: return@mapNotNull null
                SavedProvider(id, provider, (o["changedAt"] as? JsonPrimitive)?.longOrNull ?: 0)
            }

        fun encode(provider: Provider): String = buildJsonObject {
            put("name", provider.name)
            put("allowCleartext", provider.allowCleartext)
            when (provider) {
                is Provider.Xtream -> {
                    put("type", "xtream")
                    put("server", provider.server)
                    put("username", provider.username)
                    put("password", provider.password)
                }
                is Provider.M3u -> {
                    put("type", "m3u")
                    put("url", provider.url)
                }
            }
        }.toString()

        fun decode(text: String): Provider {
            val o = Json.parseToJsonElement(text).jsonObject
            // Earlier versions' default names.
            val name = when (val saved = o.str("name")) {
                "My provider" -> DEFAULT_PROVIDER_NAME
                "My playlist" -> DEFAULT_PLAYLIST_NAME
                else -> saved
            }
            val allowCleartext = (o["allowCleartext"] as? JsonPrimitive)?.booleanOrNull ?: false
            return when (o.str("type")) {
                "xtream" -> Provider.Xtream(name, o.str("server"), o.str("username"), o.str("password"), allowCleartext)
                "m3u" -> Provider.M3u(name, o.str("url"), allowCleartext)
                else -> error("Unknown provider type")
            }
        }

        private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    }
}

/**
 * A provider's own copy of a record (its My List, progress, history, categories): the shared
 * preferences file `name-<provider id>`. Before there could be several providers they were
 * unscoped (`name`); [move] carries them across once.
 */
object Scoped {
    /** The records kept per provider. */
    val NAMES = listOf("favourites", "watch_progress", "last_watched", "categories")

    fun name(name: String, scope: String?) = if (scope == null) name else "$name-$scope"

    fun move(context: Context, from: String?, to: String) {
        for (n in NAMES) {
            val source = context.getSharedPreferences(name(n, from), Context.MODE_PRIVATE)
            val target = context.getSharedPreferences(name(n, to), Context.MODE_PRIVATE).edit().clear()
            source.all.forEach { (k, v) ->
                when (v) {
                    is String -> target.putString(k, v)
                    is Long -> target.putLong(k, v)
                    is Int -> target.putInt(k, v)
                    is Boolean -> target.putBoolean(k, v)
                    is Float -> target.putFloat(k, v)
                    is Set<*> -> target.putStringSet(k, v.filterIsInstance<String>().toSet())
                }
            }
            target.commit()
            source.edit().clear().commit()
        }
    }

    /** Forgets a provider's records (its history holds stream URLs, which carry the login). */
    fun delete(context: Context, scope: String) {
        for (n in NAMES) {
            context.getSharedPreferences(name(n, scope), Context.MODE_PRIVATE).edit().clear().commit()
            if (android.os.Build.VERSION.SDK_INT >= 24) context.deleteSharedPreferences(name(n, scope))
        }
    }
}
