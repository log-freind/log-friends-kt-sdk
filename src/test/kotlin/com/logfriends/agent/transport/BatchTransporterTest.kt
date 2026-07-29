package com.logfriends.agent.transport

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BatchTransporterTest {

    @Test
    fun `drops failed batch without blocking the target application`() {
        var attempts = 0
        val sentPayloads = mutableListOf<String>()
        val transporter = BatchTransporter(
            batchSize = 10,
            intervalMs = 60_000,
            ingestUrl = "http://console/ingest",
            workerId = "worker-1",
            queueCapacity = 10
        ) { json ->
            attempts++
            if (attempts == 1) {
                throw RuntimeException("console unavailable")
            }
            sentPayloads += json
        }

        try {
            transporter.enqueueLogEvent(
                eventName = "cartItemAdded",
                paramNames = arrayOf("cartId"),
                args = arrayOf("cart-1")
            )

            transporter.flush()

            assertEquals("sent=0, dropped=1, queued=0", transporter.stats)

            transporter.flush()

            assertEquals("sent=0, dropped=1, queued=0", transporter.stats)
            assertEquals(1, attempts)
            assertEquals(0, sentPayloads.size)
        } finally {
            transporter.shutdown()
        }
    }
}
