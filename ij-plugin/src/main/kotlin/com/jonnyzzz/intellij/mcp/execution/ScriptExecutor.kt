/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.execution

import com.intellij.ide.script.IdeScriptEngineManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.jonnyzzz.intellij.mcp.storage.ExecutionResult
import com.jonnyzzz.intellij.mcp.storage.ExecutionStatus
import com.jonnyzzz.intellij.mcp.storage.ExecutionStorage
import com.jonnyzzz.intellij.mcp.storage.OutputMessage
import com.jonnyzzz.intellij.mcp.storage.OutputType
import kotlinx.coroutines.*
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds

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
class ScriptExecutor(
    private val project: Project,
    private val executionStorage: ExecutionStorage
) {
    private val log = Logger.getInstance(ScriptExecutor::class.java)

    companion object {
        private val DEFAULT_IMPORTS = """
            import com.intellij.openapi.project.*
            import com.intellij.openapi.application.*
            import com.intellij.openapi.application.readAction
            import com.intellij.openapi.application.writeAction
            import com.intellij.openapi.vfs.*
            import com.intellij.openapi.editor.*
            import com.intellij.openapi.fileEditor.*
            import com.intellij.openapi.command.*
            import com.intellij.psi.*
            import kotlinx.coroutines.*
        """.trimIndent()
    }

    /**
     * Execute a script and return the result.
     * This is a suspend function - it runs inside the caller's coroutine context.
     *
     * Fast failure: If the script engine is not available or compilation fails,
     * returns immediately with an error - no waiting.
     */
    suspend fun execute(executionId: String, code: String, timeoutSeconds: Int): ExecutionResult {
        log.info("Starting execution $executionId")

        // Phase 0: Check script engine availability (fast fail)
        val engineManager = IdeScriptEngineManager.getInstance()
        val engine = engineManager.getEngineByFileExtension("kts", null)

        if (engine == null) {
            val errorMsg = "Kotlin script engine not available. Ensure Kotlin plugin is installed and enabled."
            // Use warn level since this is expected in test environment where Kotlin plugin is not loaded
            log.warn(errorMsg)
            return ExecutionResult(
                status = ExecutionStatus.ERROR,
                errorMessage = errorMsg
            )
        }
        log.info("Script engine obtained for $executionId: ${engine.javaClass.name}")

        // Create parent Disposable for this execution
        val executionDisposable = Disposer.newDisposable("mcp-execution-$executionId")
        val isDisposed = AtomicBoolean(false)

        // Create context for this execution (child of executionDisposable)
        val context = McpScriptContextImpl(
            project = project,
            executionId = executionId,
            executionStorage = executionStorage,
            parentDisposable = executionDisposable
        )
        Disposer.register(executionDisposable, context)

        // Track captured execute blocks (FIFO)
        val capturedBlocks = mutableListOf<suspend McpScriptContext.() -> Unit>()
        val scopeDisposed = AtomicBoolean(false)

        val scope = object : McpScriptScope {
            override fun execute(block: suspend McpScriptContext.() -> Unit) {
                if (scopeDisposed.get()) {
                    log.warn("Attempt to call execute {} after scope was disposed for $executionId")
                    throw IllegalStateException("Cannot call execute {} - scope is already disposed")
                }
                capturedBlocks.add(block)
                log.info("Captured execute block #${capturedBlocks.size} for $executionId")
            }
        }

        // Set up bindings
        engine.setBinding("execute", { block: suspend McpScriptContext.() -> Unit -> scope.execute(block) })

        // Capture stdout/stderr
        val outputCapture = OutputCapture(executionId, executionStorage)
        engine.setStdOut(outputCapture.stdoutWriter)
        engine.setStdErr(outputCapture.stderrWriter)

        val wrappedCode = wrapWithImports(code)

        try {
            // Phase 1: Compile and run script to capture the execute block(s)
            log.info("Compiling script $executionId")
            try {
                engine.eval(wrappedCode)
            } catch (e: Exception) {
                // Compilation/evaluation failed - report immediately
                log.warn("Script compilation/evaluation failed for $executionId: ${e.message}", e)
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))

                return ExecutionResult(
                    status = ExecutionStatus.ERROR,
                    errorMessage = "Compilation error: ${e.message}",
                    exceptionInfo = sw.toString()
                )
            }

            // Mark scope as disposed - no more execute {} calls allowed
            scopeDisposed.set(true)
            log.info("Script evaluation complete for $executionId. Captured ${capturedBlocks.size} execute block(s)")

            if (capturedBlocks.isEmpty()) {
                return ExecutionResult(
                    status = ExecutionStatus.ERROR,
                    errorMessage = "Script must call execute { ctx -> ... } to interact with the IDE. No execute {} block found."
                )
            }

            // Phase 2: Run captured blocks in FIFO order with timeout
            log.info("Running ${capturedBlocks.size} execute block(s) for $executionId with timeout ${timeoutSeconds}s")

            // Holder for block execution error - allows returning error from inside withTimeout
            var blockError: ExecutionResult? = null

            return try {
                // Use coroutineScope to manage execution - cancellation propagates
                coroutineScope {
                    // Bind coroutine cancellation to Disposable
                    coroutineContext.job.invokeOnCompletion { cause ->
                        if (!isDisposed.getAndSet(true)) {
                            log.info("Disposing execution context for $executionId (cause: ${cause?.message ?: "completed"})")
                            Disposer.dispose(executionDisposable)
                        }
                    }

                    withTimeout(timeoutSeconds.seconds) {
                        // Execute blocks in FIFO order
                        for ((index, block) in capturedBlocks.withIndex()) {
                            if (isDisposed.get()) {
                                throw CancellationException("Execution disposed")
                            }
                            log.info("Executing block #${index + 1}/${capturedBlocks.size} for $executionId")
                            try {
                                block(context)
                            } catch (e: CancellationException) {
                                throw e // Propagate cancellation
                            } catch (e: Exception) {
                                // Any failure in a block marks the whole job as failed
                                log.warn("Block #${index + 1} failed for $executionId: ${e.message}", e)
                                val sw = StringWriter()
                                e.printStackTrace(PrintWriter(sw))
                                blockError = ExecutionResult(
                                    status = ExecutionStatus.ERROR,
                                    errorMessage = "Runtime error in block #${index + 1}: ${e.message}",
                                    exceptionInfo = sw.toString()
                                )
                                // Exit the loop and scope
                                return@withTimeout
                            }
                        }
                    }
                }
                // Return error if one occurred, otherwise success
                blockError ?: ExecutionResult(status = ExecutionStatus.SUCCESS)
            } catch (e: TimeoutCancellationException) {
                log.info("Execution timed out for $executionId after ${timeoutSeconds}s")
                ExecutionResult(
                    status = ExecutionStatus.TIMEOUT,
                    errorMessage = "Execution timed out after $timeoutSeconds seconds"
                )
            } catch (e: CancellationException) {
                log.info("Execution cancelled for $executionId: ${e.message}")
                ExecutionResult(
                    status = ExecutionStatus.CANCELLED,
                    errorMessage = "Execution was cancelled: ${e.message}"
                )
            }

        } catch (e: Exception) {
            // Unexpected error
            log.error("Unexpected error during execution $executionId", e)
            val sw = StringWriter()
            e.printStackTrace(PrintWriter(sw))
            return ExecutionResult(
                status = ExecutionStatus.ERROR,
                errorMessage = "Unexpected error: ${e.message}",
                exceptionInfo = sw.toString()
            )
        } finally {
            // Ensure cleanup
            outputCapture.close()
            if (!isDisposed.getAndSet(true)) {
                log.info("Final cleanup: disposing execution context for $executionId")
                Disposer.dispose(executionDisposable)
            }
        }
    }

    private fun wrapWithImports(code: String): String {
        return "$DEFAULT_IMPORTS\n\n$code"
    }
}

