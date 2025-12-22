/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.mcp

import com.jonnyzzz.intellij.mcp.storage.ExecutionId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement

fun ToolCallResult.Companion.builder() = ToolCallBuilder()

class ToolCallBuilder {
    private val contents = mutableListOf<ContentItem>()
    private var isError = false
    private var structuredContents = mutableListOf<JsonElement>()

    fun addTextContent(content: String): ToolCallBuilder = addContent(ContentItem.Text(content))
    fun addContent(content: ContentItem): ToolCallBuilder = apply {
        contents += content
    }

    fun markAsError(): ToolCallBuilder = apply {
        isError = true
    }

    fun setExecutionId(executionId: ExecutionId): ToolCallBuilder = apply {
        structuredContents += json.encodeToJsonElement(ExecutionInfo(executionId.executionId))
    }

    fun build() = ToolCallResult(
        content = contents.toList(),
        isError = isError,
        structuredContent = if (structuredContents.isEmpty()) null else JsonArray(structuredContents)
    )
}

private val json = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
}

@Serializable
data class ExecutionInfo(
    val executionId: String,
)