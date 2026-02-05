/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

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
    @Suppress("unused")
    val skillUrl: String
        get() = mcpServer.skillUrl

    /**
     * Brief reminder of critical rules - included in error responses.
     */
    val criticalRules = buildString {
        appendLine("CRITICAL RULES:")
        appendLine("1. Code runs as a suspend script body")
        appendLine("2. waitForSmartMode() runs automatically before your script; call it again only if you trigger indexing")
        appendLine("3. Use readAction {} for PSI/VFS reads")
        append("4. Never use runBlocking - you're already in a coroutine context")
    }

    // Resource hints are static since SkillReference is accessed early in startup
    // before McpServerCore is fully initialized. These URIs are stable entrypoints.
    private val resourceHint: String = buildString {
        appendLine("TIP: Browse MCP resources for examples and patterns:")
        appendLine("   mcp-steroid://skill/skill - API patterns")
        appendLine("   mcp-steroid://debugger/overview - Debugger workflows")
        appendLine("   mcp-steroid://ide/overview - Refactorings & inspections")
        append("   mcp-steroid://lsp/overview - Code navigation & completion")
    }

    /**
     * Returns error-specific hint with URL.
     */
    fun errorHint(errorMessage: String): String {
        val hint = when {
            errorMessage.contains("Unresolved reference") ->
                "TIP: Add missing top-level imports if needed. Imports are optional but must appear before code statements."

            errorMessage.contains("Dumb mode") || errorMessage.contains("smart mode") ->
                "TIP: waitForSmartMode() runs before the script, but call it again after triggering indexing."

            errorMessage.contains("Read access") || errorMessage.contains("Write access") ->
                "TIP: Wrap PSI/VFS access in readAction {} or writeAction {}."

            errorMessage.contains("EDT", ignoreCase = true) ->
                "TIP: This operation requires EDT. Use: withContext(Dispatchers.EDT) { }"

            errorMessage.contains("JavaLineBreakpointProperties", ignoreCase = true) || errorMessage.contains("\"props\" is null") ->
                "TIP: Use XDebuggerUtil.toggleLineBreakpoint() instead of breakpointManager.addLineBreakpoint() with null properties. See mcp-steroid://debugger/overview"

            errorMessage.contains("breakpoint", ignoreCase = true) || errorMessage.contains("debug", ignoreCase = true) ->
                "TIP: For debugger help, see mcp-steroid://debugger/overview resource (run resources/list)"

            errorMessage.contains("runBlocking") ->
                "TIP: Never use runBlocking - the script body already runs in a coroutine context."

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
     * Success message with a documentation link.
     */
    val successFooter: String
        get() = resourceHint

    companion object {
        fun getInstance(): SkillReference = service()
    }
}
