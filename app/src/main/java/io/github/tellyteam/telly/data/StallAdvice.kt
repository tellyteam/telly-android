package io.github.tellyteam.telly.data

/**
 * Why a stream is buffering, and what helps: in place of "clear the cache", which on a Fire TV only
 * helps by accident (it restarts the stream, or frees space other apps used). Unit-tested.
 *
 * - The device is nearly full, or short of memory, or dropping frames: it's the device. Freeing
 *   space or restarting it helps; reconnecting doesn't.
 * - The stream arrives slower than it plays: the connection or the provider is slow. Reconnecting
 *   can land on a quieter server.
 * - Neither: waiting on the provider. A live channel that had been playing is reconnected after a
 *   while, since a stalled stream rarely recovers on the same connection.
 */
object StallAdvice {
    /** Below this a Fire TV Stick slows down: its flash storage and the system both struggle. */
    const val LOW_STORAGE_BYTES = 500L * 1024 * 1024
    /** Frames dropped in the last half minute that mean the device can't keep up. */
    const val DEVICE_DROPPED_FRAMES = 30
    /** How long a stall lasts before it's explained on screen. */
    const val NOTE_AFTER_MS = 3_000L
    /** How long a live channel that was playing stays stalled before telly reconnects it. */
    const val RECONNECT_AFTER_MS = 15_000L
    /** Reconnections a channel gets in a row; past these, more won't help. */
    const val MAX_RECONNECTS = 3

    enum class Cause { LOW_STORAGE, DEVICE_BUSY, SLOW, WAITING }

    /**
     * [bandwidthBps]: the player's estimate of the download speed; [streamBps]: what the stream
     * needs. Either may be unknown (null). [freeBytes]: free storage on the device, if known.
     */
    fun cause(bandwidthBps: Long?, streamBps: Long?, lowMemory: Boolean, freeBytes: Long?, droppedFrames: Int): Cause = when {
        freeBytes != null && freeBytes < LOW_STORAGE_BYTES -> Cause.LOW_STORAGE
        lowMemory || droppedFrames >= DEVICE_DROPPED_FRAMES -> Cause.DEVICE_BUSY
        // A little headroom: a stream arriving barely faster than it plays still stalls.
        bandwidthBps != null && streamBps != null && bandwidthBps > 0 && streamBps > 0 && bandwidthBps < streamBps * 6 / 5 -> Cause.SLOW
        else -> Cause.WAITING
    }

    /** The line under the spinner. [fireTv] names the device and says how to restart it. */
    fun note(cause: Cause, freeBytes: Long?, fireTv: Boolean): String {
        val device = if (fireTv) "Your Fire TV" else "This device"
        return when (cause) {
            Cause.LOW_STORAGE -> "$device is nearly full (${size(freeBytes ?: 0)} free), which slows it down. " +
                "Remove apps you don't use, or clear other apps' caches in Settings."
            Cause.DEVICE_BUSY -> "$device is struggling to keep up. " +
                if (fireTv) "Restarting it usually helps: hold Select and Play for 5 seconds." else "Restarting it usually helps."
            Cause.SLOW -> "The stream is arriving slower than it plays: your connection or your provider is slow right now."
            Cause.WAITING -> "Waiting for your provider…"
        }
    }

    /** Whether to reconnect a stalled stream now: a live channel that had been playing, stalled long enough, for a reason reconnecting can fix. */
    fun reconnect(cause: Cause, live: Boolean, played: Boolean, stalledMs: Long, reconnects: Int): Boolean =
        live && played && stalledMs >= RECONNECT_AFTER_MS && reconnects < MAX_RECONNECTS &&
            (cause == Cause.SLOW || cause == Cause.WAITING)

    /** "320 MB", "1.4 GB". */
    fun size(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024)
        return if (mb < 1024) "${mb.toLong()} MB" else "%.1f GB".format(java.util.Locale.UK, mb / 1024)
    }

    /** The once-a-day reminder when the device is nearly full, or null when there's room. */
    fun storageReminder(freeBytes: Long?, fireTv: Boolean): String? =
        if (freeBytes != null && freeBytes < LOW_STORAGE_BYTES) {
            "${if (fireTv) "Your Fire TV" else "This device"} is nearly full (${size(freeBytes)} free), which can make streams stutter."
        } else {
            null
        }
}
