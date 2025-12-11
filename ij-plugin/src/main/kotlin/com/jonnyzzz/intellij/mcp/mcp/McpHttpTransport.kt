/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.mcp

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.sse.*
import io.ktor.server.sse.*
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach

/**
 * MCP Streamable HTTP Transport implementation for Ktor.
 * Follows the MCP 2025-06-18 specification.
 *
 * The transport supports:
 * - POST requests for sending messages (requests and notifications)
 * - GET requests for establishing SSE streams for server-to-client notifications
 * - Session management via Mcp-Session-Id header
 */
object McpHttpTransport {

    const val SESSION_HEADER = "Mcp-Session-Id"

    /**
     * Install MCP routes at the specified path.
     */
    fun Route.installMcp(path: String, server: McpServerCore) {
        route(path) {
            // POST - Handle incoming messages (requests and notifications)
            post {
                handlePost(call, server)
            }

            // GET - Establish SSE stream for server-to-client notifications
            sse {
                handleSse(server)
            }

            // DELETE - Terminate session
            delete {
                handleDelete(call, server)
            }
        }
    }

    private suspend fun handlePost(call: ApplicationCall, server: McpServerCore) {
        val sessionId = call.request.header(SESSION_HEADER)
        val session = if (sessionId != null) {
            server.sessionManager.getSession(sessionId)
        } else {
            // Create new session for initial request
            server.sessionManager.createSession()
        }

        if (session == null) {
            call.respond(HttpStatusCode.BadRequest, "Invalid session")
            return
        }

        val body = call.receiveText()
        if (body.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, "Empty request body")
            return
        }

        val response = server.handleMessage(body, session)

        // Include session ID in response for new sessions
        if (sessionId == null) {
            call.response.header(SESSION_HEADER, session.id)
        }

        if (response != null) {
            call.respondText(response, ContentType.Application.Json)
        } else {
            // Notification - no response needed
            call.respond(HttpStatusCode.Accepted)
        }
    }

    private suspend fun ServerSSESession.handleSse(server: McpServerCore) {
        val sessionId = call.request.header(SESSION_HEADER)
        val session = if (sessionId != null) {
            server.sessionManager.getSession(sessionId)
        } else {
            server.sessionManager.createSession().also {
                call.response.header(SESSION_HEADER, it.id)
            }
        }

        if (session == null) {
            // Can't respond with error in SSE, just close
            return
        }

        // Stream notifications to client
        session.notifications()
            .onEach { notification ->
                val json = McpJson.encodeToString(JsonRpcNotification.serializer(), notification)
                send(ServerSentEvent(data = json, event = "message"))
            }
            .catch { /* Session closed or error */ }
            .collect()
    }

    private suspend fun handleDelete(call: ApplicationCall, server: McpServerCore) {
        val sessionId = call.request.header(SESSION_HEADER)
        if (sessionId != null) {
            server.sessionManager.removeSession(sessionId)
            call.respond(HttpStatusCode.NoContent)
        } else {
            call.respond(HttpStatusCode.BadRequest, "Missing session ID")
        }
    }
}
