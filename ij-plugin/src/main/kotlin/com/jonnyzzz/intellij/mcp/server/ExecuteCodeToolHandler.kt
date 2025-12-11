/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.server

import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.jonnyzzz.intellij.mcp.execution.ExecutionManager
import com.jonnyzzz.intellij.mcp.storage.ExecutionParams
import com.jonnyzzz.intellij.mcp.storage.ExecutionStatus
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.*

/**
 * Handler for the steroid_execute_code MCP tool.
 */
@Service(Service.Level.APP)
class ExecuteCodeToolHandler {
    private val log = Logger.getInstance(ExecuteCodeToolHandler::class.java)

    fun register(server: Server) {
        server.addTool(
            name = "steroid_execute_code",
            description = TOOL_DESCRIPTION,
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("project_name") {
                        put("type", "string")
                        put("description", "Project name (from steroid_list_projects)")
                    }
                    putJsonObject("code") {
                        put("type", "string")
                        put("description", "Kotlin code to execute - must use execute { ctx -> } pattern")
                    }
                    putJsonObject("timeout") {
                        put("type", "integer")
                        put("description", "Execution timeout in seconds (default: 60)")
                    }
                },
                required = listOf("project_name", "code")
            )
        ) { request ->
            handle(server, request)
        }
    }

    private suspend fun handle(server: Server, request: CallToolRequest): CallToolResult {
        val args = request.params.arguments ?: return errorResult("Missing arguments")

        val projectName = args["project_name"]?.jsonPrimitive?.contentOrNull
            ?: return errorResult("Missing required parameter: project_name")
        val code = args["code"]?.jsonPrimitive?.contentOrNull
            ?: return errorResult("Missing required parameter: code")
        val timeout = args["timeout"]?.jsonPrimitive?.intOrNull ?: 60

        val project = findProject(projectName)
            ?: return errorResult("Project not found: $projectName")

        val progressToken = extractProgressToken(request)
        val progressReporter = progressToken?.let { ThrottledProgressReporter(server, progressToken) } ?: ProgressReporter.noOp()

        return coroutineScope {
            (progressReporter as? ThrottledProgressReporter)?.run { startThrottledSender() }

            try {
                val manager = project.service<ExecutionManager>()
                val params = ExecutionParams(timeout = timeout, showReviewOnError = false)
                val result = manager.executeWithProgress(code, params, progressReporter)

                buildResponse(result, timeout)
            } catch (e: Throwable) {
                log.error("Error executing code", e)
                errorResult("Execution error: ${e.message}")
            }
        }
    }

    private fun buildResponse(result: ExecutionResultWithOutput, timeout: Int): CallToolResult {
        return when (result.status) {
            ExecutionStatus.SUCCESS -> CallToolResult(
                content = listOf(TextContent(text = buildSuccessText(result)))
            )
            ExecutionStatus.REJECTED -> errorResult(
                "Code rejected by user: ${result.errorMessage ?: "No reason provided"}"
            )
            ExecutionStatus.TIMEOUT -> errorResult(
                "Execution timed out after $timeout seconds"
            )
            ExecutionStatus.CANCELLED -> errorResult(
                "Execution was cancelled"
            )
            else -> errorResult(
                result.errorMessage ?: "Execution failed with status: ${result.status}"
            )
        }
    }

    private fun buildSuccessText(result: ExecutionResultWithOutput): String {
        return if (result.output.isNotEmpty()) {
            result.output.joinToString("\n")
        } else {
            "Execution completed successfully."
        }
    }

    private suspend fun findProject(name: String): Project? {
        return readAction {
             ProjectManager.getInstance().openProjects.find { it.name == name }
        }
    }

    private fun extractProgressToken(request: CallToolRequest): ProgressToken? {
        val tokenElement = request.meta?.json?.get("progressToken") ?: return null
        return runCatching { McpJson.decodeFromJsonElement<ProgressToken>(tokenElement) }.getOrNull()
    }

    private fun errorResult(message: String) = CallToolResult(
        content = listOf(TextContent(text = message)),
        isError = true
    )

    companion object {
        private val TOOL_DESCRIPTION = """
            |Execute Kotlin code in the IDE's runtime context with full access to IntelliJ APIs.
            |
            |IMPORTANT: All code must be written as suspend functions. Never use runBlocking.
            |
            |The code must use the execute { ctx -> } pattern:
            |```kotlin
            |execute { ctx ->
            |    ctx.println("Hello from IntelliJ!")
            |    ctx.waitForSmartMode()
            |    // Use any IntelliJ API here
            |}
            |```
            |
            |Available context methods:
            |- ctx.println(vararg values) - Print values separated by spaces
            |- ctx.printJson(obj) - Print object as pretty JSON
            |- ctx.progress(message) - Report progress (throttled to 1/sec)
            |- ctx.logInfo/logWarn/logError(msg) - Log messages
            |- ctx.waitForSmartMode() - Wait for indexing to complete
            |- ctx.project - Access the IntelliJ Project
            |
            |For read/write actions, use IntelliJ's coroutine-aware APIs:
            |```kotlin
            |import com.intellij.openapi.application.readAction
            |import com.intellij.openapi.application.writeAction
            |
            |execute { ctx ->
            |    val psiFile = readAction {
            |        PsiManager.getInstance(ctx.project).findFile(virtualFile)
            |    }
            |    writeAction {
            |        document.setText("new content")
            |    }
            |}
            |```
            |
            |Progress notifications are sent automatically during execution.
            |The final result is returned when execution completes.
        """.trimMargin()
    }
}
