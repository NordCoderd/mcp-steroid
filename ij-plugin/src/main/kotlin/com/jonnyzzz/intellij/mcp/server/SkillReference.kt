/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.server

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service

/**
 * Application service providing MCP resource references and brief tips for LLM agents.
 * Keeps responses small by pointing to MCP resources.
 *
 * Usage:
 * ```kotlin
 * val skillRef = service<SkillReference>()
 * val url = skillRef.skillUrl
 * val hint = skillRef.errorHint(errorMessage)
 * ```
 */
@Service(Service.Level.APP)
class SkillReference {

    private val mcpServer: SteroidsMcpServer
        get() = SteroidsMcpServer.getInstance()

    /**
     * Returns the SKILL.md URL from the MCP server.
     */
    val skillUrl: String
        get() = mcpServer.skillUrl

    /**
     * Brief reminder of critical rules - included in error responses.
     */
    val criticalRules = buildString {
        appendLine("CRITICAL RULES:")
        appendLine("1. Imports MUST be OUTSIDE execute {} block")
        appendLine("2. Call waitForSmartMode() before PSI operations")
        appendLine("3. Use readAction {} for PSI/VFS reads")
        append("4. Never use runBlocking - you're in a coroutine")
    }

    private val resourceHint: String = buildString {
        append("📚 TIP: Browse MCP resources (resources/list). ")
        append("Start with intellij://skill/intellij-api-poweruser-guide, ")
        append("intellij://ide/overview, ")
        append("intellij://lsp/overview")
    }

    /**
     * Returns a tip message with MCP resource hints.
     */
    fun tipWithUrl(context: String = ""): String {
        return buildString {
            if (context.isNotEmpty()) {
                appendLine(context)
                appendLine()
            }
            appendLine(resourceHint)
            appendLine("   Use resources/read to fetch patterns on PSI, refactoring, code completion, and more.")
        }
    }

    /**
     * Returns error-specific hint with URL.
     */
    fun errorHint(errorMessage: String): String {
        val hint = when {
            errorMessage.contains("Unresolved reference") ->
                "TIP: Add imports OUTSIDE the execute {} block, not inside."

            errorMessage.contains("Dumb mode") || errorMessage.contains("smart mode") ->
                "TIP: Call waitForSmartMode() before accessing PSI or indices."

            errorMessage.contains("Read access") || errorMessage.contains("Write access") ->
                "TIP: Wrap PSI/VFS access in readAction {} or writeAction {}."

            errorMessage.contains("runBlocking") ->
                "TIP: Never use runBlocking - execute {} is already a suspend function."

            // Note: "Service is dying" errors are now handled automatically with retry logic
            // in CodeEvalManager.kt. If this error reaches here, it means all retries failed.

            else -> criticalRules
        }

        return buildString {
            appendLine(hint)
            appendLine()
            appendLine(resourceHint)
        }
    }

    /**
     * Success message with documentation link.
     */
    val successFooter: String
        get() = resourceHint

    companion object {
        fun getInstance(): SkillReference = service()
    }
}
