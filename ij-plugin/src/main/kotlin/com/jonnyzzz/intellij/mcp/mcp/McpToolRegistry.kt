/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.mcp

import com.jonnyzzz.intellij.mcp.server.ProgressReporter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.atomic.AtomicInteger

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
        handler: suspend (ToolCallContext) -> ToolCallResult
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

        val progress = object : ProgressReporter {
            val counter = AtomicInteger(0)
            val progressToken = params.arguments?.get("_meta")?.jsonObject?.get("progressToken")

            override fun report(message: String) {
                if (progressToken == null) return

                val params = ProgressParams(
                    progressToken = progressToken,
                    progress = counter.incrementAndGet().toDouble(),
                    message = message
                )

                val notification = JsonRpcNotification(
                    method = McpMethods.PROGRESS,
                    params = McpJson.encodeToJsonElement(params).jsonObject
                )

                session.sendNotification(notification)
            }
        }

        val toolCallContext = ToolCallContext(params, session, progress)

        return try {
            definition.handler(toolCallContext)
        } catch (e: Exception) {
            ToolCallResult(
                content = listOf(ContentItem.Text(text = "Tool execution error: ${e.message}")),
                isError = true
            )
        }
    }
}

data class ToolCallContext(
    val params: ToolCallParams,
    val session: McpSession,
    val progress: ProgressReporter,
)

/**
 * Internal representation of a registered tool with its handler.
 */
private data class McpToolDefinition(
    val tool: Tool,
    val handler: suspend (context: ToolCallContext) -> ToolCallResult
)
