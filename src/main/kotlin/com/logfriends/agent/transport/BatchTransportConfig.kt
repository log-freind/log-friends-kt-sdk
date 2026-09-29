package com.logfriends.agent.transport

internal object BatchTransportConfig {
    const val DEFAULT_BATCH_SIZE = 100
    const val DEFAULT_INTERVAL_MS = 500L
    const val DEFAULT_QUEUE_CAPACITY = 10_000
    const val DEFAULT_QUEUE_MEMORY_BUDGET_BYTES = 32L * 1024L * 1024L

    fun batchSize(): Int = resolvePositiveInt(
        environmentValue = System.getenv("LOGFRIENDS_BATCH_SIZE"),
        propertyValue = System.getProperty("logfriends.batch.size"),
        defaultValue = DEFAULT_BATCH_SIZE
    )

    fun intervalMs(): Long = resolvePositiveLong(
        environmentValue = System.getenv("LOGFRIENDS_BATCH_INTERVAL_MS"),
        propertyValue = System.getProperty("logfriends.batch.interval.ms"),
        defaultValue = DEFAULT_INTERVAL_MS
    )

    fun queueCapacity(): Int = resolvePositiveInt(
        environmentValue = System.getenv("LOGFRIENDS_QUEUE_CAPACITY"),
        propertyValue = System.getProperty("logfriends.queue.capacity"),
        defaultValue = DEFAULT_QUEUE_CAPACITY
    )

    fun queueMemoryBudgetBytes(): Long = resolvePositiveLong(
        environmentValue = System.getenv("LOGFRIENDS_QUEUE_MEMORY_BUDGET_BYTES"),
        propertyValue = System.getProperty("logfriends.queue.memory.budget.bytes"),
        defaultValue = DEFAULT_QUEUE_MEMORY_BUDGET_BYTES
    )

    internal fun resolvePositiveInt(
        environmentValue: String?,
        propertyValue: String?,
        defaultValue: Int
    ): Int {
        return sequenceOf(environmentValue, propertyValue)
            .mapNotNull { it?.trim()?.toIntOrNull() }
            .firstOrNull { it > 0 }
            ?: defaultValue
    }

    internal fun resolvePositiveLong(
        environmentValue: String?,
        propertyValue: String?,
        defaultValue: Long
    ): Long {
        return sequenceOf(environmentValue, propertyValue)
            .mapNotNull { it?.trim()?.toLongOrNull() }
            .firstOrNull { it > 0 }
            ?: defaultValue
    }
}
