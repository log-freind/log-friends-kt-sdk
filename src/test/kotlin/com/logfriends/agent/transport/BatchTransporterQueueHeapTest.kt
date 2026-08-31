package com.logfriends.agent.transport

import com.logfriends.agent.event.AgentEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.openjdk.jol.info.GraphLayout
import java.util.concurrent.BlockingQueue

class BatchTransporterQueueHeapTest {

    @Test
    fun `measure queued event heap footprint`() {
        assumeTrue(
            System.getProperty("logfriends.queue.heap") == "true",
            "Set -Dlogfriends.queue.heap=true to measure the queue heap footprint"
        )

        val eventCount = System.getProperty("logfriends.queue.heap.events", "10000")
            .toIntOrNull()
            ?.takeIf { it > 0 }
            ?: 10_000
        val transporter = BatchTransporter(
            batchSize = eventCount + 1,
            intervalMs = 60_000,
            ingestUrl = "http://console/ingest",
            workerId = "worker-queue-heap",
            queueCapacity = eventCount + 1
        ) {
            // This diagnostic measures queued objects without HTTP delivery.
        }

        try {
            val queue = transporter.eventQueue()
            val emptyQueueBytes = GraphLayout.parseInstance(queue).totalSize()

            repeat(eventCount) { index ->
                transporter.enqueueLogEvent(
                    eventName = "cartItemAdded",
                    paramNames = PARAM_NAMES,
                    args = arrayOf(
                        "cart-$index",
                        "user-$index",
                        "product-${index % 100}",
                        1,
                        "shop"
                    )
                )
            }

            val populatedQueueBytes = GraphLayout.parseInstance(queue).totalSize()
            val queuedHeapBytes = populatedQueueBytes - emptyQueueBytes
            val averageBytes = queuedHeapBytes.toDouble() / eventCount

            assertEquals(eventCount, queue.size)
            assertTrue(queuedHeapBytes > 0)
            println(
                "[Log Friends Queue Heap] events=$eventCount, " +
                    "emptyQueueBytes=$emptyQueueBytes, " +
                    "populatedQueueBytes=$populatedQueueBytes, " +
                    "queueHeapBytes=$queuedHeapBytes, " +
                    "avgBytesPerEvent=${"%.2f".format(averageBytes)}"
            )
        } finally {
            transporter.shutdown()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun BatchTransporter.eventQueue(): BlockingQueue<AgentEvent> {
        val field = BatchTransporter::class.java.getDeclaredField("queue")
        field.isAccessible = true
        return field.get(this) as BlockingQueue<AgentEvent>
    }

    companion object {
        private val PARAM_NAMES = arrayOf(
            "cartId",
            "userId",
            "productId",
            "quantity",
            "sourcePage"
        )
    }
}
