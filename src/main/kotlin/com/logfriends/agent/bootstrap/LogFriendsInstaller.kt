package com.logfriends.agent.bootstrap

import com.logfriends.agent.instrumentation.InstrumentationRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import org.springframework.boot.SpringApplication
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.core.env.ConfigurableEnvironment

/**
 * Spring Boot 기동 초기 (Environment 준비 단계)에 ByteBuddy agent를 동적으로 설치한다.
 * META-INF/spring.factories 에 등록되어 자동 실행된다.
 *
 * JVM 옵션 필요: -Djdk.attach.allowAttachSelf=true
 */
class LogFriendsInstaller : EnvironmentPostProcessor {

    override fun postProcessEnvironment(
        environment: ConfigurableEnvironment,
        application: SpringApplication
    ) {
        val enabled = environment.getProperty("logfriends.agent.enabled", "true")
        if (enabled.equals("false", ignoreCase = true)) {
            LogFriendsRuntime.disable("logfriends.agent.enabled=false")
            println("[Log Friends] SDK disabled by logfriends.agent.enabled=false")
            return
        }

        if (!LogFriendsRuntime.configureWorkerId(environment)) {
            System.err.println("[Log Friends] SDK disabled: workerId is required")
            System.err.println("[Log Friends] Set LOGFRIENDS_WORKER_ID or -Dlogfriends.worker.id")
            return
        }

        if (!LogFriendsRuntime.configureIngestUrl(environment)) {
            System.err.println("[Log Friends] SDK disabled: ingestUrl is required")
            System.err.println("[Log Friends] Set LOGFRIENDS_INGEST_URL or -Dlogfriends.ingest.url")
            return
        }

        LogFriendsRuntime.configureAppName(environment)
        LogFriendsRuntime.configureAppVersion(environment)

        try {
            val inst = ByteBuddyAgent.install()
            LogFriendsRuntime.markInstrumentationInstalled(inst)
            InstrumentationRegistry.installAll(inst)
            println("[Log Friends] Dynamic agent installed successfully")
        } catch (e: Exception) {
            System.err.println("[Log Friends] Failed to install dynamic agent: ${e.message}")
            System.err.println("[Log Friends] Add JVM option: -Djdk.attach.allowAttachSelf=true")
        }
    }
}