package com.logfriends.agent.transport

import com.logfriends.agent.event.AgentEvent
import com.logfriends.agent.event.HttpCapturedEvent
import com.logfriends.agent.event.JdbcCapturedEvent
import com.logfriends.agent.event.LogCapturedEvent
import com.logfriends.agent.event.LogEventCapturedEvent
import com.logfriends.agent.event.MethodTraceCapturedEvent

/**
 * Fast, conservative retained-heap estimate for an event accepted by the SDK
 * queue. This deliberately avoids JOL on the application thread.
 *
 * The constants target the supported 64-bit HotSpot JDK 21 baseline with
 * compressed references and 8-byte object alignment. String contents use the
 * UTF-16 upper bound (two bytes per code unit), so ASCII-heavy traffic is
 * intentionally overestimated rather than allowed to overrun the budget.
 */
internal object EventHeapEstimator {
    private const val STRING_SHALLOW_BYTES = 24L
    private const val BYTE_ARRAY_HEADER_BYTES = 16L
    private const val LOG_CAPTURED_EVENT_SHALLOW_BYTES = 40L
    private const val HTTP_CAPTURED_EVENT_SHALLOW_BYTES = 40L
    private const val JDBC_CAPTURED_EVENT_SHALLOW_BYTES = 40L
    private const val METHOD_TRACE_EVENT_SHALLOW_BYTES = 40L
    private const val LOG_EVENT_CAPTURED_EVENT_SHALLOW_BYTES = 24L
    private const val LINKED_BLOCKING_QUEUE_NODE_BYTES = 24L
    private const val LINKED_HASH_MAP_BASE_BYTES = 96L
    private const val LINKED_HASH_MAP_ENTRY_BYTES = 64L

    fun estimateQueuedEventBytes(event: AgentEvent): Long {
        val eventBytes = when (event) {
            is LogCapturedEvent -> LOG_CAPTURED_EVENT_SHALLOW_BYTES + strings(
                event.timestamp, event.level, event.loggerName, event.threadName,
                event.message, event.exception
            )
            is HttpCapturedEvent -> HTTP_CAPTURED_EVENT_SHALLOW_BYTES + strings(
                event.timestamp, event.method, event.uri
            )
            is JdbcCapturedEvent -> JDBC_CAPTURED_EVENT_SHALLOW_BYTES + strings(
                event.timestamp, event.sql, event.exception
            )
            is MethodTraceCapturedEvent -> METHOD_TRACE_EVENT_SHALLOW_BYTES + strings(
                event.timestamp, event.className, event.methodName, event.exception
            )
            is LogEventCapturedEvent -> LOG_EVENT_CAPTURED_EVENT_SHALLOW_BYTES +
                strings(event.timestamp, event.eventName) + estimateFields(event.fields)
        }

        // The node remains part of the conservative budget until a flushed
        // batch completes, even though drainTo removes it from the queue.
        return eventBytes + LINKED_BLOCKING_QUEUE_NODE_BYTES
    }

    private fun estimateFields(fields: Map<String, String>): Long {
        if (fields.isEmpty()) return LINKED_HASH_MAP_BASE_BYTES

        return LINKED_HASH_MAP_BASE_BYTES +
            fields.entries.sumOf { (name, value) ->
                LINKED_HASH_MAP_ENTRY_BYTES + estimateString(name) + estimateString(value)
            }
    }

    private fun strings(vararg values: String?): Long = values.sumOf { value ->
        value?.let(::estimateString) ?: 0L
    }

    private fun estimateString(value: String): Long {
        val valueArrayBytes = align8(BYTE_ARRAY_HEADER_BYTES + value.length.toLong() * 2L)
        return STRING_SHALLOW_BYTES + valueArrayBytes
    }

    private fun align8(value: Long): Long = (value + 7L) and 7L.inv()
}
