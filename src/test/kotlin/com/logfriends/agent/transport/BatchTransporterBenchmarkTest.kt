package com.logfriends.agent.transport

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.system.measureNanoTime

class BatchTransporterBenchmarkTest {

    @Test
    fun `benchmark log event enqueue overhead`() {
        assumeTrue(
            System.getProperty("logfriends.benchmark") == "true",
            "Set -Dlogfriends.benchmark=true to run local SDK benchmark"
        )

        val warmupIterations = 20_000
        val measuredIterations = 100_000
        val transporter = BatchTransporter(
            batchSize = warmupIterations + measuredIterations + 1,
            intervalMs = 60_000,
            ingestUrl = "http://console/ingest",
            workerId = "worker-benchmark",
            queueCapacity = warmupIterations + measuredIterations + 1
        ) {
            // Keep the benchmark focused on request-thread enqueue overhead.
        }

        try {
            repeat(warmupIterations) {
                transporter.enqueueLogEvent(
                    eventName = "cartItemAdded",
                    paramNames = PARAM_NAMES,
                    args = PARAM_VALUES
                )
            }

            val elapsedNs = measureNanoTime {
                repeat(measuredIterations) {
                    transporter.enqueueLogEvent(
                        eventName = "cartItemAdded",
                        paramNames = PARAM_NAMES,
                        args = PARAM_VALUES
                    )
                }
            }

            val averageNs = elapsedNs / measuredIterations
            val averageMicros = averageNs / 1_000.0
            println(
                "[Log Friends Benchmark] enqueueLogEvent iterations=$measuredIterations, " +
                    "avg=${"%.2f".format(averageMicros)}us, stats=${transporter.stats}"
            )
        } finally {
            transporter.shutdown()
        }
    }

    companion object {
        private val PARAM_NAMES = arrayOf(
            "cartId",
            "userId",
            "productId",
            "quantity",
            "sourcePage"
        )

        private val PARAM_VALUES = arrayOf<Any?>(
            "cart-demo-001",
            "demo-user-001",
            "PRD-HOM-033",
            1,
            "shop"
        )
    }
}
