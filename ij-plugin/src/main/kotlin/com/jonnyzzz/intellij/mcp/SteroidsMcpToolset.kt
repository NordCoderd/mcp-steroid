/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
@file:Suppress("FunctionName", "unused")

package com.jonnyzzz.intellij.mcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.openapi.components.service
import com.intellij.openapi.project.ProjectManager
import com.jonnyzzz.intellij.mcp.execution.ExecutionManager
import com.jonnyzzz.intellij.mcp.storage.ExecutionParams
import com.jonnyzzz.intellij.mcp.storage.ExecutionStatus
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.util.*

/**
 * MCP Toolset providing script execution capabilities.
 * Implements McpToolset and methods annotated with @McpTool are automatically registered.
 */
@Suppress("LocalVariableName")
class SteroidsMcpToolset : McpToolset {

    @McpTool("list_projects")
    @McpDescription("List all open projects in the IDE. Returns project names that can be used with execute_code.")
    suspend fun list_projects(): List<ProjectInfo> {
        return ProjectManager.getInstance().openProjects.map { project ->
            ProjectInfo(
                name = project.name,
                path = project.basePath ?: ""
            )
        }
    }

    @McpTool("execute_code")
    @McpDescription("""
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
        |    // Read PSI/VFS:
        |    val psiFile = readAction {
        |        PsiManager.getInstance(ctx.project).findFile(virtualFile)
        |    }
        |
        |    // Modify documents/PSI:
        |    writeAction {
        |        document.setText("new content")
        |    }
        |}
        |```
        |
        |For services:
        |```kotlin
        |// Project services:
        |val fileEditorManager = FileEditorManager.getInstance(ctx.project)
        |val psiManager = PsiManager.getInstance(ctx.project)
        |
        |// Application services:
        |val app = ApplicationManager.getApplication()
        |val vfsManager = VirtualFileManager.getInstance()
        |```
        |
        |Returns an execution_id that can be used with get_result to poll for results.
    """)
    suspend fun execute_code(
        @McpDescription("Project name (from list_projects)")
        project_name: String,
        @McpDescription("Kotlin code to execute - must use execute { ctx -> } pattern with suspend functions")
        code: String,
        @McpDescription("Execution timeout in seconds (default: 60)")
        timeout: Int = 60,
        @McpDescription("Show code in editor even on compilation error")
        show_review_on_error: Boolean = false
    ): ExecuteCodeResponse {
        val project = findProject(project_name)
            ?: return ExecuteCodeResponse(
                execution_id = "",
                status = ExecutionStatus.ERROR,
                error_message = "Project not found: $project_name"
            )

        val manager = project.service<ExecutionManager>()
        val params = ExecutionParams(timeout = timeout, showReviewOnError = show_review_on_error)

        val result = manager.submit(code, params)
        return ExecuteCodeResponse(
            execution_id = result.executionId,
            status = result.status
        )
    }

    @McpTool("get_result")
    @McpDescription("""
        |Get execution result by polling. Call this repeatedly until status is SUCCESS, ERROR, REJECTED, or CANCELLED.
        |
        |Status values:
        |- COMPILING: Script is being compiled
        |- PENDING_REVIEW: Waiting for user to approve/reject
        |- RUNNING: Script is executing
        |- SUCCESS: Completed successfully
        |- ERROR: Failed with error
        |- REJECTED: User rejected the code (error_message contains user's edits/comments)
        |- TIMEOUT: Execution or review timed out
        |- CANCELLED: Execution was cancelled
        |
        |Use offset to get only new output messages since last call for efficient polling.
    """)
    suspend fun get_result(
        @McpDescription("Execution ID from execute_code")
        execution_id: String,
        @McpDescription("Skip first N output messages (for incremental polling)")
        offset: Int = 0
    ): GetResultResponse {
        val project = findProjectByExecutionId(execution_id)
            ?: return GetResultResponse(
                execution_id = execution_id,
                status = ExecutionStatus.NOT_FOUND,
                output = emptyList(),
                error_message = "Execution not found"
            )

        val manager = project.service<ExecutionManager>()
        val result = manager.getResult(execution_id, offset)

        return GetResultResponse(
            execution_id = result.executionId,
            status = result.status,
            output = result.output.map { msg ->
                OutputMessageDto(
                    ts = msg.ts,
                    type = msg.type.name.lowercase(),
                    msg = msg.msg,
                    level = msg.level
                )
            },
            error_message = result.errorMessage,
            exception_info = result.exceptionInfo
        )
    }

    @McpTool("cancel_execution")
    @McpDescription("Cancel a running or pending execution")
    suspend fun cancel_execution(
        @McpDescription("Execution ID to cancel")
        execution_id: String
    ): CancelResponse {
        val project = findProjectByExecutionId(execution_id)
            ?: return CancelResponse(cancelled = false, message = "Execution not found")

        val manager = project.service<ExecutionManager>()
        val cancelled = manager.cancel(execution_id)

        return CancelResponse(
            cancelled = cancelled,
            message = if (cancelled) "Execution cancelled" else "Could not cancel (may have already completed)"
        )
    }

    private fun findProject(name: String): com.intellij.openapi.project.Project? {
        return ProjectManager.getInstance().openProjects.find { it.name == name }
    }

    private fun findProjectByExecutionId(executionId: String): com.intellij.openapi.project.Project? {
        // Execution ID format: {project-hash-3}-{timestamp}-{payload-hash}
        // We need to find the project by matching the hash prefix
        val projectHash = executionId.split("-").firstOrNull() ?: return null

        return ProjectManager.getInstance().openProjects.find { project ->
            val hash = computeProjectHash(project.name)
            hash == projectHash
        }
    }

    private fun computeProjectHash(projectName: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(projectName.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(hashBytes).take(3)
    }
}

// DTOs for tool responses

@Serializable
data class ProjectInfo(
    val name: String,
    val path: String
)

@Serializable
@Suppress("PropertyName")
data class ExecuteCodeResponse(
    val execution_id: String,
    val status: ExecutionStatus,
    val error_message: String? = null
)

@Serializable
@Suppress("PropertyName")
data class GetResultResponse(
    val execution_id: String,
    val status: ExecutionStatus,
    val output: List<OutputMessageDto>,
    val error_message: String? = null,
    val exception_info: String? = null
)

@Serializable
data class OutputMessageDto(
    val ts: Long,
    val type: String,
    val msg: String,
    val level: String? = null
)

@Serializable
data class CancelResponse(
    val cancelled: Boolean,
    val message: String
)
