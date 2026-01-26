/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.server

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.ProjectManager.getInstance
import com.intellij.openapi.util.registry.Registry
import com.jonnyzzz.intellij.mcp.execution.ExecutionManager
import com.jonnyzzz.intellij.mcp.mcp.ContentItem
import com.jonnyzzz.intellij.mcp.mcp.McpServerCore
import com.jonnyzzz.intellij.mcp.mcp.ToolCallContext
import com.jonnyzzz.intellij.mcp.mcp.ToolCallResult
import kotlinx.serialization.json.*

data class ExecCodeParams(
    val taskId: String,
    val code: String,
    val reason: String,
    val timeout: Int,
    //TODO: move that away from here, allow changes only via the McpScriptContext::doNotCancelOnModalityStateChange
    /** If true, cancel execution when a modal dialog appears and return a screenshot. Default true. */
    val cancelOnModal: Boolean = true,

    val rawParams: JsonObject,
)

/**
 * Handler for the steroid_execute_code MCP tool.
 */
class ExecuteCodeToolHandler : McpRegistrar {
    private val toolDescription get() = """
             Execute Kotlin code in the IntelliJ-based IDE's runtime context with full access to IntelliJ APIs.
             
             IntelliJ IDEA and this MCP tool is a stateful API, consider polling for results instead
             of waiting for the execution to finish. This allows you to continue working while the code
             executes, and you can check the results later.

             You are working with the stateful API, everything you do here changes the state of the IDE,
             which is running exclusively for you. Use the IntelliJ-based IDE to help you looking
             at the project, where you are working. Prioritize using the IDE and this MCP where possible.

             NEW TO INTELLIJ APIS? Start here: Run 'resources/list' on this MCP Server to see all possible
             resources that you need. It includes [Debugger](intellij://debugger/overview), 
             [API Patterns](intellij://skill/intellij-api-poweruser-guide), [Basics](intellij://ide/overview),
             [LSP Operations](intellij://lsp/overview), Running, Staring tests, Debugging, and so on.

             LEARNING NOTE: Writing working code may require several attempts - this is normal! The IntelliJ API
             is vast and powerful. Keep trying - each attempt teaches you more. Use printException() for errors.
             Start sub-agent where possible to delegate these iterations.

             BEST PRACTICE: Delegate to SUB-AGENT for code execution:
             - Sub-agent can retry multiple times without polluting main agent context
             - Errors and debugging stay isolated from main conversation
             - Provide detailed 'reason' so sub-agent understands the intent
             - Sub-agent can iterate on fixes without context rot in main agent

             This is similar to LSP tools but uses IntelliJ's native APIs, offering deeper code understanding
             and more features (refactorings, inspections, full project model). See intellij://

             You can do everything IntelliJ API allows you to do including, but not limited to
             - code search
             - code completion
             - code introspection, including methods, API, coroutines, reflection
             - errors and warnings highlighting
             - tests execution
             - automated code refactorings, such as rename or find usages

             CRITICAL RULES:
             1. The execute { } block is a SUSPEND Kotlin function - prefer Kotlin coroutine APIs over blocking Java APIs             
             2. Never use runBlocking - you're already in a coroutine context
             3. Use readAction { } for PSI/VFS reads, writeAction { } for modifications
             4. Call waitForSmartMode() before accessing indices or PSI

             Script structure:
             ```kotlin
             // Imports go HERE, outside execute block
             import com.intellij.psi.PsiManager
 
             execute {
                 // This is a suspend function - use coroutine APIs!
                 waitForSmartMode()
                 // Use built-in readAction helper - no import needed!
                 val psiFile = readAction {
                     PsiManager.getInstance(project).findFile(virtualFile)
                 }
                 println(psiFile?.name)
             }
             ```

            Available in execute { } scope:
            - just use Java or Kotlin reflection to inspect more
            - project: Project - the IntelliJ Project instance
            - println(vararg values) - output separated by spaces
            - printJson(obj) - pretty-print as JSON
            - printException(msg, throwable) - error with stack trace (recommended!)
            - progress(message) - report progress (throttled to 1/sec)
            - waitForSmartMode() - suspend until indexing completes
            - disposable - for resource cleanup

            Built-in helpers (NO IMPORTS NEEDED):
            - readAction { } - execute under read lock
            - writeAction { } - execute under write lock
            - smartReadAction { } - waitForSmartMode() + readAction in one call
            - projectScope() - GlobalSearchScope for project files
            - allScope() - GlobalSearchScope for project + libraries
            - findFile(path) - find VirtualFile by absolute path
            - findPsiFile(path) - find PsiFile by absolute path
            - findProjectFile(relativePath) - find file relative to project
            - findProjectPsiFile(relativePath) - find PsiFile relative to project
            - runInspectionsDirectly(file) - run inspections bypassing daemon (works without window focus)

            IntelliJ API Version: ${ApplicationInfo.getInstance().apiVersion}

            📚 TIP: Read the "IntelliJ API Power User Guide" resource for patterns and examples!
            After execution, call steroid_execute_feedback to log your feedback.
         """.trim().lines().joinToString("\n") { it.trim() }

