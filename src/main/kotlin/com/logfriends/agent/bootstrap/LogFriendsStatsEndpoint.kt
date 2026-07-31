package com.logfriends.agent.bootstrap

import com.logfriends.agent.transport.BatchTransporter
import com.logfriends.agent.transport.TransportStats
import org.springframework.boot.actuate.endpoint.annotation.Endpoint
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation

@Endpoint(id = "logfriends")
class LogFriendsStatsEndpoint {

    @ReadOperation
    fun stats(): TransportStats = BatchTransporter.getInstance().snapshot()
}
