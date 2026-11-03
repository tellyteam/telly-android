package io.github.tellyteam.telly.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Home's reminder of telly Plus: a few days after the first offer, until closed or bought. */
class PlusReminderTest {
    private val day = 86_400_000L
    private val offered = 1_800_000_000_000L

    @Test
    fun waitsAFewDaysAfterTheOffer() {
        assertFalse(plusReminderDue(offered + 2 * day, offered, closed = false, syncShown = true, hasPlus = false))
        assertTrue(plusReminderDue(offered + 3 * day, offered, closed = false, syncShown = true, hasPlus = false))
    }

    @Test
    fun neverWithoutAnOfferOnceClosedOrWithPlus() {
        assertFalse("not offered yet", plusReminderDue(offered, 0, closed = false, syncShown = true, hasPlus = false))
        assertFalse("closed for good", plusReminderDue(offered + 9 * day, offered, closed = true, syncShown = true, hasPlus = false))
        assertFalse("already has it", plusReminderDue(offered + 9 * day, offered, closed = false, syncShown = true, hasPlus = true))
        assertFalse("Sync still hidden", plusReminderDue(offered + 9 * day, offered, closed = false, syncShown = false, hasPlus = false))
    }
}
