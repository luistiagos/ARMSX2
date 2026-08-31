package com.armsx2

import org.junit.Assert.assertEquals
import org.junit.Test

class DiscordSessionClockTest {
    private var now = 1_000L
    private val clock = DiscordSessionClock { now }

    @Test
    fun idleHasNoSessionTimestamp() {
        assertEquals(0L, clock.startedAtFor(null))
    }

    @Test
    fun repeatedUpdatesKeepTheOriginalTimestamp() {
        assertEquals(1_000L, clock.startedAtFor("SLUS-00001\u001fGame"))

        now = 9_000L

        assertEquals(1_000L, clock.startedAtFor("SLUS-00001\u001fGame"))
    }

    @Test
    fun changingGamesStartsANewSession() {
        assertEquals(1_000L, clock.startedAtFor("SLUS-00001\u001fFirst"))

        now = 2_000L

        assertEquals(2_000L, clock.startedAtFor("SLUS-00002\u001fSecond"))
    }

    @Test
    fun returningFromTheLibraryStartsANewSession() {
        assertEquals(1_000L, clock.startedAtFor("SLUS-00001\u001fGame"))
        assertEquals(0L, clock.startedAtFor(null))

        now = 3_000L

        assertEquals(3_000L, clock.startedAtFor("SLUS-00001\u001fGame"))
    }
}
