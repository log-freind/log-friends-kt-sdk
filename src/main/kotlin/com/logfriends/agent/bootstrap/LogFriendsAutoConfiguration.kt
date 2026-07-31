package com.logfriends.agent.bootstrap

import com.logfriends.agent.discovery.DiscoveredLogEventScanner
import com.logfriends.agent.spec.SpecScanner
import com.logfriends.agent.transport.AgentRegistrationClient
import com.logfriends.agent.transport.BatchTransporter
import com.logfriends.agent.transport.DiscoveredLogEventReportClient
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationListener
import org.springframework.context.annotation.Bean
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@AutoConfiguration
class LogFriendsAutoConfiguration {

    @Bean
    @ConditionalOnClass(name = ["org.springframework.boot.actuate.endpoint.annotation.Endpoint"])
    fun logFriendsStatsEndpoint(): LogFriendsStatsEndpoint = LogFriendsStatsEndpoint()

    @Bean
    fun logFriendsReadyListener(): ApplicationListener<ApplicationReadyEvent> {
        return ApplicationListener {
            if (LogFriendsRuntime.isDisabled()) {
                println("[Log Friends] Agent not started. Reason: ${LogFriendsRuntime.disabledReason()}")
                return@ApplicationListener
            }

            val executor = Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "log-friends-startup").apply { isDaemon = true }
            }

            executor.execute {
                registerAgent()
            }

            // 5초 딜레이: Spring @Bean 초기화 완료 후 스펙 스캔
            executor.schedule({
                try {
                    SpecScanner.scan()
                } catch (e: Exception) {
                    System.err.println("[Log Friends] SpecScanner failed: ${e.message}")
                }
            }, 5, TimeUnit.SECONDS)

            Runtime.getRuntime().addShutdownHook(Thread({
                val transporter = BatchTransporter.getInstance()
                println("[Log Friends] Shutting down. Stats: ${transporter.stats}")
                transporter.shutdown()
            }, "log-friends-shutdown"))

            println(
                "[Log Friends] Agent ready. " +
                    "workerId=${LogFriendsRuntime.workerId}, ingestUrl=${LogFriendsRuntime.ingestUrl}"
            )
        }
    }

    private fun registerAgent() {
        val workerId = LogFriendsRuntime.workerId ?: return
        val ingestUrl = LogFriendsRuntime.ingestUrl ?: return
        val appName = LogFriendsRuntime.appName ?: run {
            System.err.println(
                "[Log Friends] Agent auto-registration skipped: appName is missing. " +
                    "Set LOGFRIENDS_APP_NAME, logfriends.app.name, or spring.application.name"
            )
            return
        }

        try {
            val handshake = AgentRegistrationClient.fromIngestUrl(ingestUrl).register(workerId, appName)
            LogFriendsRuntime.markRegistered(handshake)
            println(
                "[Log Friends] Agent registered. " +
                    "agentId=${handshake.agentId ?: "unknown"}, " +
                    "workerId=${handshake.workerId}, " +
                    "appName=${handshake.appName ?: appName}, " +
                    "knownLogSpecs=${handshake.knownLogSpecCount}"
            )
            scanAndReportDiscoveredLogEvents()
        } catch (e: Exception) {
            System.err.println("[Log Friends] Agent auto-registration failed: ${e.message}")
        }
    }

    private fun scanAndReportDiscoveredLogEvents() {
        val instrumentation = LogFriendsRuntime.instrumentation ?: run {
            System.err.println("[Log Friends] Discovered LOG_EVENT scan skipped: instrumentation is missing")
            return
        }
        val handshake = LogFriendsRuntime.handshake ?: run {
            System.err.println("[Log Friends] Discovered LOG_EVENT report skipped: agent handshake is missing")
            return
        }
        val ingestUrl = LogFriendsRuntime.ingestUrl ?: return

        val candidates = DiscoveredLogEventScanner.scan(instrumentation)
        println("[Log Friends] Discovered LOG_EVENT candidates=${candidates.size}")
        if (candidates.isEmpty()) return

        try {
            val result = DiscoveredLogEventReportClient.fromIngestUrl(ingestUrl)
                .report(
                    handshake = handshake,
                    appVersion = LogFriendsRuntime.appVersion,
                    events = candidates
                )
            println("[Log Friends] Discovered LOG_EVENT report completed. received=${result.received}")
        } catch (e: Exception) {
            System.err.println("[Log Friends] Discovered LOG_EVENT report failed: ${e.message}")
        }
    }
}
