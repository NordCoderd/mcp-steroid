/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.mcp

import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import java.util.*
import java.util.concurrent.ConcurrentHashMap

private val log = Logger.getInstance("com.jonnyzzz.intellij.mcp.mcp.McpSession")

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

    init {
        log.info("[MCP SessionManager] Initialized (new instance - all previous sessions are invalidated)")
    }

    /**
     * Create a new session.
     */
    fun createSession(): McpSession {
        val session = McpSession()
        sessions[session.id] = session
        log.info("[MCP SessionManager] Created session: ${session.id} (total active: ${sessions.size})")
        return session
    }

    /**
     * Get an existing session by ID.
     */
    fun getSession(id: String): McpSession? {
        val session = sessions[id]
        if (session == null) {
            log.debug("[MCP SessionManager] Session not found: $id (active sessions: ${sessions.keys.joinToString(", ").ifEmpty { "none" }})")
        }
        return session
    }

    /**
     * Remove and close a session.
     */
    fun removeSession(id: String) {
        val removed = sessions.remove(id)
        if (removed != null) {
            removed.close()
            log.info("[MCP SessionManager] Removed session: $id (remaining: ${sessions.size})")
        } else {
            log.warn("[MCP SessionManager] Attempted to remove non-existent session: $id")
        }
    }

    /**
     * Get all active sessions.
     */
    fun getAllSessions(): Collection<McpSession> = sessions.values

    /**
     * Get count of active sessions.
     */
    fun getSessionCount(): Int = sessions.size
}
