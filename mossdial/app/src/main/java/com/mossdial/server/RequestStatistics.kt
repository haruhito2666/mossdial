package com.mossdial.server

import java.util.ArrayDeque

class RequestStatistics(capacity: Int) {
    private val recentCapacity = capacity
    private val lock = Any()
    private val recent = ArrayDeque<RequestLogEntry>(recentCapacity)
    private val statuses = linkedMapOf<Int, Long>()
    private var totalRequests = 0L
    private var bytesSent = 0L

    init {
        require(recentCapacity > 0)
    }

    fun record(entry: RequestLogEntry, retainRequest: Boolean) {
        synchronized(lock) {
            totalRequests++
            bytesSent += entry.bytes
            statuses[entry.status] = (statuses[entry.status] ?: 0L) + 1L
            if (retainRequest) {
                if (recent.size == recentCapacity) recent.removeFirst()
                recent.addLast(entry)
            }
        }
    }

    fun snapshot(trackedRateLimitClients: Int): ServerStatisticsSnapshot = synchronized(lock) {
        ServerStatisticsSnapshot(
            totalRequests = totalRequests,
            bytesSent = bytesSent,
            statusCounts = statuses.toMap(),
            recentRequests = recent.toList(),
            trackedRateLimitClients = trackedRateLimitClients
        )
    }
}

data class RequestLogEntry(
    val method: String,
    val path: String,
    val status: Int,
    val bytes: Long,
    val timestampMillis: Long
)

data class ServerStatisticsSnapshot(
    val totalRequests: Long,
    val bytesSent: Long,
    val statusCounts: Map<Int, Long>,
    val recentRequests: List<RequestLogEntry>,
    val trackedRateLimitClients: Int
) {
    companion object {
        /** What a stopped server, or one that has served nothing yet, reports. */
        val EMPTY = ServerStatisticsSnapshot(0L, 0L, emptyMap(), emptyList(), 0)
    }
}
