package com.logfriends.agent.transport

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BatchTransportConfigTest {

    @Test
    fun `environment value takes precedence over system property`() {
        assertEquals(
            250,
            BatchTransportConfig.resolvePositiveInt(
                environmentValue = "250",
                propertyValue = "100",
                defaultValue = 50
            )
        )
        assertEquals(
            2_000L,
            BatchTransportConfig.resolvePositiveLong(
                environmentValue = "2000",
                propertyValue = "500",
                defaultValue = 100L
            )
        )
    }

    @Test
    fun `system property is used when environment value is absent`() {
        assertEquals(
            300,
            BatchTransportConfig.resolvePositiveInt(
                environmentValue = null,
                propertyValue = "300",
                defaultValue = 50
            )
        )
    }

    @Test
    fun `invalid values fall back to the next valid candidate or default`() {
        assertEquals(
            400,
            BatchTransportConfig.resolvePositiveInt(
                environmentValue = "invalid",
                propertyValue = "400",
                defaultValue = 50
            )
        )
        assertEquals(
            500L,
            BatchTransportConfig.resolvePositiveLong(
                environmentValue = "0",
                propertyValue = "-1",
                defaultValue = 500L
            )
        )
    }
}