/**
 * Captures stdout/stderr and writes to execution storage.
 */
class OutputCapture(
    private val executionId: String,
    private val storage: ExecutionStorage
) {
    val stdoutWriter: java.io.Writer = object : java.io.Writer() {
        private val buffer = StringBuilder()

        override fun write(cbuf: CharArray, off: Int, len: Int) {
            buffer.append(cbuf, off, len)
            flushLines()
        }

        override fun flush() {
            flushLines()
        }

        override fun close() {
            if (buffer.isNotEmpty()) {
                storage.appendOutput(executionId, OutputMessage(
                    ts = System.currentTimeMillis(),
                    type = OutputType.OUT,
                    msg = buffer.toString()
                ))
                buffer.clear()
            }
        }

        private fun flushLines() {
            val content = buffer.toString()
            val lines = content.split("\n")
            if (lines.size > 1) {
                // Write all complete lines
                for (i in 0 until lines.size - 1) {
                    storage.appendOutput(executionId, OutputMessage(
                        ts = System.currentTimeMillis(),
                        type = OutputType.OUT,
                        msg = lines[i]
                    ))
                }
                buffer.clear()
                buffer.append(lines.last())
            }
        }
    }

    val stderrWriter: java.io.Writer = object : java.io.Writer() {
        private val buffer = StringBuilder()

        override fun write(cbuf: CharArray, off: Int, len: Int) {
            buffer.append(cbuf, off, len)
            flushLines()
        }

        override fun flush() {
            flushLines()
        }

        override fun close() {
            if (buffer.isNotEmpty()) {
                storage.appendOutput(executionId, OutputMessage(
                    ts = System.currentTimeMillis(),
                    type = OutputType.ERR,
                    msg = buffer.toString()
                ))
                buffer.clear()
            }
        }

        private fun flushLines() {
            val content = buffer.toString()
            val lines = content.split("\n")
            if (lines.size > 1) {
                for (i in 0 until lines.size - 1) {
                    storage.appendOutput(executionId, OutputMessage(
                        ts = System.currentTimeMillis(),
                        type = OutputType.ERR,
                        msg = lines[i]
                    ))
                }
                buffer.clear()
                buffer.append(lines.last())
            }
        }
    }

    fun close() {
        stdoutWriter.close()
        stderrWriter.close()
    }
}
