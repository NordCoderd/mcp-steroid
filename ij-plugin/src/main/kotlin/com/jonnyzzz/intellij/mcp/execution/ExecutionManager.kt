/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.execution

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.jonnyzzz.intellij.mcp.review.ReviewManager
import com.jonnyzzz.intellij.mcp.review.ReviewResult
import com.jonnyzzz.intellij.mcp.server.ExecutionResultWithOutput
import com.jonnyzzz.intellij.mcp.server.ProgressReporter
import com.jonnyzzz.intellij.mcp.storage.ExecutionParams
import com.jonnyzzz.intellij.mcp.storage.ExecutionResult
import com.jonnyzzz.intellij.mcp.storage.ExecutionStatus
import com.jonnyzzz.intellij.mcp.storage.ExecutionStorage
import kotlinx.coroutines.*
import java.io.PrintWriter
import java.io.StringWriter

/**
 * State of an execution.
 */
sealed class ExecutionState {
    data object Submitted : ExecutionState()
    data object PendingReview : ExecutionState()
    data object Running : ExecutionState()
    data class Completed(val result: ExecutionResult) : ExecutionState()
    data object Cancelled : ExecutionState()
}

/**
 * Manages script executions for a project.
 * Executions run sequentially in a dedicated coroutine scope.
 */
@Service(Service.Level.PROJECT)
class ExecutionManager(
    private val project: Project,
) : Disposable {
    private val log = Logger.getInstance(ExecutionManager::class.java)

    override fun dispose() = Unit

    /**
     * Execute code within the MCP request scope.
     * Returns the final result with all output collected.
     * Progress is reported via the provided reporter (throttled to 1/sec).
     */
    suspend fun executeWithProgress(
        code: String,
        params: ExecutionParams,
        progressReporter: ProgressReporter,
    ): ExecutionResultWithOutput {
        val executionId = project.service<ExecutionStorage>().generateExecutionId(code, params)
        log.info("Starting execution $executionId")

        project.service<ExecutionStorage>().createExecution(executionId, code, params)
        progressReporter.report("Compiling code...")

        return withContext(CoroutineName("mcp-steroid-$executionId")) {
            executeInternal(executionId, code, params, progressReporter)
        }
    }

    private suspend fun executeInternal(
        executionId: String,
        code: String,
        params: ExecutionParams,
        progressReporter: ProgressReporter,
    ): ExecutionResultWithOutput {
        // Handle review if needed
        progressReporter.report("Waiting for code review...")

        when (val reviewResult = project.service<ReviewManager>().requestReview(executionId, code)) {
            is ReviewResult.Approved -> {
                // Continue with execution
                progressReporter.report("Code approved, executing...")
            }

            is ReviewResult.Rejected -> {
                val message = buildRejectionMessage(reviewResult)
                project.service<ExecutionStorage>().writeResult(
                    executionId, ExecutionResult(
                        status = ExecutionStatus.REJECTED,
                        errorMessage = message
                    )
                )
                return ExecutionResultWithOutput(
                    status = ExecutionStatus.REJECTED,
                    output = emptyList(),
                    errorMessage = message
                )
            }

            is ReviewResult.Timeout -> {
                project.service<ExecutionStorage>().writeResult(
                    executionId, ExecutionResult(
                        status = ExecutionStatus.TIMEOUT,
                        errorMessage = "Review timed out"
                    )
                )
                return ExecutionResultWithOutput(
                    status = ExecutionStatus.TIMEOUT,
                    output = emptyList(),
                    errorMessage = "Review timed out"
                )
            }
        }

        yield()

        // Run execution with progress reporting
        val result = project.scriptExecutor.executeWithProgress(
            executionId,
            code,
            params.timeout,
            progressReporter
        )
        log.info("Execution $executionId completed with status ${result.status}")
        return result
    }

    /**
     * Build a rejection message that includes user feedback.
     */
    private fun buildRejectionMessage(rejection: ReviewResult.Rejected): String = buildString {
        appendLine("Code was rejected by user during review.")
        appendLine()

        if (rejection.codeWasModified) {
            appendLine("The user edited the code. Their changes may contain comments or corrections.")
            appendLine()
            appendLine("=== USER'S EDITED VERSION ===")
            appendLine(rejection.editedCode)
            appendLine()

            if (rejection.diff != null) {
                appendLine("=== DIFF (changes made by user) ===")
                appendLine(rejection.diff)
            }
        } else {
            appendLine("The code was rejected without modifications.")
            appendLine("Please review your approach and try a different solution.")
        }
    }
}
