package io.github.tellyteam.telly.data.update

import android.content.Context
import io.github.tellyteam.telly.data.net.fetch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException

/** A published telly release. */
data class Release(val version: String, val apkUrl: String, val notes: String?)

/** Reading GitHub's "latest release" answer and comparing versions; no Android code, so it's unit-tested. */
object ReleaseParser {
    const val APK_NAME = "telly.apk"

    fun parse(root: JsonElement): Release? {
        val o = root as? JsonObject ?: return null
        if ((o["draft"] as? JsonPrimitive)?.contentOrNull == "true" || (o["prerelease"] as? JsonPrimitive)?.contentOrNull == "true") return null
        val version = (o["tag_name"] as? JsonPrimitive)?.contentOrNull?.removePrefix("v")?.trim()?.ifBlank { null } ?: return null
        val apk = (o["assets"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .firstOrNull { (it["name"] as? JsonPrimitive)?.contentOrNull == APK_NAME }
            ?.let { (it["browser_download_url"] as? JsonPrimitive)?.contentOrNull } ?: return null
        return Release(version, apk, (o["body"] as? JsonPrimitive)?.contentOrNull?.let(::cleanNotes))
    }

    /**
     * Release notes as shown in the app: never anyone's account name. Drops GitHub's generated
     * credits ("by @someone in …", "New Contributors", "Full Changelog") and any other @mention.
     */
    fun cleanNotes(body: String): String? {
        var skipping = false
        val lines = body.lines().mapNotNull { raw ->
            val line = raw.trim()
            when {
                line.startsWith("## New Contributors", ignoreCase = true) -> { skipping = true; null }
                line.startsWith("#") -> { skipping = false; null }
                skipping -> null
                line.startsWith("**Full Changelog**") -> null
                line.contains("made their first contribution") -> null
                else -> line
                    .replace(Regex("""\s+by @[\w-]+(\s+in\s+\S+)?"""), "")
                    .replace(Regex("""@[\w-]+"""), "")
                    .replace(Regex(""" {2,}"""), " ")
                    .trimEnd()
            }
        }
        return lines.joinToString("\n").trim().replace(Regex("""\n{3,}"""), "\n\n").ifBlank { null }
    }

    /** True when [candidate] (e.g. "1.2.0") is a higher version than [current] ("1.1.9"). */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun parts(v: String) = v.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
}

/**
 * Asks GitHub for the latest telly release (at most daily unless the user asks) and downloads it.
 * Installing is left to Android's own installer, which asks the user and only accepts an update
 * signed with the same key as the installed app.
 */
class UpdateRepository(private val context: Context, private val http: OkHttpClient) {
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)

    val lastCheckedMs: Long get() = prefs.getLong(KEY_LAST_CHECK, 0)

    fun dueForCheck(nowMs: Long = System.currentTimeMillis()): Boolean = UpdateRules.due(lastCheckedMs, nowMs)

    /**
     * Whether a newly found [version] should be announced: once per version, so the daily check
     * doesn't say the same thing every day. Settings keeps showing it either way.
     */
    fun announce(version: String): Boolean {
        if (prefs.getString(KEY_ANNOUNCED, null) == version) return false
        prefs.edit().putString(KEY_ANNOUNCED, version).apply()
        return true
    }

    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        val release = http.fetch(LATEST_URL.toHttpUrl()).use { r -> ReleaseParser.parse(Json.parseToJsonElement(r.body!!.string())) }
        prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
        release
    }

    /** Downloads the APK to the app's cache (shared with the installer through a FileProvider). */
    suspend fun download(release: Release, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // keep only the one being installed
        val target = File(dir, "telly-${release.version}.apk")
        http.fetch(release.apkUrl.toHttpUrl()).use { r ->
            val body = r.body ?: throw IOException("Empty download")
            val total = body.contentLength().takeIf { it > 0 }
            body.byteStream().use { input ->
                target.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        if (total != null) onProgress(done.toFloat() / total)
                    }
                }
            }
        }
        target
    }

    private companion object {
        const val LATEST_URL = "https://api.github.com/repos/tellyteam/telly-android/releases/latest"
        const val KEY_LAST_CHECK = "lastCheck"
        const val KEY_ANNOUNCED = "announced"
    }
}

/** When to look for a new release without being asked; no Android code, so it's unit-tested. */
object UpdateRules {
    const val DAY_MS = 24L * 60 * 60 * 1000

    /** At most daily: a TV can keep telly running for days, so this is checked each time it comes to the front. */
    fun due(lastCheckedMs: Long, nowMs: Long): Boolean = nowMs - lastCheckedMs >= DAY_MS
}
