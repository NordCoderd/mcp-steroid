/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.server

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
