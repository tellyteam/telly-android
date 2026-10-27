package io.github.tellyteam.telly.data.store

import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.Writer
import java.security.DigestOutputStream
import java.security.MessageDigest

/**
 * Catalogues on disk: the big lists as telly keeps them ([Compact]), the smaller provider responses raw, so catalogues show instantly on the next launch while a fresh
 * copy downloads. Files are grouped per provider [scope]; a download only replaces the cached copy
 * once it has parsed successfully, so a bad response never wipes a good list.
 */
class CatalogCache(private val root: File) {

    /** Null when nothing is cached or the cached copy is unreadable (it is then discarded). */
    fun <T> read(scope: String, name: String, parse: (InputStream) -> T): T? {
        val file = file(scope, name)
        if (!file.isFile) return null
        return try {
            file.inputStream().buffered().use(parse)
        } catch (e: Exception) {
            file.delete()
            null
        }
    }

    fun <T> replace(scope: String, name: String, write: (OutputStream) -> Unit, parse: (InputStream) -> T): T {
        val target = file(scope, name)
        target.parentFile!!.mkdirs()
        val tmp = File(target.parentFile, "$name.tmp")
        try {
            tmp.outputStream().buffered().use(write)
            val result = tmp.inputStream().buffered().use(parse)
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
            }
            return result
        } finally {
            tmp.delete()
        }
    }

    /** A saved catalogue read as text, or null (an unreadable one is discarded). */
    fun <T> readCompact(scope: String, name: String, parse: (BufferedReader) -> T?): T? = read(scope, name) { parse(it.bufferedReader()) }

    /**
     * Saves what [write] produces as [name], with a fingerprint of it. When [known] (that list is
     * already showing) and the fingerprint matches the one saved, nothing is saved and it returns
     * false: the list hasn't changed. Comparing fingerprints means the saved copy is never read back.
     */
    fun keep(scope: String, name: String, known: Boolean, write: (Writer) -> Unit): Boolean {
        val target = file(scope, name)
        target.parentFile!!.mkdirs()
        val tmp = File(target.parentFile, "$name.tmp")
        val fingerprintFile = File(target.parentFile, "$name.fingerprint")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            DigestOutputStream(tmp.outputStream().buffered(), digest).bufferedWriter().use(write)
            val fingerprint = digest.digest().take(16).joinToString("") { "%02x".format(it) }
            if (known && target.isFile && fingerprintFile.takeIf { it.isFile }?.readText() == fingerprint) return false
            if (!tmp.renameTo(target)) tmp.copyTo(target, overwrite = true)
            fingerprintFile.writeText(fingerprint)
            return true
        } finally {
            tmp.delete()
        }
    }

    fun remove(scope: String, name: String) {
        file(scope, name).delete()
    }

    /** Deletes every saved catalogue, for every provider. */
    fun clearAll() {
        root.deleteRecursively()
    }

    /** Deletes one provider's saved catalogues. */
    fun clear(scope: String) {
        File(root, scope).deleteRecursively()
    }

    fun readText(scope: String, name: String): String? = file(scope, name).takeIf { it.isFile }?.readText()

    fun writeText(scope: String, name: String, text: String) {
        file(scope, name).apply { parentFile!!.mkdirs() }.writeText(text)
    }

    private fun file(scope: String, name: String) = File(File(root, scope), name)

    companion object {
        /** A provider's folder name; derived from server and username only, never the password. */
        fun scope(vararg parts: String): String =
            MessageDigest.getInstance("SHA-256").digest(parts.joinToString("\n").toByteArray())
                .take(12).joinToString("") { "%02x".format(it) }
    }
}
