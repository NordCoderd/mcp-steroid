/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.mcp

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.serialization.json.JsonElement
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Represents an MCP session with its state and notification channel.
 */
class McpSession(
    val id: String = UUID.randomUUID().toString()
) {
    @Volatile
    var initialized: Boolean = false
        private set

    @Volatile
    var clientInfo: ClientInfo? = null
        private set

    @Volatile
    var clientCapabilities: ClientCapabilities? = null
        private set

    private val notificationChannel = Channel<JsonRpcNotification>(Channel.BUFFERED)

    /**
     * Mark session as initialized after successful initialize/initialized exchange.
     */
    fun markInitialized(info: ClientInfo, capabilities: ClientCapabilities) {
        clientInfo = info
        clientCapabilities = capabilities
        initialized = true
    }

    /**
     * Send a notification to this session's SSE stream.
     */
    fun sendNotification(notification: JsonRpcNotification) {
        notificationChannel.trySend(notification)
    }

    /**
     * Get flow of notifications for SSE streaming.
     */
    fun notifications(): Flow<JsonRpcNotification> = notificationChannel.consumeAsFlow()

    /**
     * Close the session.
     */
    fun close() {
        notificationChannel.close()
    }
}

/**
 * Manages active MCP sessions.
 */
class McpSessionManager {
    private val sessions = ConcurrentHashMap<String, McpSession>()

    /**
     * Create a new session.
     */
    fun createSession(): McpSession {
        val session = McpSession()
        sessions[session.id] = session
        return session
    }

    /**
     * Get an existing session by ID.
     */
    fun getSession(id: String): McpSession? = sessions[id]

    /**
     * Remove and close a session.
     */
    fun removeSession(id: String) {
        sessions.remove(id)?.close()
    }

    /**
     * Get all active sessions.
     */
    fun getAllSessions(): Collection<McpSession> = sessions.values
}
