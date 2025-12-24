/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.server

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.ProjectManager.getInstance
import com.intellij.openapi.util.registry.Registry
import com.jonnyzzz.intellij.mcp.execution.ExecutionManager
import com.jonnyzzz.intellij.mcp.mcp.ContentItem
import com.jonnyzzz.intellij.mcp.mcp.McpServerCore
import com.jonnyzzz.intellij.mcp.mcp.ToolCallContext
import com.jonnyzzz.intellij.mcp.mcp.ToolCallResult
import com.jonnyzzz.intellij.mcp.mcp.builder
import com.jonnyzzz.intellij.mcp.storage.ExecutionId
import com.jonnyzzz.intellij.mcp.storage.executionStorage
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

data class ExecCodeParams(
    val taskId: String,
    val code: String,
    val reason: String,
    val timeout: Int,

    val rawParams: JsonObject,
)

/**
 * Handler for the steroid_execute_code MCP tool.
 */
@Service(Service.Level.APP)
class ExecuteCodeToolHandler {
    private val log = Logger.getInstance(ExecuteCodeToolHandler::class.java)

    private val toolDescription get() = """
             Execute Kotlin code in the IntelliJ-based IDE's runtime context with full access to IntelliJ APIs.

             You are working with the stateful API, everything you do here changes the state of the IDE,
             which is running exclusively for you. Use the IntelliJ-based IDE to help you looking
             at the project, where you are working. Prioritize using the IDE and this MCP where possible.

             LEARNING NOTE: Writing working code may require several attempts - this is normal! The IntelliJ API
             is vast and powerful. Keep trying - each attempt teaches you more. Use printException() for errors.

             This is similar to LSP tools but uses IntelliJ's native APIs, offering deeper code understanding
             and more features (refactorings, inspections, full project model).

             You can do everything IntelliJ API allows you to do including, but not limited to
             - code search
             - code completion
             - code introspection, including methods, API, coroutines, reflection
             - errors and warnings highlighting
             - tests execution
             - automated code refactorings, such as rename or find usages

            CRITICAL RULES:
            1. The execute { } block is a SUSPEND function - prefer Kotlin coroutine APIs over blocking Java APIs
            2. IMPORTS MUST be OUTSIDE execute { } - place imports at the top of the script
            3. Never use runBlocking - you're already in a coroutine context
            4. Use readAction { } for PSI/VFS reads, writeAction { } for modifications
            5. Call waitForSmartMode() before accessing indices or PSI

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

            IntelliJ API Version: ${ApplicationInfo.getInstance().apiVersion}

            📚 TIP: Read the "IntelliJ API Power User Guide" resource for patterns and examples!
            After execution, call steroid_execute_feedback to log your feedback.
        """.trimIndent()

    fun register(server: McpServerCore) {
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
}
