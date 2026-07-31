package com.logfriends.agent.transport

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TransportStatsTest {

    @Test
    fun `accounted events include sent dropped queued and in flight events`() {
        val stats = TransportStats(
            captured = 10,
            sent = 4,
            dropped = 2,
            queued = 3,
            inFlight = 1
        )

        assertEquals(10, stats.accounted)
    }
}
