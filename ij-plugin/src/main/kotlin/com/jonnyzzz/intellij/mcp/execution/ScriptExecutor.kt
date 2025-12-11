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
import com.jonnyzzz.intellij.mcp.storage.ExecutionResult
import com.jonnyzzz.intellij.mcp.storage.ExecutionStatus
import com.jonnyzzz.intellij.mcp.storage.ExecutionStorage
import com.jonnyzzz.intellij.mcp.storage.OutputMessage
import com.jonnyzzz.intellij.mcp.storage.OutputType
import kotlinx.coroutines.*
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.atomic.AtomicBoolean
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
     * Execute a script and return the result.
     * This is a suspend function - it runs inside the caller's coroutine context.
     *
     * Fast failure: If the script engine is not available or compilation fails,
     * returns immediately with an error - no waiting.
     */
    suspend fun execute(executionId: String, code: String, timeoutSeconds: Int? = null): ExecutionResult {
        val result = try {
            executeImpl(executionId, code, defaultTimeout(timeoutSeconds))
        } catch (e: Throwable) {
            log.error("Unexpected error during execution $executionId", e)
            val sw = StringWriter()
            e.printStackTrace(PrintWriter(sw))
            ExecutionResult(
                status = ExecutionStatus.ERROR,
                errorMessage = "Unexpected error: ${e.message}",
                exceptionInfo = sw.toString()
            )
        }
        project.service<ExecutionStorage>().writeResult(executionId, result)
        return result
    }

    private suspend fun executeImpl(executionId: String, code: String, timeoutSeconds: Int): ExecutionResult {
        val code = project.service<CodeEvalManager>().evalCode(executionId, code)
        if (code is EvalResult.Failed) {
            return code.errorResult
        }

        log.info("Starting execution $executionId")

        // Phase 0: Check script engine availability (fast fail)
        // Create parent Disposable for this execution
        val executionDisposable = Disposer.newDisposable(this, "mcp-execution-$executionId")

        // Create context for this execution (child of executionDisposable)
        val context = McpScriptContextImpl(
            project = project,
            executionId = executionId,
            disposable = executionDisposable,
        )

        try {
            //oops Kotlin!
            code as EvalResult.Success
            val capturedBlocks = code.result

            // Phase 2: Run captured blocks in FIFO order with timeout
            log.info("Running ${capturedBlocks.size} execute block(s) for $executionId with timeout ${timeoutSeconds}s")

            // Use coroutineScope to manage execution - cancellation propagates
            return coroutineScope {
                withContext(Dispatchers.IO) {
                    withTimeout(timeoutSeconds.seconds) {
                        runTheSubmittedCode(capturedBlocks, executionId, context)
                    }
                }
            }
        } catch (t: Throwable) {
            if (t is TimeoutCancellationException) {
                log.info("Execution timed out for $executionId after ${timeoutSeconds}s")
                return ExecutionResult(
                    status = ExecutionStatus.TIMEOUT,
                    errorMessage = "Execution timed out after $timeoutSeconds seconds"
                )
            } else if (t is ProcessCanceledException || t is CancellationException) {
                log.info("Execution cancelled for $executionId after ${timeoutSeconds}s")
                return ExecutionResult(
                    status = ExecutionStatus.CANCELLED,
                    errorMessage = "Execution timed out after $timeoutSeconds seconds"
                )
            } else {
                log.error("Unexpected error during execution $executionId", t)
                return ExecutionResult(
                    status = ExecutionStatus.ERROR,
                    errorMessage = "Unexpected error: ${t.message}",
                    exceptionInfo = t.stackTraceToString()
                )
            }
        } finally {
            Disposer.dispose(executionDisposable)
        }
    }

    private suspend fun runTheSubmittedCode(
        capturedBlocks: List<suspend McpScriptContext.() -> Unit>,
        executionId: String,
        context: McpScriptContextImpl
    ): ExecutionResult {
        var index = 0
        return try {
            // Execute blocks in FIFO order
            for (block in capturedBlocks) {
                yield()
                log.info("Executing block #${index + 1}/${capturedBlocks.size} for $executionId")
                block(context)
                index++
            }
            // Return error if one occurred, otherwise success
            ExecutionResult(status = ExecutionStatus.SUCCESS)
        } catch (e: Throwable) {
            // Any failure in a block marks the whole job as failed
            log.warn("Block #${index + 1} failed for $executionId: ${e.message}", e)
            val sw = StringWriter()
            e.printStackTrace(PrintWriter(sw))
            ExecutionResult(
                status = ExecutionStatus.ERROR,
                errorMessage = "Runtime error in block #${index + 1}: ${e.message}",
                exceptionInfo = sw.toString()
            )
        }
    }

}
