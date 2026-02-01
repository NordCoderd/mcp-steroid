/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.server

import com.intellij.openapi.extensions.ExtensionPointName
import com.jonnyzzz.intellij.mcp.mcp.McpServerCore

/**
 * Extension point interface for registering MCP tools and resources.
 * Implementations can register tools, resources, or both with the MCP server.
 */
interface McpRegistrar {
    /**
     * Register tools and/or resources with the MCP server.
     * Called once during server startup.
     */
    fun register(server: McpServerCore)

    companion object {
        val EP_NAME: ExtensionPointName<McpRegistrar> =
            ExtensionPointName.create("com.jonnyzzz.mcp-steroid.mcpRegistrar")
    }
}
