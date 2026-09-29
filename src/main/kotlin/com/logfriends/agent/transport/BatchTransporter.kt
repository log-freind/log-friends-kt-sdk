package com.logfriends.agent.transport

import com.logfriends.agent.bootstrap.LogFriendsRuntime
import com.logfriends.agent.event.AgentEvent
import com.logfriends.agent.event.AgentEventFactory
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class BatchTransporter internal constructor(
    private val batchSize: Int,
    private val intervalMs: Long,
    private val ingestUrl: String = LogFriendsRuntime.ingestUrl ?: "",
    private val workerId: String = LogFriendsRuntime.workerId ?: "",
    queueCapacity: Int = BatchTransportConfig.queueCapacity(),
    private val queueMemoryBudgetBytes: Long = BatchTransportConfig.queueMemoryBudgetBytes(),
    private val postBatch: ((String) -> Unit)? = null
) {

    private val queue: BlockingQueue<AgentEvent>
    private val scheduler: ScheduledExecutorService
    private val running = AtomicBoolean(true)
    private val capturedCount = AtomicLong(0)
    private val sentCount = AtomicLong(0)
    private val dropCount = AtomicLong(0)
    private val inFlightCount = AtomicLong(0)
    private val retainedEstimatedHeapBytes = AtomicLong(0)
    private val lastDropWarnAt = AtomicLong(0)
    private val ingestClient: IngestHttpClient by lazy { IngestHttpClient(ingestUrl) }

    init {
        queue = LinkedBlockingQueue(queueCapacity)

        scheduler = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "log-friends-batch-flush").apply { isDaemon = true }
        }

        scheduler.scheduleAtFixedRate(
            this::flush,
            intervalMs, intervalMs, TimeUnit.MILLISECONDS
        )
    }

    fun enqueueLog(
        level: String, loggerName: String, threadName: String,
        message: String, exception: String?
    ) {
        enqueue(AgentEventFactory.log(level, loggerName, threadName, message, exception))
    }

    fun enqueueHttp(
        method: String, uri: String, statusCode: Int,
        durationMs: Long
    ) {
        enqueue(AgentEventFactory.http(method, uri, statusCode, durationMs))
    }

    fun enqueueJdbc(
        sql: String, durationMs: Long, rowCount: Int,
        exception: String?
    ) {
        enqueue(AgentEventFactory.jdbc(sql, durationMs, rowCount, exception))
    }

    fun enqueueMethodTrace(
        className: String, methodName: String, durationMs: Long,
        exception: String?
    ) {
        enqueue(AgentEventFactory.methodTrace(className, methodName, durationMs, exception))
    }

    fun enqueueLogEvent(
        eventName: String,
        paramNames: Array<String>,
        args: Array<Any?>,
        maskedParams: BooleanArray = BooleanArray(paramNames.size)
    ) {
        enqueue(AgentEventFactory.logEvent(eventName, paramNames, args, maskedParams))
    }

    fun shutdown() {
        running.set(false)
        flush()
        scheduler.shutdown()
    }

    val stats: String
        get() = snapshot().toLogMessage()

    fun snapshot(): TransportStats = TransportStats(
        captured = capturedCount.get(),
        sent = sentCount.get(),
        dropped = dropCount.get(),
        queued = queue.size.toLong(),
        inFlight = inFlightCount.get(),
        estimatedRetainedHeapBytes = retainedEstimatedHeapBytes.get(),
        queueMemoryBudgetBytes = queueMemoryBudgetBytes
    )

    private fun enqueue(event: AgentEvent) {
        capturedCount.incrementAndGet()

        if (workerId.isBlank() || ingestUrl.isBlank()) {
            dropCount.incrementAndGet()
            return
        }

        val estimatedHeapBytes = EventHeapEstimator.estimateQueuedEventBytes(event)
        if (!tryReserveHeapBudget(estimatedHeapBytes)) {
            dropCount.incrementAndGet()
            warnDroppedEventsIfNeeded()
            return
        }

        if (!offerWithTimeout(event)) {
            releaseHeapBudget(estimatedHeapBytes)
            dropCount.incrementAndGet()
            warnDroppedEventsIfNeeded()
        }
        if (queue.size >= batchSize) {
            scheduler.execute(this::flush)
        }
    }

    private fun offerWithTimeout(event: AgentEvent): Boolean {
        return try {
            queue.offer(event, QUEUE_OFFER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun tryReserveHeapBudget(estimatedHeapBytes: Long): Boolean {
        while (true) {
            val current = retainedEstimatedHeapBytes.get()
            if (estimatedHeapBytes > queueMemoryBudgetBytes - current) {
                return false
            }
            if (retainedEstimatedHeapBytes.compareAndSet(current, current + estimatedHeapBytes)) {
                return true
            }
        }
    }

    private fun releaseHeapBudget(estimatedHeapBytes: Long) {
        retainedEstimatedHeapBytes.updateAndGet { current ->
            (current - estimatedHeapBytes).coerceAtLeast(0)
        }
    }

    private fun warnDroppedEventsIfNeeded() {
        val now = System.currentTimeMillis()
        val previous = lastDropWarnAt.get()
        if (now - previous < DROP_WARN_INTERVAL_MS) {
            return
        }

        if (lastDropWarnAt.compareAndSet(previous, now)) {
            System.err.println(
                "[Log Friends] Dropped events because SDK queue is full. " +
                    "dropped=${dropCount.get()}, queued=${queue.size}"
            )
        }
    }

    @Synchronized
    internal fun flush() {
        val buffer = ArrayList<AgentEvent>(batchSize)
        queue.drainTo(buffer, batchSize)
        if (buffer.isEmpty()) return
        val batchEstimatedHeapBytes = buffer.sumOf(EventHeapEstimator::estimateQueuedEventBytes)

        inFlightCount.addAndGet(buffer.size.toLong())
        val json = EventJsonWriter.writeBatch(workerId, buffer)
        try {
            postBatch?.invoke(json) ?: ingestClient.post(json)
            sentCount.addAndGet(buffer.size.toLong())
        } catch (e: Exception) {
            dropCount.addAndGet(buffer.size.toLong())
            System.err.println(
                "[Log Friends] Batch flush failed; dropped=${buffer.size}, " +
                    "queued=${queue.size}: ${e.message}"
            )
        } finally {
            inFlightCount.addAndGet(-buffer.size.toLong())
            releaseHeapBudget(batchEstimatedHeapBytes)
        }
    }

    companion object {
        private const val QUEUE_OFFER_TIMEOUT_MS = 10L
        private const val DROP_WARN_INTERVAL_MS = 60_000L

        @Volatile
        private var instance: BatchTransporter? = null

        @JvmStatic
        fun getInstance(): BatchTransporter {
            return instance ?: synchronized(this) {
                instance ?: run {
                    val batch = BatchTransportConfig.batchSize()
                    val interval = BatchTransportConfig.intervalMs()
                    BatchTransporter(batch, interval).also { instance = it }
                }
            }
        }
    }
}

data class TransportStats(
    val captured: Long,
    val sent: Long,
    val dropped: Long,
    val queued: Long,
    val inFlight: Long,
    val estimatedRetainedHeapBytes: Long = 0,
    val queueMemoryBudgetBytes: Long = 0
) {
    val accounted: Long
        get() = sent + dropped + queued + inFlight

    fun toLogMessage(): String =
        "captured=$captured, sent=$sent, dropped=$dropped, queued=$queued, inFlight=$inFlight"
}