    override fun register(server: McpServerCore) {
        server.toolRegistry.registerTool(
            name = "steroid_execute_code",
            description = toolDescription,
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("project_name") {
                        put("type", "string")
                        put("description", "Project name (from steroid_list_projects)")
                    }
                    putJsonObject("code") {
                        put("type", "string")
                        put("description", "Kotlin code to execute - must use execute { } with McpScriptContext as the receiver")
                    }
                    putJsonObject("task_id") {
                        put("type", "string")
                        put("description", "Your task identifier to group related executions. Use the same task_id for all execute_code calls that are part of the same task, and when providing feedback via steroid_execute_feedback.")
                    }
                    putJsonObject("reason") {
                        put("type", "string")
                        put("description", "IMPORTANT: On your FIRST call, provide the FULL TASK DESCRIPTION from the user - what they originally asked you to do. On subsequent calls, describe what this specific execution aims to achieve. This helps track progress and understand context.")
                    }
                    putJsonObject("timeout") {
                        put("type", "integer")
                        put("description", "Execution timeout in seconds (default: 60)")
                    }
                    putJsonObject("required_plugins") {
                        put("type", "array")
                        putJsonObject("items") {
                            put("type", "string")
                        }
                        put(
                            "description",
                            "Optional list of required plugin IDs (example: com.intellij.database). " +
                                "Use steroid_capabilities to list installed plugins."
                        )
                    }
                }
                putJsonArray("required") {
                    add("project_name")
                    add("code")
                    add("reason")
                    add("task_id")
                }
            },
            ::handle
        )
    }

    private suspend fun handle(context: ToolCallContext): ToolCallResult {
        val params = context.params
        val args = params.arguments ?: return errorResult("Missing arguments")

        val projectName = args["project_name"]?.jsonPrimitive?.contentOrNull
            ?: return errorResult("Missing required parameter: project_name")
        val code = args["code"]?.jsonPrimitive?.contentOrNull
            ?: return errorResult("Missing required parameter: code")
        val taskId = args["task_id"]?.jsonPrimitive?.contentOrNull
            ?: return errorResult("Missing required parameter: task_id")
        val reason = args["reason"]?.jsonPrimitive?.contentOrNull
        val timeout = args["timeout"]?.jsonPrimitive?.intOrNull ?: Registry.intValue("mcp.steroids.execution.timeout", 60)
        val requiredPlugins = args["required_plugins"]
            ?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?.filter { it.isNotBlank() }
            ?: emptyList()

        val missingPlugins = findMissingPlugins(requiredPlugins)
        if (missingPlugins.isNotEmpty()) {
            return errorResult(
                "Missing required plugins: ${missingPlugins.joinToString(", ")}. " +
                    "Use steroid_capabilities to list installed plugins."
            )
        }

        val project = readAction {
            getInstance().openProjects.find { it.name == projectName }
        }
            ?: return errorResult("Project not found: $projectName")

        val execCodeParams = ExecCodeParams(
            taskId = taskId,
            code = code,
            reason = reason ?: "No reason provided",
            timeout = timeout,
            rawParams = params.arguments
        )

        return project
            .service<ExecutionManager>()
            .executeWithProgress(execCodeParams)
    }

    private fun errorResult(message: String) = ToolCallResult(
        content = listOf(ContentItem.Text(text = "ERROR: $message")),
        isError = true
    )

    private fun findMissingPlugins(requiredPlugins: List<String>): List<String> {
        if (requiredPlugins.isEmpty()) return emptyList()
        return requiredPlugins.filter { pluginId ->
            val resolvedId = PluginId.getId(pluginId)
            val resolved = PluginManagerCore.getPlugin(resolvedId)
            resolved == null || !PluginManagerCore.isLoaded(resolvedId)
        }
    }
}
