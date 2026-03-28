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
        // Consolidate consecutive text items into a single text block.
        // Some MCP clients (e.g. Claude CLI) only render the first text content item
        // when isError=true, so merging ensures the full output is always visible.
        val merged = mutableListOf<ContentItem>()
        val textBuffer = StringBuilder()

        fun flushText() {
            if (textBuffer.isNotEmpty()) {
                merged += ContentItem.Text(textBuffer.toString())
                textBuffer.clear()
            }
        }

        for (item in contents) {
            when (item) {
                is ContentItem.Text -> {
                    if (textBuffer.isNotEmpty()) textBuffer.append('\n')
                    textBuffer.append(item.text)
                }
                else -> {
                    flushText()
                    merged += item
                }
            }
        }
        flushText()

        return ToolCallResult(
            content = merged,
            isError = isError,
        )
    }
}
