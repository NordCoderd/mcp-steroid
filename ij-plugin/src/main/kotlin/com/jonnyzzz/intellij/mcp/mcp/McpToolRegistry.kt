/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.mcp

import kotlinx.serialization.json.JsonObject

/**
 * Registry for MCP tools.
 */
class McpToolRegistry {
    private val tools = mutableMapOf<String, McpToolDefinition>()

    /**
     * Register a tool with its handler.
     */
    fun registerTool(
        name: String,
        description: String?,
        inputSchema: JsonObject,
        handler: suspend (ToolCallParams, McpSession) -> ToolCallResult
    ) {
        tools[name] = McpToolDefinition(
            tool = Tool(
                name = name,
                description = description,
                inputSchema = inputSchema
            ),
            handler = handler
        )
    }

    /**
     * Get all registered tools.
     */
    fun listTools(): List<Tool> = tools.values.map { it.tool }

    /**
     * Call a tool by name.
     */
    suspend fun callTool(params: ToolCallParams, session: McpSession): ToolCallResult {
        val definition = tools[params.name]
            ?: return ToolCallResult(
                content = listOf(ContentItem.Text(text = "Tool not found: ${params.name}")),
                isError = true
            )

        return try {
            definition.handler(params, session)
        } catch (e: Exception) {
            ToolCallResult(
                content = listOf(ContentItem.Text(text = "Tool execution error: ${e.message}")),
                isError = true
            )
        }
    }
}

/**
 * Internal representation of a registered tool with its handler.
 */
private data class McpToolDefinition(
    val tool: Tool,
    val handler: suspend (ToolCallParams, McpSession) -> ToolCallResult
)
