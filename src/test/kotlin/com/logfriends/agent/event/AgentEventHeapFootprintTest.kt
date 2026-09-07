package com.logfriends.agent.event

import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.openjdk.jol.info.GraphLayout
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.abs

/**
 * JOL diagnostic example, not a normal unit test.
 *
 * It separates an application-owned log String from the extra object graph the
 * SDK retains when it wraps that String in an [AgentEvent] and puts it in its
 * in-memory queue. Run with -Dlogfriends.queue.heap=true.
 */
class AgentEventHeapFootprintTest {

    @Test
    fun `measures source log string and SDK retained event graph separately`() {
        assumeTrue(
            System.getProperty("logfriends.queue.heap") == "true",
            "Set -Dlogfriends.queue.heap=true to run JOL heap diagnostics"
        )

        // This String represents data already created by the application/logger.
        val level = "ERROR"
        val loggerName = "com.example.TripService"
        val threadName = "http-nio-8080-exec-1"
        val sourceMessage = "서울 여행 일정 생성 실패: ".repeat(512)

        // These values already exist before the SDK captures the log. Treat all
        // of them as application/logger-owned input, not SDK allocation.
        val sourceInputBytes = GraphLayout.parseInstance(
            level,
            loggerName,
            threadName,
            sourceMessage
        ).totalSize()

        val captured = AgentEventFactory.log(
            level = level,
            loggerName = loggerName,
            threadName = threadName,
            message = sourceMessage,
            exception = null
        ) as LogCapturedEvent

        // Capturing does not duplicate the source message in this event type.
        assertSame(sourceMessage, captured.message)

        val capturedEventBytes = GraphLayout.parseInstance(captured).totalSize()
        val sdkCreatedRetainedBytes = capturedEventBytes - sourceInputBytes
        val estimatedEventBytes = estimateJdk21CompressedOopsEventBytes(captured)
        val eventEstimateDifference = capturedEventBytes - estimatedEventBytes
        val parseInstanceAllocatedBytesPerCall = measureParseInstanceAllocation(captured)

        val queue = LinkedBlockingQueue<AgentEvent>()
        val emptyQueueBytes = GraphLayout.parseInstance(queue).totalSize()
        queue.offer(captured)
        val queuedGraphBytes = GraphLayout.parseInstance(queue).totalSize()
        val queueNodeBytes = queuedGraphBytes - emptyQueueBytes - capturedEventBytes
        val estimatedQueueNodeBytes = LINKED_BLOCKING_QUEUE_NODE_BYTES
        val queueNodeEstimateDifference = queueNodeBytes - estimatedQueueNodeBytes

        assertTrue(sdkCreatedRetainedBytes > 0)
        assertTrue(queueNodeBytes > 0)
        assertTrue(abs(eventEstimateDifference) < MAX_ACCEPTABLE_ESTIMATE_DIFFERENCE_BYTES)
        assertTrue(abs(queueNodeEstimateDifference) < MAX_ACCEPTABLE_ESTIMATE_DIFFERENCE_BYTES)
        println(
            "[Log Friends Event Heap] sourceInputBytes=$sourceInputBytes, " +
                "capturedEventBytes=$capturedEventBytes, " +
            "estimatedEventBytes=$estimatedEventBytes, " +
            "eventEstimateDifference=$eventEstimateDifference, " +
            "parseInstanceAllocatedBytesPerCall=$parseInstanceAllocatedBytesPerCall, " +
                "sdkCreatedRetainedBytes=$sdkCreatedRetainedBytes, " +
                "emptyQueueBytes=$emptyQueueBytes, " +
                "queueNodeBytes=$queueNodeBytes, " +
                "estimatedQueueNodeBytes=$estimatedQueueNodeBytes, " +
                "queueNodeEstimateDifference=$queueNodeEstimateDifference"
        )
    }

    /**
     * A deliberately explicit estimate for this test JVM only:
     * 64-bit HotSpot JDK 21, compressed object pointers, 8-byte alignment and
     * Compact Strings. Production code must not assume these constants.
     */
    private fun estimateJdk21CompressedOopsEventBytes(event: LogCapturedEvent): Long {
        return LOG_CAPTURED_EVENT_SHALLOW_BYTES + listOfNotNull(
            event.timestamp,
            event.level,
            event.loggerName,
            event.threadName,
            event.message,
            event.exception
        ).sumOf(::estimateCompactStringBytes)
    }

    private fun estimateCompactStringBytes(value: String): Long {
        val bytesPerCodeUnit = if (value.all { it.code <= 0xFF }) 1 else 2
        val valueArrayBytes = align8(BYTE_ARRAY_HEADER_BYTES + value.length.toLong() * bytesPerCodeUnit)
        return STRING_SHALLOW_BYTES + valueArrayBytes
    }

    private fun align8(value: Long): Long = (value + 7L) and 7L.inv()

    /**
     * Measures allocation *during* a warmed-up JOL traversal, not the retained
     * size of [event]. The returned GraphLayout and traversal bookkeeping are
     * temporary garbage after each call; this is why it must never be used in
     * the SDK hot path.
     */
    private fun measureParseInstanceAllocation(event: LogCapturedEvent): Long {
        val threadMxBean = ManagementFactory.getThreadMXBean() as? ThreadMXBean
            ?: return -1L
        if (!threadMxBean.isThreadAllocatedMemorySupported) return -1L
        if (!threadMxBean.isThreadAllocatedMemoryEnabled) {
            threadMxBean.isThreadAllocatedMemoryEnabled = true
        }

        repeat(MEASUREMENT_WARMUP_CALLS) {
            GraphLayout.parseInstance(event).totalSize()
        }

        val threadId = Thread.currentThread().id
        val before = threadMxBean.getThreadAllocatedBytes(threadId)
        var totalSize = 0L
        repeat(MEASUREMENT_CALLS) {
            totalSize += GraphLayout.parseInstance(event).totalSize()
        }
        val allocated = threadMxBean.getThreadAllocatedBytes(threadId) - before
        check(totalSize > 0L)
        return allocated / MEASUREMENT_CALLS
    }

    companion object {
        private const val STRING_SHALLOW_BYTES = 24L
        private const val BYTE_ARRAY_HEADER_BYTES = 16L
        private const val LOG_CAPTURED_EVENT_SHALLOW_BYTES = 40L
        private const val LINKED_BLOCKING_QUEUE_NODE_BYTES = 24L
        private const val MAX_ACCEPTABLE_ESTIMATE_DIFFERENCE_BYTES = 128L
        private const val MEASUREMENT_WARMUP_CALLS = 10
        private const val MEASUREMENT_CALLS = 20
    }
}
