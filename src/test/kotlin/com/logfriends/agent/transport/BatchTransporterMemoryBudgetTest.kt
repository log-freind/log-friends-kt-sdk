package com.logfriends.agent.transport

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BatchTransporterMemoryBudgetTest {

    @Test
    fun `drops an event when its estimated retained heap exceeds the budget`() {
        val transporter = BatchTransporter(
            batchSize = 10,
            intervalMs = 60_000,
            ingestUrl = "http://console/ingest",
            workerId = "worker-memory-budget",
            queueCapacity = 10,
            queueMemoryBudgetBytes = 1
        )

        try {
            transporter.enqueueLog("INFO", "example", "main", "hello", null)

            val stats = transporter.snapshot()
            assertEquals(1, stats.captured)
            assertEquals(1, stats.dropped)
            assertEquals(0, stats.queued)
            assertEquals(0, stats.estimatedRetainedHeapBytes)
            assertEquals(1, stats.queueMemoryBudgetBytes)
        } finally {
            transporter.shutdown()
        }
    }

    @Test
    fun `releases the retained heap reservation after a batch is delivered`() {
        val transporter = BatchTransporter(
            batchSize = 10,
            intervalMs = 60_000,
            ingestUrl = "http://console/ingest",
            workerId = "worker-memory-budget",
            queueCapacity = 10,
            queueMemoryBudgetBytes = 1_000_000
        ) { }

        try {
            transporter.enqueueLog("INFO", "example", "main", "hello", null)
            assertTrue(transporter.snapshot().estimatedRetainedHeapBytes > 0)

            transporter.flush()

            val stats = transporter.snapshot()
            assertEquals(1, stats.sent)
            assertEquals(0, stats.queued)
            assertEquals(0, stats.inFlight)
            assertEquals(0, stats.estimatedRetainedHeapBytes)
        } finally {
            transporter.shutdown()
        }
    }
}
