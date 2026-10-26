package io.github.tellyteam.telly.data.sync

import android.content.Context
import io.github.tellyteam.telly.data.store.KeystoreCipher
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The link code (the household's only secret), this device's id and the relay. Encrypted on the device. */
@Serializable
data class SyncConfig(val code: String, val device: String, val relay: String)

class SyncSettings(context: Context) {
    private val prefs = context.getSharedPreferences("sync", Context.MODE_PRIVATE)
    private val cipher = KeystoreCipher("telly_sync")

    init {
        // Sharing logins between devices was removed: forget the setting.
        if (prefs.contains(SHARE_LOGINS)) prefs.edit().remove(SHARE_LOGINS).apply()
    }

    fun load(): SyncConfig? = prefs.getString(KEY, null)?.let { stored ->
        runCatching { json.decodeFromString(SyncConfig.serializer(), cipher.decrypt(stored)) }.getOrNull()
    }?.takeIf { SyncCrypto.normaliseCode(it.code) != null }?.copy(relay = RELAY)

    fun save(config: SyncConfig) {
        prefs.edit().putString(KEY, cipher.encrypt(json.encodeToString(SyncConfig.serializer(), config))).apply()
    }

    /** Sync is hidden until unlocked (seven presses on the version in Settings → About). */
    var unlocked: Boolean
        get() = prefs.getBoolean(UNLOCKED, false)
        set(value) = prefs.edit().putBoolean(UNLOCKED, value).apply()

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    companion object {
        /** telly's relay: sync always uses it (a link saved with another one is moved to it). */
        const val RELAY = "https://telly-sync.tellyteam.workers.dev"
        private const val KEY = "config"
        private const val UNLOCKED = "unlocked"
        private const val SHARE_LOGINS = "shareLogins"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
