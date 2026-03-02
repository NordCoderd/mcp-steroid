/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.jonnyzzz.mcpSteroid.prompts.generated.debugger.OverviewPromptArticle as DebuggerOverview
import com.jonnyzzz.mcpSteroid.prompts.generated.ide.OverviewPromptArticle as IdeOverview
import com.jonnyzzz.mcpSteroid.prompts.generated.lsp.OverviewPromptArticle as LspOverview
import com.jonnyzzz.mcpSteroid.prompts.generated.prompt.SkillPromptArticle

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

    private val resourceHint: String = buildString {
        appendLine("TIP: Browse MCP resources for examples and patterns:")
        appendLine("   ${SkillPromptArticle().uri} - API patterns")
        appendLine("   ${DebuggerOverview().uri} - Debugger workflows")
        appendLine("   ${IdeOverview().uri} - Refactorings & inspections")
        append("   ${LspOverview().uri} - Code navigation & completion")
    }

    /**
     * Returns error-specific hint with URL.
     */
    fun errorHint(errorMessage: String): String {
        val hint = when {
            errorMessage.contains("unresolved label", ignoreCase = true) &&
                (errorMessage.contains("executeSteroidCode", ignoreCase = true) ||
                    errorMessage.contains("executeSuspend", ignoreCase = true)) ->
                "TIP: Do not use return@executeSteroidCode or return@executeSuspend. Your script is already the suspend function body. " +
                    "Use plain return (or return@withContext inside withContext blocks)."

            errorMessage.contains("ApplicationConfiguration", ignoreCase = true) &&
                errorMessage.contains("constructor", ignoreCase = true) &&
                errorMessage.contains("protected", ignoreCase = true) ->
                "TIP: Do not construct ApplicationConfiguration(...) directly. Reuse an existing run configuration " +
                    "or create one via RunManager.createConfiguration(...) using ApplicationConfigurationType factory."

            errorMessage.contains("actual type is 'PsiFile', but 'VirtualFile' was expected", ignoreCase = true) ||
                (errorMessage.contains("unresolved reference 'path'", ignoreCase = true) &&
                    errorMessage.contains("PsiFile", ignoreCase = true)) ||
                (errorMessage.contains("unresolved reference 'url'", ignoreCase = true) &&
                    errorMessage.contains("PsiFile", ignoreCase = true)) ->
                "TIP: Use VirtualFile-based APIs. Find files with FilenameIndex.getVirtualFilesByName(...) " +
                    "or convert psiFile.virtualFile before calling FileDocumentManager/getting path/url."

            errorMessage.contains("unresolved reference 'findFiles'", ignoreCase = true) ||
                errorMessage.contains("unresolved reference 'contentsToByteArray'", ignoreCase = true) ->
                "TIP: findFiles(...) is not available in script context. Use findProjectFiles(globPattern) " +
                    "or FilenameIndex.getVirtualFilesByName(...), then read content with VfsUtilCore.loadText(virtualFile) " +
                    "or readAction { psiFile.text }."

            errorMessage.contains("Unresolved reference", ignoreCase = true) ->
                "TIP: Add missing top-level imports if needed. Imports are optional but must appear before code statements."

            errorMessage.contains("Dumb mode") || errorMessage.contains("smart mode") ->
                "TIP: waitForSmartMode() runs before the script, but call it again after triggering indexing."

            errorMessage.contains("Read access") || errorMessage.contains("Write access") ->
                "TIP: Wrap PSI/VFS access in readAction {} or writeAction {}."

            errorMessage.contains("EDT", ignoreCase = true) ->
                "TIP: This operation requires EDT. Use: withContext(Dispatchers.EDT) { }"

            errorMessage.contains("JavaLineBreakpointProperties", ignoreCase = true) || errorMessage.contains("\"props\" is null") ->
                "TIP: Use XDebuggerUtil.toggleLineBreakpoint() instead of breakpointManager.addLineBreakpoint() with null properties. See ${DebuggerOverview().uri}"

            errorMessage.contains("breakpoint", ignoreCase = true) || errorMessage.contains("debug", ignoreCase = true) ->
                "TIP: For debugger help, see ${DebuggerOverview().uri} resource (run resources/list)"

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
