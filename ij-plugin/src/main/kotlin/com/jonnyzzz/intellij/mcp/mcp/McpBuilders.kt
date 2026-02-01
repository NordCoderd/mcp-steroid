/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.mcp

import com.jonnyzzz.mcpSteroid.storage.ExecutionId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement

fun ToolCallResult.Companion.builder() = ToolCallBuilder()

class ToolCallBuilder {
    private val contents = mutableListOf<ContentItem>()
    private var isError = false

    fun addTextContent(content: String): ToolCallBuilder = addContent(ContentItem.Text(content))
    fun addContent(content: ContentItem): ToolCallBuilder = apply {
        contents += content
    }

    fun markAsError(): ToolCallBuilder = apply {
        isError = true
    }

    fun setExecutionId(executionId: ExecutionId): ToolCallBuilder = apply {
        addTextContent("Execution ID: ${executionId.executionId}")
    }

    fun build(): ToolCallResult {
        return ToolCallResult(
            content = contents.toList(),
            isError = isError,
        )
    }
}
