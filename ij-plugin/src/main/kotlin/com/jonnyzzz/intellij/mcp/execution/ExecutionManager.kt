/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.execution

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.registry.Registry
import com.jonnyzzz.intellij.mcp.review.ReviewManager
import com.jonnyzzz.intellij.mcp.review.ReviewResult
import com.jonnyzzz.intellij.mcp.storage.ExecutionParams
import com.jonnyzzz.intellij.mcp.storage.ExecutionResult
import com.jonnyzzz.intellij.mcp.storage.ExecutionStatus
import com.jonnyzzz.intellij.mcp.storage.ExecutionStorage
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * State of an execution.
 */
sealed class ExecutionState {
    data object Compiling : ExecutionState()
    data object PendingReview : ExecutionState()
    data object Running : ExecutionState()
    data class Completed(val result: ExecutionResult) : ExecutionState()
    data object Cancelled : ExecutionState()

    fun toStatus(): ExecutionStatus = when (this) {
        is Compiling -> ExecutionStatus.COMPILING
        is PendingReview -> ExecutionStatus.PENDING_REVIEW
        is Running -> ExecutionStatus.RUNNING
        is Completed -> result.status
        is Cancelled -> ExecutionStatus.CANCELLED
    }
}

/**
 * Manages script executions for a project.
 * Executions run sequentially in a dedicated coroutine scope.
 */
@Service(Service.Level.PROJECT)
class ExecutionManager(
    private val project: Project,
    private val coroutineScope: CoroutineScope
) {
    private val log = Logger.getInstance(ExecutionManager::class.java)

    // Sequential execution scope
    @OptIn(ExperimentalCoroutinesApi::class)
    private val executionScope = CoroutineScope(
        coroutineScope.coroutineContext +
                SupervisorJob() +
                Dispatchers.Default.limitedParallelism(1)
    )

    private val executions = ConcurrentHashMap<String, ExecutionState>()
    private val executionJobs = ConcurrentHashMap<String, Job>()

    private val storage: ExecutionStorage
        get() = project.service()

    private val reviewManager: ReviewManager
        get() = project.service()

    /**
     * Submit code for execution.
     * Returns execution ID immediately.
     * Compilation is synchronous, execution is async.
     */
    suspend fun submit(code: String, params: ExecutionParams): SubmitResult {
        val executionId = storage.generateExecutionId(code, params)
        log.info("Submitting execution $executionId")

        // Create execution record - this saves script.kts immediately
        storage.createExecution(executionId, code, params)
        executions[executionId] = ExecutionState.Compiling

        // Check review mode
        val reviewMode = try {
            Registry.stringValue("mcp.steroids.review.mode")
        } catch (e: Exception) {
            "ALWAYS"
        }

        // Start execution in background
        val job = executionScope.launch {
            try {
                // Handle review if needed
                if (reviewMode == "ALWAYS") {
                    executions[executionId] = ExecutionState.PendingReview
                    when (val reviewResult = reviewManager.requestReview(executionId, code)) {
                        is ReviewResult.Approved -> {
                            // Continue with execution
                        }
                        is ReviewResult.Rejected -> {
                            // Build rejection message with user feedback
                            val message = buildRejectionMessage(reviewResult)
                            storage.writeResult(executionId, ExecutionResult(
                                status = ExecutionStatus.REJECTED,
                                errorMessage = message
                            ))
                            executions[executionId] = ExecutionState.Completed(
                                ExecutionResult(status = ExecutionStatus.REJECTED, errorMessage = message)
                            )
                            return@launch
                        }
                        is ReviewResult.Timeout -> {
                            storage.writeResult(executionId, ExecutionResult(
                                status = ExecutionStatus.TIMEOUT,
                                errorMessage = "Review timed out"
                            ))
                            executions[executionId] = ExecutionState.Completed(
                                ExecutionResult(status = ExecutionStatus.TIMEOUT, errorMessage = "Review timed out")
                            )
                            return@launch
                        }
                    }
                }

                // Run execution
                executions[executionId] = ExecutionState.Running

                val executor = ScriptExecutor(project, storage)
                val timeout = try {
                    Registry.intValue("mcp.steroids.execution.timeout")
                } catch (e: Exception) {
                    params.timeout
                }

                val result = executor.execute(executionId, code, timeout)

                storage.writeResult(executionId, result)
                executions[executionId] = ExecutionState.Completed(result)

                log.info("Execution $executionId completed with status ${result.status}")

            } catch (t: Throwable) {
                if (t is CancellationException || t is ProcessCanceledException) {
                    log.info("Execution $executionId was cancelled")
                    storage.writeResult(executionId, ExecutionResult(
                        status = ExecutionStatus.CANCELLED,
                        errorMessage = "Execution was cancelled"
                    ))
                    executions[executionId] = ExecutionState.Cancelled
                    throw t
                }

                log.error("Execution $executionId failed unexpectedly", t)
                val result = ExecutionResult(
                    status = ExecutionStatus.ERROR,
                    errorMessage = t.message,
                    exceptionInfo = t.stackTraceToString()
                )
                storage.writeResult(executionId, result)
                executions[executionId] = ExecutionState.Completed(result)
            } finally {
                executionJobs.remove(executionId)
            }
        }

        executionJobs[executionId] = job

        // Return initial status
        val status = executions[executionId]?.toStatus() ?: ExecutionStatus.COMPILING
        return SubmitResult(executionId, status)
    }

    /**
     * Build a rejection message that includes user feedback.
     * If the user edited the code, include the diff.
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

    /**
     * Get current status of an execution.
     */
    fun getStatus(executionId: String): ExecutionStatus {
        // Check in-memory state first
        val state = executions[executionId]
        if (state != null) {
            return state.toStatus()
        }

        // Check persisted result
        val result = storage.readResult(executionId)
        if (result != null) {
            return result.status
        }

        // Check if execution exists at all
        if (storage.exists(executionId)) {
            return ExecutionStatus.NOT_FOUND // Exists but no state - shouldn't happen
        }

        return ExecutionStatus.NOT_FOUND
    }

    /**
     * Cancel a running or pending execution.
     */
    fun cancel(executionId: String): Boolean {
        val job = executionJobs[executionId]
        if (job != null && job.isActive) {
            job.cancel()
            reviewManager.cancel(executionId)
            return true
        }
        return false
    }

    /**
     * Get execution result and output.
     */
    fun getResult(executionId: String, offset: Int = 0): GetResultResponse {
        val status = getStatus(executionId)
        val output = storage.readOutput(executionId, offset)
        val result = storage.readResult(executionId)

        return GetResultResponse(
            executionId = executionId,
            status = status,
            output = output,
            errorMessage = result?.errorMessage,
            exceptionInfo = result?.exceptionInfo
        )
    }
}

data class SubmitResult(
    val executionId: String,
    val status: ExecutionStatus
)

data class GetResultResponse(
    val executionId: String,
    val status: ExecutionStatus,
    val output: List<com.jonnyzzz.intellij.mcp.storage.OutputMessage>,
    val errorMessage: String? = null,
    val exceptionInfo: String? = null
)
