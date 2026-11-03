package io.github.tellyteam.telly.data.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Joining by QR code from the new device (PROTOCOL.md): a TV that isn't linked shows
 * `telly://join?id=…&key=…`; an iPhone or iPad in the household scans it and leaves its link code
 * in the relay's join box, sealed with the key, for this TV to collect. The key is only ever in
 * the QR code.
 */
class SyncJoin(val id: String, val key: ByteArray) {
    val url: String get() = "telly://join?id=$id&key=${Base64Text.encode(key, url = true)}"

    private val aad get() = "telly-join v1|$id".toByteArray()

    /** The household's link code, sealed for this join's box. */
    fun seal(code: String, nonce: ByteArray = ByteArray(12).also(SyncCrypto.random::nextBytes)): ByteArray =
        SyncCrypto.sealBox(key, aad, buildJsonObject { put("code", code) }.toString(), nonce)

    /** The link code in a box, or null if it isn't for this join or was altered. */
    fun open(box: ByteArray): String? {
        val text = SyncCrypto.openBox(key, aad, box) ?: return null
        val code = runCatching { ((Json.parseToJsonElement(text) as? JsonObject)?.get("code") as? JsonPrimitive)?.content }.getOrNull()
        return code?.let(SyncCrypto::normaliseCode)
    }

    companion object {
        fun new(): SyncJoin = SyncJoin(ByteArray(16).also(SyncCrypto.random::nextBytes).joinToString("") { "%02x".format(it) }, ByteArray(32).also(SyncCrypto.random::nextBytes))

        fun parse(text: String): SyncJoin? {
            val m = Regex("^telly://join\\?id=([0-9a-f]{32})&key=([A-Za-z0-9_-]+)$", RegexOption.IGNORE_CASE).find(text.trim()) ?: return null
            val key = Base64Text.decode(m.groupValues[2]) ?: return null
            return if (key.size == 32) SyncJoin(m.groupValues[1].lowercase(), key) else null
        }
    }
}
