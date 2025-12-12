/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.mcp

import com.intellij.openapi.diagnostic.Logger
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * MCP Streamable HTTP Transport implementation for Ktor.
 * Follows the MCP 2025-06-18 specification.
 *
 * The transport supports:
 * - POST requests for sending messages (requests and notifications)
 * - GET requests for establishing SSE streams for server-to-client notifications
 * - Session management via Mcp-Session-Id header
 *
 * Header requirements per MCP spec:
 * - Client MUST include Accept header with application/json (and optionally text/event-stream)
 * - Client MUST include Content-Type: application/json for POST requests
 * - Server responds with Content-Type: application/json for JSON responses
 */
object McpHttpTransport {
    private val log = Logger.getInstance(McpHttpTransport::class.java)

    const val SESSION_HEADER = "Mcp-Session-Id"
    private const val CONTENT_TYPE_JSON = "application/json"
    private const val CONTENT_TYPE_SSE = "text/event-stream"

    /**
     * Install MCP routes at the specified path.
     */
    fun Route.installMcp(path: String, server: McpServerCore) {
        route(path) {
            // POST - Handle incoming messages (requests and notifications)
            post {
                handlePost(call, server)
            }

            // GET - SSE stream for server-to-client notifications
            // Per MCP spec:
            // - Client MUST include Accept header with text/event-stream
            // - Server MUST return Content-Type: text/event-stream OR HTTP 405 Method Not Allowed
            get {
                handleGet(call)
            }

            // DELETE - Terminate session
            delete {
                val remoteHost = call.request.local.remoteHost
                log.info("[MCP] DELETE request from $remoteHost")
                handleDelete(call, server)
            }
        }
    }

    private suspend fun handlePost(call: ApplicationCall, server: McpServerCore) {
        // Log incoming request details
        val remoteHost = call.request.local.remoteHost
        val userAgent = call.request.userAgent() ?: "unknown"
        log.info("[MCP] POST request from $remoteHost (User-Agent: $userAgent)")

        // Log all headers for debugging
        call.request.headers.forEach { name, values ->
            log.debug("[MCP] Header: $name = ${values.joinToString(", ")}")
        }

        // Validate Accept header per MCP spec
        // Client MUST include Accept header that includes application/json
        val acceptHeader = call.request.accept()
        if (acceptHeader != null && !acceptsJson(acceptHeader)) {
            log.warn("[MCP] Rejecting request: Accept header '$acceptHeader' doesn't include application/json")
            call.respond(HttpStatusCode.NotAcceptable, "Accept header must include application/json")
            return
        }

        // Validate Content-Type header per MCP spec
        // Client MUST include Content-Type: application/json
        val contentType = call.request.contentType()
        if (!contentType.match(ContentType.Application.Json)) {
            log.warn("[MCP] Rejecting request: Content-Type '$contentType' is not application/json")
            call.respond(HttpStatusCode.UnsupportedMediaType, "Content-Type must be application/json")
            return
        }

        val sessionId = call.request.header(SESSION_HEADER)
        val session = if (sessionId != null) {
            log.debug("[MCP] Using existing session: $sessionId")
            server.sessionManager.getSession(sessionId)
        } else {
            log.info("[MCP] Creating new session")
            server.sessionManager.createSession()
        }

        if (session == null) {
            log.warn("[MCP] Invalid or expired session ID: $sessionId")
            call.respond(HttpStatusCode.BadRequest, "Invalid session")
            return
        }

        val body = call.receiveText()
        if (body.isBlank()) {
            log.warn("[MCP] Empty request body")
            call.respond(HttpStatusCode.BadRequest, "Empty request body")
            return
        }

        // Log the request body (truncated for large payloads)
        val truncatedBody = if (body.length > 500) body.take(500) + "...[truncated]" else body
        log.info("[MCP] Request body: $truncatedBody")

        val response = server.handleMessage(body, session)

        // Include session ID in response for new sessions
        if (sessionId == null) {
            log.info("[MCP] New session created: ${session.id}")
            call.response.header(SESSION_HEADER, session.id)
        }

        if (response != null) {
            // Log the response (truncated for large payloads)
            val truncatedResponse = if (response.length > 500) response.take(500) + "...[truncated]" else response
            log.info("[MCP] Response: $truncatedResponse")
            call.respondText(response, ContentType.Application.Json)
        } else {
            // Notification - no response needed
            log.info("[MCP] Notification processed, returning 202 Accepted")
            call.respond(HttpStatusCode.Accepted)
        }
    }

    /**
     * Handle GET requests for SSE streams.
     * Per MCP spec:
     * - Client MUST include Accept header with text/event-stream
     * - Server MUST return Content-Type: text/event-stream OR HTTP 405 Method Not Allowed
     */
    private suspend fun handleGet(call: ApplicationCall) {
        val remoteHost = call.request.local.remoteHost
        val userAgent = call.request.userAgent() ?: "unknown"
        log.info("[MCP] GET request from $remoteHost (User-Agent: $userAgent)")

        // Validate Accept header per MCP spec
        // Client MUST include Accept header with text/event-stream
        val acceptHeader = call.request.accept()
        if (acceptHeader == null || !acceptsSse(acceptHeader)) {
            log.warn("[MCP] Rejecting GET request: Accept header '${acceptHeader ?: "missing"}' doesn't include text/event-stream")
            call.respond(HttpStatusCode.NotAcceptable, "Accept header must include text/event-stream")
            return
        }

        // We don't support SSE notifications, return 405 per MCP spec
        log.info("[MCP] GET request from $remoteHost - returning 405 (SSE not supported)")
        call.respond(HttpStatusCode.MethodNotAllowed, "SSE notifications not supported")
    }

    /**
     * Check if the Accept header includes application/json.
     * Per MCP spec, clients MUST accept application/json for POST requests.
     */
    private fun acceptsJson(acceptHeader: String): Boolean {
        // Accept header can contain multiple types separated by comma
        // Each type can have parameters like q=0.9
        // Examples: "application/json", "*/*", "application/json, text/event-stream"
        return acceptHeader.split(",").any { part ->
            val mediaType = part.trim().split(";").first().trim()
            mediaType == CONTENT_TYPE_JSON || mediaType == "*/*" || mediaType == "application/*"
        }
    }

    /**
     * Check if the Accept header includes text/event-stream.
     * Per MCP spec, clients MUST accept text/event-stream for GET requests.
     */
    private fun acceptsSse(acceptHeader: String): Boolean {
        return acceptHeader.split(",").any { part ->
            val mediaType = part.trim().split(";").first().trim()
            mediaType == CONTENT_TYPE_SSE || mediaType == "*/*" || mediaType == "text/*"
        }
    }

    private suspend fun handleDelete(call: ApplicationCall, server: McpServerCore) {
        val sessionId = call.request.header(SESSION_HEADER)
        if (sessionId != null) {
            log.info("[MCP] Terminating session: $sessionId")
            server.sessionManager.removeSession(sessionId)
            call.respond(HttpStatusCode.NoContent)
        } else {
            log.warn("[MCP] DELETE request without session ID")
            call.respond(HttpStatusCode.BadRequest, "Missing session ID")
        }
    }
}
