package io.github.tellyteam.telly.data

import io.github.tellyteam.telly.data.StallAdvice.Cause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StallAdviceTest {
    private val gb = 1024L * 1024 * 1024
    private val mb = 1024L * 1024

    @Test
    fun `the device comes first, then speed, then waiting`() {
        assertEquals(Cause.LOW_STORAGE, StallAdvice.cause(1_000_000, 5_000_000, lowMemory = true, freeBytes = 300 * mb, droppedFrames = 0))
        assertEquals(Cause.DEVICE_BUSY, StallAdvice.cause(20_000_000, 5_000_000, lowMemory = true, freeBytes = 2 * gb, droppedFrames = 0))
        assertEquals(Cause.DEVICE_BUSY, StallAdvice.cause(20_000_000, 5_000_000, lowMemory = false, freeBytes = 2 * gb, droppedFrames = 40))
        assertEquals(Cause.SLOW, StallAdvice.cause(5_500_000, 5_000_000, lowMemory = false, freeBytes = 2 * gb, droppedFrames = 0))
        assertEquals(Cause.WAITING, StallAdvice.cause(20_000_000, 5_000_000, lowMemory = false, freeBytes = 2 * gb, droppedFrames = 0))
    }

    @Test
    fun `unknown speed or bitrate is waiting, not slow`() {
        assertEquals(Cause.WAITING, StallAdvice.cause(null, 5_000_000, false, null, 0))
        assertEquals(Cause.WAITING, StallAdvice.cause(1_000_000, null, false, null, 0))
        assertEquals(Cause.WAITING, StallAdvice.cause(1_000_000, -1, false, null, 0))
    }

    @Test
    fun `only a live channel that was playing is reconnected, and not for ever`() {
        assertTrue(StallAdvice.reconnect(Cause.WAITING, live = true, played = true, stalledMs = 15_000, reconnects = 0))
        assertTrue(StallAdvice.reconnect(Cause.SLOW, live = true, played = true, stalledMs = 20_000, reconnects = 2))
        assertFalse("too soon", StallAdvice.reconnect(Cause.WAITING, true, true, 14_000, 0))
        assertFalse("still starting: the start has its own wait", StallAdvice.reconnect(Cause.WAITING, true, false, 30_000, 0))
        assertFalse("films and episodes", StallAdvice.reconnect(Cause.WAITING, false, true, 30_000, 0))
        assertFalse("tried enough", StallAdvice.reconnect(Cause.WAITING, true, true, 30_000, 3))
        assertFalse("reconnecting doesn't help the device", StallAdvice.reconnect(Cause.DEVICE_BUSY, true, true, 30_000, 0))
        assertFalse(StallAdvice.reconnect(Cause.LOW_STORAGE, true, true, 30_000, 0))
    }

    @Test
    fun `notes and the storage reminder read naturally`() {
        assertEquals(
            "Your Fire TV is nearly full (320 MB free), which slows it down. Remove apps you don't use, or clear other apps' caches in Settings.",
            StallAdvice.note(Cause.LOW_STORAGE, 320 * mb, fireTv = true),
        )
        assertEquals(
            "Your Fire TV is struggling to keep up. Restarting it usually helps: hold Select and Play for 5 seconds.",
            StallAdvice.note(Cause.DEVICE_BUSY, null, fireTv = true),
        )
        assertEquals("This device is struggling to keep up. Restarting it usually helps.", StallAdvice.note(Cause.DEVICE_BUSY, null, fireTv = false))
        assertEquals("1.4 GB", StallAdvice.size(1_434 * mb))
        assertEquals("1023 MB", StallAdvice.size(1_023 * mb))
        assertEquals("Your Fire TV is nearly full (499 MB free), which can make streams stutter.", StallAdvice.storageReminder(499 * mb, true))
        assertNull(StallAdvice.storageReminder(600 * mb, true))
        assertNull(StallAdvice.storageReminder(null, true))
    }
}
