package io.github.tellyteam.telly.data.sync

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Keys and sealing for sync, exactly as telly-sync's PROTOCOL.md describes. */
object SyncCrypto {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val SALT = "telly-sync v1"
    private const val VERSION: Byte = 1
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    class Keys(
        val mailbox: String,
        val key: ByteArray,
        val auth: String,
        /** Keys provider tags (see [providerTag]). */
        val provider: ByteArray,
    )

    /** A new link code: 16 characters, 80 random bits. */
    fun newCode(bytes: ByteArray = ByteArray(10).also(random::nextBytes)): String {
        val out = StringBuilder()
        var value = 0
        var bits = 0
        for (b in bytes) {
            value = (value shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                out.append(ALPHABET[(value ushr (bits - 5)) and 31])
                bits -= 5
            }
        }
        return out.toString()
    }

    /** Uppercase, no spaces or dashes, O→0, I/L→1; null unless it's 16 valid characters. */
    fun normaliseCode(input: String): String? {
        val code = input.uppercase().filterNot { it.isWhitespace() || it == '-' }
            .replace('O', '0').replace('I', '1').replace('L', '1')
        return code.takeIf { it.length == 16 && it.all { c -> c in ALPHABET } }
    }

    fun formatCode(code: String): String = code.chunked(4).joinToString("-")

    fun newDeviceId(): String = ByteArray(8).also(random::nextBytes).toHex()

    fun deriveKeys(code: String): Keys {
        val ikm = code.toByteArray()
        val prk = hmac(SALT.toByteArray(), ikm)
        // One HKDF-Expand block is exactly the 32 bytes needed.
        fun derive(info: String) = hmac(prk, info.toByteArray() + 1)
        return Keys(
            mailbox = derive("mailbox").copyOf(16).toHex(),
            key = derive("key"),
            auth = Base64Text.encode(derive("auth"), url = true),
            provider = derive("provider"),
        )
    }

    /** A provider's tag: meaningless without the link code; with it, only "same provider or not". */
    fun providerTag(keys: Keys, identity: String): String = hmac(keys.provider, identity.toByteArray()).copyOf(8).toHex()

    fun seal(keys: Keys, device: String, plaintext: String, nonce: ByteArray = ByteArray(NONCE_BYTES).also(random::nextBytes)): ByteArray =
        sealBox(keys.key, aad(keys, device), plaintext, nonce)

    /** The plaintext, or null if the parcel isn't ours, was altered, or is from a newer version. */
    fun open(keys: Keys, device: String, box: ByteArray): String? = openBox(keys.key, aad(keys, device), box)

    private fun sealBox(key: ByteArray, aad: ByteArray, plaintext: String, nonce: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(aad)
        }
        return byteArrayOf(VERSION) + nonce + cipher.doFinal(plaintext.toByteArray())
    }

    private fun openBox(key: ByteArray, aad: ByteArray, box: ByteArray): String? {
        if (box.size < 1 + NONCE_BYTES + 16 || box[0] != VERSION) return null
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, box, 1, NONCE_BYTES))
                updateAAD(aad)
            }
            String(cipher.doFinal(box, 1 + NONCE_BYTES, box.size - 1 - NONCE_BYTES))
        }.getOrNull()
    }

    private fun aad(keys: Keys, device: String) = "$SALT|${keys.mailbox}|$device".toByteArray()

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    internal fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
