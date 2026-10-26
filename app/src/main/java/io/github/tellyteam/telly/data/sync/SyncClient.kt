package io.github.tellyteam.telly.data.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.io.IOException

class SyncException(message: String) : IOException(message)

/** Talking to the relay. Only sealed parcels ever leave the device. */
class SyncClient(private val http: OkHttpClient) {

    /**
     * Every other device's parcel that opens with our key; anything else is ignored. [current] is
     * the tag a version 1 parcel is read as (null: ignored).
     */
    suspend fun pull(relay: String, keys: SyncCrypto.Keys, device: String, current: String?): List<SyncBundle> {
        val text = send(Request.Builder().url("${base(relay)}/v1/${keys.mailbox}").get(), keys)
        val parcels = (Json.parseToJsonElement(text) as? JsonObject)?.get("parcels") as? JsonArray ?: return emptyList()
        return parcels.mapNotNull { element ->
            val p = element as? JsonObject ?: return@mapNotNull null
            val from = (p["device"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            if (from == device) return@mapNotNull null
            val box = (p["body"] as? JsonPrimitive)?.content?.let(Base64Text::decode) ?: return@mapNotNull null
            SyncCrypto.open(keys, from, box)?.let { SyncBundle.parse(it, current) }
        }
    }

    suspend fun push(relay: String, keys: SyncCrypto.Keys, device: String, bundle: SyncBundle) {
        val body = SyncCrypto.seal(keys, device, bundle.toJson())
        send(Request.Builder().url("${base(relay)}/v1/${keys.mailbox}/$device").put(body.toRequestBody(OCTETS)), keys)
    }

    /** Takes this device's parcel off the relay (the others keep theirs). */
    suspend fun leave(relay: String, keys: SyncCrypto.Keys, device: String) {
        send(Request.Builder().url("${base(relay)}/v1/${keys.mailbox}/$device").delete(), keys)
    }

    /** One round: fetch the others, combine with [own], share the result. Returns the combined state. */
    suspend fun syncOnce(relay: String, keys: SyncCrypto.Keys, device: String, own: SyncBundle, current: String?, now: Long): SyncBundle {
        val merged = SyncBundle.merge(own, pull(relay, keys, device, current), now)
        push(relay, keys, device, merged)
        return merged
    }

    private suspend fun send(builder: Request.Builder, keys: SyncCrypto.Keys): String = withContext(Dispatchers.IO) {
        val request = builder.header("Authorization", "Bearer ${keys.auth}").header("Cache-Control", "no-store").build()
        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw SyncException("Couldn't reach the sync relay.")
        }
        response.use {
            when {
                it.code == 401 -> throw SyncException("This sync code is no longer valid on the relay.")
                it.code == 409 -> throw SyncException("Too many devices are linked to this code (12 at most).")
                !it.isSuccessful -> throw SyncException("The sync relay answered ${it.code}.")
            }
            it.body?.string().orEmpty()
        }
    }

    private fun base(relay: String) = relay.trimEnd('/')

    private companion object {
        val OCTETS = "application/octet-stream".toMediaType()
    }
}
