/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.execution

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.registry.Registry
import com.jonnyzzz.intellij.mcp.server.ExecutionResultWithOutput
import com.jonnyzzz.intellij.mcp.server.ProgressReporter
import com.jonnyzzz.intellij.mcp.storage.ExecutionResult
import com.jonnyzzz.intellij.mcp.storage.ExecutionStatus
import com.jonnyzzz.intellij.mcp.storage.ExecutionStorage
import kotlinx.coroutines.*
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.time.Duration.Companion.seconds

inline val Project.scriptExecutor: ScriptExecutor get() = service()

/**
 * Executes Kotlin scripts using IntelliJ's script engine.
 *
 * Execution flow:
 * 1. Script is compiled and evaluated to capture execute { } lambdas
 * 2. Lambdas are executed in FIFO order inside a supervisorScope
 * 3. Any failure marks the whole execution as complete
 * 4. On timeout or cancellation, the Disposable is disposed and coroutine cancelled
 *
 * IMPORTANT: This executor runs the captured suspend block inside a supervisorScope.
 * The script code gets coroutine context implicitly - no runBlocking needed.
 */
@Service(Service.Level.PROJECT)
class ScriptExecutor(
    private val project: Project
) : Disposable {
    private val log = Logger.getInstance(ScriptExecutor::class.java)
    override fun dispose() = Unit

    private fun defaultTimeout(timeout: Int?): Int {
        return timeout ?: Registry.intValue("mcp.steroids.execution.timeout", 30)
    }

    /**
     * Execute a script with progress reporting and return the result with output.
     * This is a suspend function - it runs inside the caller's coroutine context.
     *
     * Fast failure: If the script engine is not available or compilation fails,
     * returns immediately with an error - no waiting.
     */
    suspend fun executeWithProgress(
        executionId: String,
        code: String,
        timeoutSeconds: Int? = null,
        progressReporter: ProgressReporter,
    ): ExecutionResultWithOutput {
        val result = try {
            executeImpl(executionId, code, defaultTimeout(timeoutSeconds), progressReporter)
        } catch (e: Throwable) {
            log.error("Unexpected error during execution $executionId", e)
            val sw = StringWriter()
            e.printStackTrace(PrintWriter(sw))
            ExecutionResultWithOutput(
                status = ExecutionStatus.ERROR,
                output = emptyList(),
                errorMessage = "Unexpected error: ${e.message}",
                executionId = executionId
            )
        }
        project.service<ExecutionStorage>().writeResult(executionId, result.toExecutionResult())
        return result
    }

    private suspend fun executeImpl(
        executionId: String,
        code: String,
        timeoutSeconds: Int,
        progressReporter: ProgressReporter,
    ): ExecutionResultWithOutput {
        val evalResult = project.service<CodeEvalManager>().evalCode(executionId, code)
        if (evalResult is EvalResult.Failed) {
            return ExecutionResultWithOutput(
                status = evalResult.errorResult.status,
                output = emptyList(),
                errorMessage = evalResult.errorResult.errorMessage,
                executionId = executionId
            )
        }

        log.info("Starting execution $executionId")

        // Create parent Disposable for this execution
        val executionDisposable = Disposer.newDisposable(this, "mcp-execution-$executionId")

        // Create context for this execution with progress support
        val context = McpScriptContextImpl(
            project = project,
            executionId = executionId,
            disposable = executionDisposable,
            progressReporter = progressReporter,
        )

        try {
            evalResult as EvalResult.Success
            val capturedBlocks = evalResult.result

            // Run captured blocks in FIFO order with timeout
            log.info("Running ${capturedBlocks.size} execute block(s) for $executionId with timeout ${timeoutSeconds}s")

            return coroutineScope {
                withContext(Dispatchers.IO) {
                    withTimeout(timeoutSeconds.seconds) {
                        runTheSubmittedCode(capturedBlocks, executionId, context)
                    }
                }
            }
        } catch (t: Throwable) {
            return when {
                t is TimeoutCancellationException -> {
                    log.info("Execution timed out for $executionId after ${timeoutSeconds}s")
                    ExecutionResultWithOutput(
                        status = ExecutionStatus.TIMEOUT,
                        output = context.getOutput(),
                        errorMessage = "Execution timed out after $timeoutSeconds seconds",
                        executionId = executionId
                    )
                }
                t is ProcessCanceledException || t is CancellationException -> {
                    log.info("Execution cancelled for $executionId")
                    ExecutionResultWithOutput(
                        status = ExecutionStatus.CANCELLED,
                        output = context.getOutput(),
                        errorMessage = "Execution was cancelled",
                        executionId = executionId
                    )
                }
                else -> {
                    log.error("Unexpected error during execution $executionId", t)
                    ExecutionResultWithOutput(
                        status = ExecutionStatus.ERROR,
                        output = context.getOutput(),
                        errorMessage = "Unexpected error: ${t.message}",
                        executionId = executionId
                    )
                }
            }
        } finally {
            Disposer.dispose(executionDisposable)
        }
    }

    private suspend fun runTheSubmittedCode(
        capturedBlocks: List<suspend McpScriptContext.() -> Unit>,
        executionId: String,
        context: McpScriptContextImpl
    ): ExecutionResultWithOutput {
        var index = 0
        return try {
            for (block in capturedBlocks) {
                yield()
                log.info("Executing block #${index + 1}/${capturedBlocks.size} for $executionId")
                context.progress("Executing block ${index + 1} of ${capturedBlocks.size}...")
                block(context)
                index++
            }
            ExecutionResultWithOutput(
                status = ExecutionStatus.SUCCESS,
                output = context.getOutput(),
                executionId = executionId
            )
        } catch (e: Throwable) {
            log.warn("Block #${index + 1} failed for $executionId: ${e.message}", e)
            val sw = StringWriter()
            e.printStackTrace(PrintWriter(sw))
            ExecutionResultWithOutput(
                status = ExecutionStatus.ERROR,
                output = context.getOutput(),
                errorMessage = "Runtime error in block #${index + 1}: ${e.message}",
                executionId = executionId
            )
        }
    }

    private fun ExecutionResultWithOutput.toExecutionResult() = ExecutionResult(
        status = status,
        errorMessage = errorMessage,
        exceptionInfo = null
    )
}
