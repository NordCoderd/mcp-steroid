/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp

import com.jonnyzzz.intellij.mcp.server.SteroidsMcpServer

/**
 * Test utilities for MCP server tests.
 * Provides access to the MCP server service in tests.
 */
object McpTestUtil {
    /**
     * Get the SSE URL if the server is running.
     */
    fun getSseUrlIfRunning(): String {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()
        assert(server.port > 0)
        return server.mcpUrl
    }
}
