/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.McpServerCore

internal fun registerSkillResource(
    server: McpServerCore,
    descriptor: SkillDescriptor,
    name: String,
    description: String?,
    contentProvider: () -> String,
) {
    server.resourceRegistry.registerResource(
        uri = descriptor.resourceUri,
        name = name,
        description = description,
        mimeType = "text/markdown",
        contentProvider = contentProvider,
    )
    server.resourceRegistry.registerResource(
        uri = descriptor.legacyResourceUri,
        name = "$name (legacy)",
        description = buildLegacyDescription(description, descriptor),
        mimeType = "text/markdown",
        contentProvider = contentProvider,
    )
}

private fun buildLegacyDescription(description: String?, descriptor: SkillDescriptor): String? {
    val legacyHint = "Legacy alias for ${descriptor.id}"
    return when {
        description.isNullOrBlank() -> legacyHint
        else -> "$description\n\n$legacyHint"
    }
}
