/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.server

import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.ProjectManager.getInstance
import com.jonnyzzz.intellij.mcp.execution.CodeButcher
import com.jonnyzzz.intellij.mcp.execution.codeButcher
import com.jonnyzzz.intellij.mcp.mcp.ContentItem
import com.jonnyzzz.intellij.mcp.mcp.McpServerCore
import com.jonnyzzz.intellij.mcp.mcp.ToolCallParams
import com.jonnyzzz.intellij.mcp.mcp.ToolCallResult
import com.jonnyzzz.intellij.mcp.storage.ExecutionStorage
import kotlinx.serialization.json.*

/**
 * Handler for the steroid_execute_feedback MCP tool.
 *
 * Allows agents to provide feedback on execution results, including:
 * - Success rating (0.00 to 1.00)
 * - Explanation of the rating
 * - Association with a task_id
 */
@Service(Service.Level.APP)
class ExecuteFeedbackToolHandler : McpRegistrar {
    private val log = thisLogger()
    private val json = Json {
        prettyPrint = true
    }

    private val toolDescription get() = """
            Provide feedback on the result of a steroid_execute_code call.
            
            Use this tool to rate execution results and track what worked or didn't work.
            
            PARAMETERS:
            - project_name: The project where execution occurred
            - task_id: The same task_id you used in steroid_execute_code
            - execution_id: The execution_id returned in the steroid_execute_code result
            - success_rating: Rate from 0.00 (complete failure) to 1.00 (complete success)
              - 0.00-0.25: Complete failure, nothing worked
              - 0.25-0.50: Partial failure, some errors occurred
              - 0.50-0.75: Partial success, achieved some goals
              - 0.75-1.00: Success, achieved the intended goal
            - explanation: Describe what worked, what didn't, and what you'll try next
            - code (optional): The code snippet that was executed
            
            Feedback helps track execution history and identify patterns for improvement.
        """.trimIndent()

    override fun register(server: McpServerCore) {
        server.toolRegistry.registerTool(
            name = "steroid_execute_feedback",
            description = toolDescription,
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("project_name") {
                        put("type", "string")
                        put("description", "Project name (from steroid_list_projects)")
                    }
                    putJsonObject("task_id") {
                        put("type", "string")
                        put("description", "The task_id you used when calling steroid_execute_code")
                    }
                    putJsonObject("execution_id") {
                        put("type", "string")
                        put("description", "The execution_id returned from the most recent steroid_execute_code call for this task")
                    }
                    putJsonObject("success_rating") {
                        put("type", "number")
                        put("minimum", 0.0)
                        put("maximum", 1.0)
                        put("description", "Rate the success of the execution from 0.00 (complete failure) to 1.00 (complete success)")
                    }
                    putJsonObject("explanation") {
                        put("type", "string")
                        put("description", "Explain why you gave this rating. What worked? What didn't? What will you try next?")
                    }
                    putJsonObject("code") {
                        put("type", "string")
                        put("description", "Optional: The code snippet that was executed. Useful for tracking what code produced which results.")
                    }
                }
                putJsonArray("required") {
                    add("project_name")
                    add("task_id")
                    add("success_rating")
                    add("explanation")
                }
            }
        ) { context ->
            handle(context.params)
        }
    }

    private suspend fun handle(params: ToolCallParams): ToolCallResult {
        val args = params.arguments ?: return errorResult("Missing arguments")

        val projectName = args["project_name"]?.jsonPrimitive?.contentOrNull
            ?: return errorResult("Missing required parameter: project_name")
        val taskId = args["task_id"]?.jsonPrimitive?.contentOrNull
            ?: return errorResult("Missing required parameter: task_id")
        args["execution_id"]?.jsonPrimitive?.contentOrNull
            ?: return errorResult("Missing required parameter: execution_id")
        val successRating = args["success_rating"]?.jsonPrimitive?.doubleOrNull
            ?: return errorResult("Missing required parameter: success_rating")
        val explanation = args["explanation"]?.jsonPrimitive?.contentOrNull
        val code = args["code"]?.jsonPrimitive?.contentOrNull

        // Validate success_rating
        if (successRating !in 0.0..1.0) {
            return errorResult("success_rating must be between 0.00 and 1.00")
        }

        log.info("Feedback is submitted: " + json.encodeToString(params.rawArguments))

        val project = readAction {
            getInstance().openProjects.find { it.name == projectName }
        } ?: return errorResult("Project not found: $projectName")

        runCatching {
            val executionStorage = project.service<ExecutionStorage>()
            val executionId = executionStorage.writeExecutionFeedback(taskId = taskId, params)
            if (code != null) {
                executionStorage.writeCodeExecutionData(executionId, "script.kts", codeButcher.wrapWithImports(code))
            }

            if (explanation != null) {
                executionStorage.writeCodeExecutionData(executionId, "explanation.txt", explanation)
            }
        }

        return ToolCallResult(
            content = listOf(ContentItem.Text(text = "ACK!"))
        )
    }

    private fun errorResult(message: String) = ToolCallResult(
        content = listOf(ContentItem.Text(text = message)),
        isError = true
    )
}
