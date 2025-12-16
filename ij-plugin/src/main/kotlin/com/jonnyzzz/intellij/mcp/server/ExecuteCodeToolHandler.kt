/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.server

import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.jonnyzzz.intellij.mcp.execution.ExecutionManager
import com.jonnyzzz.intellij.mcp.mcp.ContentItem
import com.jonnyzzz.intellij.mcp.mcp.McpServerCore
import com.jonnyzzz.intellij.mcp.mcp.ToolCallContext
import com.jonnyzzz.intellij.mcp.mcp.ToolCallResult
import com.jonnyzzz.intellij.mcp.storage.ExecutionParams
import com.jonnyzzz.intellij.mcp.storage.ExecutionStatus
import com.jonnyzzz.intellij.mcp.storage.ExecutionStorage
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.*

/**
 * Handler for the steroid_execute_code MCP tool.
 */
@Service(Service.Level.APP)
class ExecuteCodeToolHandler {
    private val log = Logger.getInstance(ExecuteCodeToolHandler::class.java)

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
                        put("description", "Write human readable reason for the execution, what you want to do and to get out of that")
                    }
                    putJsonObject("timeout") {
                        put("type", "integer")
                        put("description", "Execution timeout in seconds (default: 60)")
                    }
                }
                putJsonArray("required") {
                    add("project_name")
                    add("code")
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
        val timeout = args["timeout"]?.jsonPrimitive?.intOrNull ?: 60

        val project = findProject(projectName)
            ?: return errorResult("Project not found: $projectName")

        return coroutineScope {
            try {
                val manager = project.service<ExecutionManager>()

                val storage = project.service<ExecutionStorage>()
                val executionParams = ExecutionParams(timeout = timeout, showReviewOnError = false)
                val result = manager.executeWithProgress(code, executionParams, context.progress)

                // Associate execution with task
                val executionId = result.executionId
                if (executionId != null) {
                    storage.addExecutionToTask(taskId, executionId, projectName)
                }

                buildResponse(result, timeout, taskId, executionId)
            } catch (e: Throwable) {
                log.error("Error executing code", e)
                errorResult("Execution error: ${e.message}")
            }
        }
    }

    private fun buildResponse(
        result: ExecutionResultWithOutput,
        timeout: Int,
        taskId: String,
        executionId: String?
    ): ToolCallResult {
        return when (result.status) {
            ExecutionStatus.SUCCESS -> ToolCallResult(
                content = listOf(ContentItem.Text(text = buildSuccessText(result, taskId, executionId)))
            )
            ExecutionStatus.REJECTED -> errorResultWithContext(
                "Code rejected by user: ${result.errorMessage ?: "No reason provided"}",
                taskId, executionId
            )
            ExecutionStatus.TIMEOUT -> errorResultWithContext(
                "Execution timed out after $timeout seconds",
                taskId, executionId
            )
            ExecutionStatus.CANCELLED -> errorResultWithContext(
                "Execution was cancelled",
                taskId, executionId
            )
            else -> errorResultWithContext(
                result.errorMessage ?: "Execution failed with status: ${result.status}",
                taskId, executionId
            )
        }
    }

    private fun buildSuccessText(
        result: ExecutionResultWithOutput,
        taskId: String,
        executionId: String?
    ): String = buildString {
        appendLine("=== Execution Result ===")
        appendLine("status: SUCCESS")
        appendLine("task_id: $taskId")
        if (executionId != null) {
            appendLine("execution_id: $executionId")
        }
        appendLine()
        if (result.output.isNotEmpty()) {
            appendLine("=== Output ===")
            appendLine(result.output.joinToString("\n"))
        } else {
            appendLine("Execution completed successfully (no output).")
        }
        appendLine()
        appendLine("---")
        appendLine("Tip: Consider calling steroid_execute_feedback to rate this execution.")
    }

    private fun errorResultWithContext(message: String, taskId: String, executionId: String?): ToolCallResult {
        val fullMessage = buildString {
            appendLine("=== Execution Result ===")
            appendLine("status: ERROR")
            appendLine("task_id: $taskId")
            if (executionId != null) {
                appendLine("execution_id: $executionId")
            }
            appendLine()
            appendLine("=== Error ===")
            appendLine(message)
            appendLine()
            appendLine("---")
            appendLine("Tip: Consider calling steroid_execute_feedback to rate this execution.")
        }
        return ToolCallResult(
            content = listOf(ContentItem.Text(text = fullMessage)),
            isError = true
        )
    }

    private suspend fun findProject(name: String): Project? {
        return readAction {
             ProjectManager.getInstance().openProjects.find { it.name == name }
        }
    }

    private fun errorResult(message: String) = ToolCallResult(
        content = listOf(ContentItem.Text(text = message)),
        isError = true
    )

    private val toolDescription get() = """
             Execute Kotlin code in the IntelliJ-based IDE's runtime context with full access to IntelliJ APIs.
 
             You are working with the stateful API, everything you do here changes the state of the IDE,
             which is running exclusively for you. Use the IntelliJ-based IDE to help you looking
             at the project, where you are working. Prioritize using the IDE and this MCP where possible.
             
             You can do everything IntelliJ API allows you to do including, but not limited to
             - code search
             - code completion
             - code introspection, including methods, API, coroutines, reelection
             - errors and warnings highlighting
             - tests execution
             - automated code refactorings, such as rename or find usages
              
            IMPORTANT:
            1. All code must be written as suspend functions. Never use runBlocking.
            2. Provide a task_id to group related executions.
            
            The code must use the execute { } pattern:
            ```kotlin
            execute {
                println("Hello from IntelliJ!")
                waitForSmartMode()
                // Use any IntelliJ API here
            }
            ```
            
            Available context methods:
            - println(vararg values) - Print values separated by spaces
            - printJson(obj) - Print object as pretty JSON
            - progress(message) - Report progress (throttled to 1/sec)
            - logInfo/logWarn/logError(msg) - Log messages
            - waitForSmartMode() - Wait for indexing to complete
            - project - Access the IntelliJ Project
            - use Java/Kotlin reflection to find more!
            
            For read/write actions, use IntelliJ's coroutine-aware APIs:
            ```kotlin
            import com.intellij.openapi.application.readAction
            import com.intellij.openapi.application.writeAction
            
            execute {
                val psiFile = readAction {
                    PsiManager.getInstance(project).findFile(virtualFile)
                }
                writeAction {
                    document.setText("new content")
                }
            }
            ```
            
            Tip: After execution is success and you solved or give up solving the task
                 call the steroid_execute_feedback tool to log your feedback, so we can improve.
        """.trimIndent()
}
