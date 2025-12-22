/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.mcp

import com.jonnyzzz.intellij.mcp.storage.ExecutionId
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

fun ToolCallResult.Companion.builder() = ToolCallBuilder()

class ToolCallBuilder {
    private val contents = mutableListOf<ContentItem>()
    private var isError = false
    private val structuredContents = mutableMapOf<String, JsonElement>()

    fun addTextContent(content: String): ToolCallBuilder = addContent(ContentItem.Text(content))
    fun addContent(content: ContentItem): ToolCallBuilder = apply {
        contents += content
    }

    fun markAsError(): ToolCallBuilder = apply {
        isError = true
        structuredContents["has_errors"] = JsonPrimitive(true)
    }

    fun setExecutionId(executionId: ExecutionId): ToolCallBuilder = apply {
        structuredContents["executionId"] = JsonPrimitive(executionId.executionId)
    }

    fun build() = ToolCallResult(
        content = contents.toList(),
        isError = isError,
        structuredContent = JsonObject(structuredContents)
    )
}