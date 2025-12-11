/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp

import com.intellij.mcpserver.impl.McpServerService

/**
 * Utility for starting and managing the MCP server in tests.
 *
 * Usage:
 * ```
 * withMcpServer { port, sseUrl ->
 *     // Test code here
 * }
 * ```
 */
object McpTestUtil {

    /**
     * Starts the MCP server, executes the action, and stops the server.
     * The port is dynamically obtained from the McpServerService.
     *
     * @param action The action to execute with the server port and SSE URL
     */
    inline fun <T> withMcpServer(action: (port: Int, sseUrl: String) -> T): T {
        val mcpService = McpServerService.getInstance()
        mcpService.start()

        return try {
            val port = mcpService.port
            val sseUrl = mcpService.serverSseUrl
            println("[MCP] Server started on port: $port")
            println("[MCP] SSE URL: $sseUrl")
            action(port, sseUrl)
        } finally {
            mcpService.stop()
            println("[MCP] Server stopped")
        }
    }

    /**
     * Gets the current MCP server port if the server is running.
     * @return The port number, or null if the server is not running
     */
    fun getPortIfRunning(): Int? {
        val mcpService = McpServerService.getInstance()
        return if (mcpService.isRunning) {
            mcpService.port
        } else {
            null
        }
    }

    /**
     * Gets the current MCP server SSE URL if the server is running.
     * @return The SSE URL, or null if the server is not running
     */
    fun getSseUrlIfRunning(): String? {
        val mcpService = McpServerService.getInstance()
        return if (mcpService.isRunning) {
            mcpService.serverSseUrl
        } else {
            null
        }
    }
}
