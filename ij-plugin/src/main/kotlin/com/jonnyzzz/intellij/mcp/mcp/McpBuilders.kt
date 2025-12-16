/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

fun ToolCallResult.Companion.builder() = ToolCallBuilder()

class ToolCallBuilder {
    private val contents = mutableListOf<ContentItem>()
    private var isError = false
    private var structuredContents: JsonElement? = null

    fun addTextContent(content: String): ToolCallBuilder = addContent(ContentItem.Text(content))
    fun addContent(content: ContentItem): ToolCallBuilder = apply {
        contents += content
    }

    fun markAsError(): ToolCallBuilder = apply {
        isError = true
    }

    fun build() = ToolCallResult(
        content = contents.toList(),
        isError = isError,
        structuredContent = if (structuredContents != null) {
            json.decodeFromString(json.encodeToString(structuredContents))
        } else {
            null
        }
    )
}

private val json = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
}
