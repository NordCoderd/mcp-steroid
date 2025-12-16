/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.mcp

import kotlinx.serialization.json.*

/**
 * Core MCP server logic that handles JSON-RPC message dispatch.
 * Independent of transport layer.
 */
class McpServerCore(
    private val serverInfo: ServerInfo,
    private val capabilities: ServerCapabilities,
    private val instructions: String? = null,
) {
    val sessionManager = McpSessionManager()
    val toolRegistry = McpToolRegistry()

    /**
     * Handle an incoming JSON-RPC message and return a response (if applicable).
     * Returns null for notifications that don't require a response.
     */
    suspend fun handleMessage(message: String, session: McpSession): String? {
        val jsonElement = try {
            McpJson.parseToJsonElement(message)
        } catch (e: Exception) {
            return encodeError(JsonNull, JsonRpcErrorCodes.PARSE_ERROR, "Parse error: ${e.message}")
        }

        return when {
            jsonElement is JsonArray -> handleBatch(jsonElement, session)
            jsonElement is JsonObject -> handleSingle(jsonElement, session)
            else -> encodeError(JsonNull, JsonRpcErrorCodes.INVALID_REQUEST, "Invalid request")
        }
    }

    private suspend fun handleBatch(batch: JsonArray, session: McpSession): String {
        val responses = batch.mapNotNull { element ->
            if (element is JsonObject) {
                handleSingle(element, session)?.let { McpJson.parseToJsonElement(it) }
            } else {
                McpJson.parseToJsonElement(
                    encodeError(JsonNull, JsonRpcErrorCodes.INVALID_REQUEST, "Invalid request in batch")
                )
            }
        }
        return McpJson.encodeToString(JsonArray.serializer(), JsonArray(responses))
    }

    private suspend fun handleSingle(json: JsonObject, session: McpSession): String? {
        val id = json["id"]
        val method = json["method"]?.jsonPrimitive?.contentOrNull

        // Check if this is a notification (no id)
        if (id == null) {
            if (method != null) {
                handleNotification(method)
            }
            return null
        }

        if (method == null) {
            return encodeError(id, JsonRpcErrorCodes.INVALID_REQUEST, "Missing method")
        }

        return handleRequest(id, method, json["params"]?.jsonObject, session)
    }

    private suspend fun handleRequest(
        id: JsonElement,
        method: String,
        params: JsonObject?,
        session: McpSession
    ): String {
        return when (method) {
            McpMethods.INITIALIZE -> handleInitialize(id, params, session)
            McpMethods.PING -> handlePing(id)
            McpMethods.TOOLS_LIST -> handleToolsList(id)
            McpMethods.TOOLS_CALL -> handleToolsCall(id, params, session)
            else -> encodeError(id, JsonRpcErrorCodes.METHOD_NOT_FOUND, "Method not found: $method")
        }
    }

    private fun handleNotification(method: String) {
        when (method) {
            McpMethods.INITIALIZED -> {
                // Client confirmed initialization - nothing special needed
            }
            // Handle other notifications as needed
        }
    }

    private fun handleInitialize(id: JsonElement, params: JsonObject?, session: McpSession): String {
        val initParams = try {
            params?.let { McpJson.decodeFromJsonElement<InitializeParams>(it) }
        } catch (e: Exception) {
            return encodeError(id, JsonRpcErrorCodes.INVALID_PARAMS, "Invalid initialize params: ${e.message}")
        }

        if (initParams != null) {
            session.markInitialized(initParams.clientInfo, initParams.capabilities)
        }

        val result = InitializeResult(
            protocolVersion = MCP_PROTOCOL_VERSION,
            capabilities = capabilities,
            serverInfo = serverInfo,
            instructions = instructions
        )

        return encodeResult(id, McpJson.encodeToJsonElement(result))
    }

    private fun handlePing(id: JsonElement): String {
        return encodeResult(id, JsonObject(emptyMap()))
    }

    private fun handleToolsList(id: JsonElement): String {
        val tools = toolRegistry.listTools()
        val result = ToolsListResult(tools = tools)
        return encodeResult(id, McpJson.encodeToJsonElement(result))
    }

    private suspend fun handleToolsCall(id: JsonElement, params: JsonObject?, session: McpSession): String {
        val callParams = try {
            params?.let { McpJson.decodeFromJsonElement<ToolCallParams>(it) }
        } catch (e: Exception) {
            return encodeError(id, JsonRpcErrorCodes.INVALID_PARAMS, "Invalid tool call params: ${e.message}")
        }

        if (callParams == null) {
            return encodeError(id, JsonRpcErrorCodes.INVALID_PARAMS, "Missing tool call params")
        }

        val result = toolRegistry.callTool(callParams, session)
        return encodeResult(id, McpJson.encodeToJsonElement(result))
    }

    private fun encodeResult(id: JsonElement, result: JsonElement): String {
        val response = JsonRpcResponse(id = id, result = result)
        return McpJson.encodeToString(JsonRpcResponse.serializer(), response)
    }

    private fun encodeError(id: JsonElement, code: Int, message: String): String {
        val response = JsonRpcResponse(
            id = id,
            error = JsonRpcError(code = code, message = message)
        )
        return McpJson.encodeToString(JsonRpcResponse.serializer(), response)
    }

    /**
     * Send tools/list_changed notification to all sessions.
     */
    fun notifyToolsListChanged() {
        val notification = JsonRpcNotification(method = McpMethods.TOOLS_LIST_CHANGED)
        sessionManager.getAllSessions().forEach { it.sendNotification(notification) }
    }
}
