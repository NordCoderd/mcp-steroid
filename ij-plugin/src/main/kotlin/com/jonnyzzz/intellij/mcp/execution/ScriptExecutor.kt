/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.execution

import com.intellij.ide.script.IdeScriptEngineManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.jonnyzzz.intellij.mcp.storage.ExecutionResult
import com.jonnyzzz.intellij.mcp.storage.ExecutionStatus
import com.jonnyzzz.intellij.mcp.storage.ExecutionStorage
import com.jonnyzzz.intellij.mcp.storage.OutputMessage
import com.jonnyzzz.intellij.mcp.storage.OutputType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.time.Duration.Companion.seconds

/**
 * Executes Kotlin scripts using IntelliJ's script engine.
 */
class ScriptExecutor(
    private val project: Project,
    private val executionStorage: ExecutionStorage,
    private val parentScope: CoroutineScope
) {
    private val log = Logger.getInstance(ScriptExecutor::class.java)

    companion object {
        private val DEFAULT_IMPORTS = """
            import com.intellij.openapi.project.*
            import com.intellij.openapi.application.*
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
     */
    fun execute(executionId: String, code: String, timeoutSeconds: Int): ExecutionResult {
        log.info("Executing script $executionId")

        val engineManager = IdeScriptEngineManager.getInstance()
        val engine = engineManager.getEngineByFileExtension("kts", null)

        if (engine == null) {
            log.error("Kotlin script engine not available")
            return ExecutionResult(
                status = ExecutionStatus.ERROR,
                errorMessage = "Kotlin script engine not available"
            )
        }

        // Create context for this execution
        val context = McpScriptContextImpl(
            project = project,
            executionId = executionId,
            executionStorage = executionStorage,
            parentScope = parentScope
        )

        // Capture the execute block
        var capturedBlock: (suspend McpScriptContext.() -> Unit)? = null
        val scope = object : McpScriptScope {
            override fun execute(block: suspend McpScriptContext.() -> Unit) {
                capturedBlock = block
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
            // Phase 1: Compile and run script to capture the execute block
            log.info("Compiling script $executionId")
            engine.eval(wrappedCode)

            val block = capturedBlock
            if (block == null) {
                return ExecutionResult(
                    status = ExecutionStatus.ERROR,
                    errorMessage = "Script must call execute { ctx -> ... } to interact with the IDE"
                )
            }

            // Phase 2: Run the captured block with timeout
            log.info("Running execute block for $executionId")
            return try {
                runBlocking {
                    withTimeout(timeoutSeconds.seconds) {
                        context.use {
                            block(context)
                        }
                    }
                }
                ExecutionResult(status = ExecutionStatus.SUCCESS)
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                ExecutionResult(
                    status = ExecutionStatus.TIMEOUT,
                    errorMessage = "Execution timed out after $timeoutSeconds seconds"
                )
            }

        } catch (e: Exception) {
            log.warn("Script execution failed: ${e.message}", e)

            // Determine if this is a compilation error or runtime error
            val isCompilationError = e.javaClass.name.contains("Script") ||
                    e.message?.contains("unresolved reference") == true ||
                    e.message?.contains("expecting") == true

            val sw = StringWriter()
            e.printStackTrace(PrintWriter(sw))

            return ExecutionResult(
                status = ExecutionStatus.ERROR,
                errorMessage = if (isCompilationError) "Compilation error: ${e.message}" else e.message,
                exceptionInfo = sw.toString()
            )
        } finally {
            Disposer.dispose(context)
            outputCapture.close()
        }
    }

    private fun wrapWithImports(code: String): String {
        return "$DEFAULT_IMPORTS\n\n$code"
    }

    /**
     * Helper to use context as a Closeable-like resource.
     */
    private inline fun <T : McpScriptContext, R> T.use(block: (T) -> R): R {
        return try {
            block(this)
        } finally {
            // Disposal handled in finally of execute()
        }
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
