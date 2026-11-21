package io.github.tellyteam.telly.data.sync

import io.github.tellyteam.telly.data.Provider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Joining by QR code from the new device (telly-sync PROTOCOL.md, telly-lg SETUP-BY-PHONE.md): a TV
 * shows `https://tv.gettelly.app/#id=…&key=…`; a phone leaves a link code, a provider or both in
 * the relay's join box, sealed with the key, for the TV to collect. The key is only ever in the QR
 * code. Older TVs show `telly://join?id=…&key=…`, which takes only a link code.
 *
 * telly only acts on a link its own scanner read off a screen, never one the system opens: a link
 * in a message could otherwise ask for a link code or a login to be sent to a stranger's box.
 */
class SyncJoin(
    val id: String,
    val key: ByteArray,
    /** The `tv.gettelly.app` form: the device showing it takes a provider too, not only a link code. */
    val takesProvider: Boolean = true,
) {
    val url: String
        get() = Base64Text.encode(key, url = true).let { k ->
            if (takesProvider) "https://tv.gettelly.app/#id=$id&key=$k" else "telly://join?id=$id&key=$k"
        }

    private val aad get() = "telly-join v1|$id".toByteArray()

    /** What a join box holds: a normalised link code, a provider, or both. */
    data class Message(val code: String? = null, val provider: Provider? = null)

    /** A link code and/or a provider, sealed for this join's box, in the order SETUP-BY-PHONE.md writes them. */
    fun seal(code: String? = null, provider: Provider? = null, nonce: ByteArray = ByteArray(12).also(SyncCrypto.random::nextBytes)): ByteArray {
        require(code != null || provider != null) { "There's nothing to send." }
        val json = buildJsonObject {
            if (code != null) put("code", code)
            if (provider != null) put("provider", buildJsonObject {
                when (provider) {
                    is Provider.Xtream -> {
                        put("kind", "xtream")
                        put("name", provider.name)
                        put("server", provider.server)
                        put("username", provider.username)
                        put("password", provider.password)
                    }
                    is Provider.M3u -> {
                        put("kind", "m3u")
                        put("name", provider.name)
                        put("url", provider.url)
                    }
                }
                put("allowCleartext", provider.allowCleartext)
            })
        }
        return SyncCrypto.sealBox(key, aad, json.toString(), nonce)
    }

    /**
     * What's in a box, or null if it isn't for this join, was altered, or isn't a message telly
     * reads. Anyone who saw the TV's screen could have sent it, so it's checked as untrusted input:
     * any field over its limit, of the wrong type or empty makes the whole message unreadable.
     */
    fun open(box: ByteArray): Message? {
        val text = SyncCrypto.openBox(key, aad, box) ?: return null
        val o = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        var message = Message()
        o["code"]?.let { v ->
            val code = v.string()?.let(SyncCrypto::normaliseCode) ?: return null
            message = message.copy(code = code)
        }
        o["provider"]?.let { v ->
            val provider = (v as? JsonObject)?.let(::readProvider) ?: return null
            message = message.copy(provider = provider)
        }
        return message.takeIf { it.code != null || it.provider != null }
    }

    companion object {
        /** The receiver's limits (SETUP-BY-PHONE.md section 2), in UTF-16 units as telly-apple counts them. */
        const val MAX_FIELD = 255
        const val MAX_URL = 2048
        const val MAX_NAME = 60

        fun new(): SyncJoin = SyncJoin(ByteArray(16).also(SyncCrypto.random::nextBytes).joinToString("") { "%02x".format(it) }, ByteArray(32).also(SyncCrypto.random::nextBytes))

        /**
         * The join from a scanned link, either form, or null if it isn't one. Only ever called with
         * what telly's own scanner read, or a TV's own code.
         */
        fun parse(text: String): SyncJoin? {
            val t = text.trim()
            val old = Regex("^telly://join\\?(.*)$", RegexOption.IGNORE_CASE).find(t)
            // Nothing but https://tv.gettelly.app/ with the id and key in the fragment.
            val new = Regex("^https://tv\\.gettelly\\.app/?#(.*)$", RegexOption.IGNORE_CASE).find(t)
            val params = (old ?: new)?.groupValues?.get(1) ?: return null
            val values = params.split('&').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 } }
            fun value(name: String) = values.firstOrNull { it[0] == name }?.get(1)
            val id = value("id")?.lowercase()?.takeIf { Regex("^[0-9a-f]{32}$").matches(it) } ?: return null
            val key = value("key")?.let(Base64Text::decode)?.takeIf { it.size == 32 } ?: return null
            return SyncJoin(id, key, takesProvider = old == null)
        }

        /**
         * [provider] as it goes to a TV: trimmed as the TV trims, the name shortened to fit, or null
         * if a detail is still too long for the TV to take.
         */
        fun sendable(provider: Provider): Provider? {
            val name = provider.name.take(MAX_NAME)
            return when (provider) {
                is Provider.Xtream -> {
                    val server = provider.server.trim()
                    val username = provider.username.trim()
                    if (server.isEmpty() || server.length > MAX_FIELD || username.length > MAX_FIELD || provider.password.length > MAX_FIELD) null
                    else provider.copy(name = name, server = server, username = username)
                }
                is Provider.M3u -> {
                    val url = provider.url.trim()
                    if (url.isEmpty() || url.length > MAX_URL) null else provider.copy(name = name, url = url)
                }
            }
        }

        private fun JsonElement.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

        /** A provider from a message, or null if it breaks section 2's rules. */
        private fun readProvider(o: JsonObject): Provider? {
            /** The field's text, or null if it's missing, not a string, empty or too long. */
            fun text(name: String, max: Int, trim: Boolean = true): String? {
                val s = o[name]?.string() ?: return null
                return (if (trim) s.trim() else s).takeIf { it.isNotEmpty() && it.length <= max }
            }
            val name = when (val v = o["name"]) {
                null, JsonNull -> ""
                else -> v.string()?.takeIf { it.length <= MAX_NAME } ?: return null
            }.trim()
            val allow = when (val v = o["allowCleartext"]) {
                null, JsonNull -> false
                else -> (v as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: return null
            }
            return when (o["kind"]?.string()) {
                "xtream" -> Provider.Xtream(
                    name = name,
                    server = text("server", MAX_FIELD) ?: return null,
                    username = text("username", MAX_FIELD) ?: return null,
                    password = text("password", MAX_FIELD, trim = false) ?: return null,
                    allowCleartext = allow,
                )
                "m3u" -> Provider.M3u(name, text("url", MAX_URL) ?: return null, allow)
                else -> null
            }
        }
    }
}
