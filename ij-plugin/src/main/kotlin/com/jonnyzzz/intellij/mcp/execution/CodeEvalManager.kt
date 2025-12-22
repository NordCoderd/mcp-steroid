/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.execution

import com.intellij.ide.script.IdeScriptEngine
import com.intellij.ide.script.IdeScriptEngineManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.jonnyzzz.intellij.mcp.storage.ExecutionId
import com.jonnyzzz.intellij.mcp.storage.executionStorage
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.StringWriter
import java.util.concurrent.atomic.AtomicBoolean
import javax.script.ScriptException

data class EvalResult(val result: List<suspend McpScriptContext.() -> Unit>)

private class DisposableScope(val executionId: ExecutionId) : McpScriptScope, Disposable {
    private val log = thisLogger()

    val capturedBlocks = mutableListOf<suspend McpScriptContext.() -> Unit>()
    val scopeDisposed = AtomicBoolean(false)

    override fun execute(block: suspend McpScriptContext.() -> Unit) {
        if (scopeDisposed.get()) {
            log.warn("Attempt to call execute {} after scope was disposed for $executionId")
            throw IllegalStateException("Cannot call execute {} - scope is already disposed")
        }
        capturedBlocks.add(block)
        log.info("Captured execute block #${capturedBlocks.size} for $executionId")
    }

    override fun dispose() {
        scopeDisposed.set(true)
    }
}

inline val Project.codeEvalManager : CodeEvalManager get() = service()

@Service(Service.Level.PROJECT)
class CodeEvalManager(
    private val project: Project,
) : Disposable {
    override fun dispose() = Unit

    private val log = thisLogger()
    private val compilationMutex = Mutex()

    suspend fun evalCode(executionId: ExecutionId, code: String, resultBuilder: ExecutionResultBuilder): EvalResult? {
        if (compilationMutex.isLocked) {
            log.info("Compilation $executionId waiting for previous compilation to complete")
            resultBuilder.logProgress("Waiting for previous compilation to complete...")
        }
        return compilationMutex.withLock {
            evalCodeInternal(executionId, code, resultBuilder)
        }
    }

    private suspend fun evalCodeInternal(executionId: ExecutionId, code: String, resultBuilder: ExecutionResultBuilder): EvalResult? {
        val wrappedCode = codeButcher.wrapWithImports(code)
        project.executionStorage.writeWrappedScript(executionId, wrappedCode)

        // Proactive daemon kill mode (default): Kill daemon before compilation to ensure clean classpath
        // This prevents "incomplete code" errors caused by stale daemon state
        if (kotlinDaemonManager.isProactiveDaemonKillEnabled()) {
            log.info("Proactive daemon kill mode: cleaning up daemon before compilation")
            resultBuilder.logProgress("Preparing Kotlin compiler...")

            // Kill any existing daemon to ensure fresh classpath
            val daemonKilled = kotlinDaemonManager.forceKillKotlinDaemon()
            if (daemonKilled) {
                log.info("Killed existing Kotlin daemon, waiting for cleanup...")
                delay(kotlinDaemonManager.DAEMON_KILL_RETRY_DELAY_MS)
            }
        }

        val scope = DisposableScope(executionId)
        try {
            log.info("Compiling script $executionId")

            val engineManager = IdeScriptEngineManager.getInstance()
            val engine = engineManager.getEngineByFileExtension("kts", null)

            if (engine == null) {
                val errorMsg = "Kotlin script engine not available. Ensure Kotlin plugin is installed and enabled."
                log.warn("Execution ${executionId}: $errorMsg")
                resultBuilder.reportFailed(errorMsg)
                return null
            }

            log.info("Script engine obtained for $executionId: ${engine.javaClass.name}")

            engine.setBinding("execute") { block: suspend McpScriptContext.() -> Unit ->
                scope.execute(block)
            }

            runEngineAndLogOutput(engine, wrappedCode, executionId, resultBuilder)

            // Success - check captured blocks
            val capturedBlocks = scope.capturedBlocks
            log.info("Script evaluation complete for $executionId. Captured ${capturedBlocks.size} execute block(s)")

            if (capturedBlocks.isEmpty()) {
                val message = "Script must call execute { ... } to interact with the IDE. No execute {} block found."
                resultBuilder.reportFailed(message)
                log.warn(message)
                return null
            }

            return EvalResult(capturedBlocks.toList())
        } catch (e: Throwable) {
            val message = "Error executing script $executionId: ${e.message}"
            // Non-recoverable error or exhausted retries
            if (e !is ScriptException) {
                log.warn(message, e)
                resultBuilder.logException(message, e)
            } else {
                log.warn(message)
                resultBuilder.logMessage(message)
            }
            resultBuilder.reportFailed(message)

            // Log code for debugging
            logDebugInfoOnError(e, executionId, wrappedCode)

            return null
        } finally {
            Disposer.dispose(scope)
        }
    }

    /**
     * Log debug information when compilation fails with certain errors.
     */
    private suspend fun logDebugInfoOnError(e: Throwable, executionId: ExecutionId, wrappedCode: String) {
        // Log code for "incomplete code" errors for debugging
        if (e.message?.contains("incomplete code") == true ||
            e.cause?.message?.contains("incomplete code") == true) {
            log.warn("Incomplete code error - saving wrapped code for debugging")
            project.executionStorage.writeCodeExecutionData(
                executionId,
                "incomplete-code-debug.kts",
                "" +
                        "// Error: ${e.message}\n" +
                        "// Wrapped code that caused the error:\n\n" +
                        wrappedCode
            )
        }

        // Log code for "unresolved reference" errors (classpath issues)
        if (e.message?.contains("unresolved reference") == true ||
            e.cause?.message?.contains("unresolved reference") == true) {
            log.warn("Unresolved reference error - likely classpath issue, saving debug info")
            project.executionStorage.writeCodeExecutionData(
                executionId,
                "classpath-error-debug.kts",
                "" +
                        "// Error: ${e.message}\n" +
                        "// This usually means the Kotlin daemon doesn't have the plugin classes.\n" +
                        wrappedCode
            )
        }
    }

    private suspend fun runEngineAndLogOutput(
        engine: IdeScriptEngine,
        wrappedCode: String,
        executionId: ExecutionId,
        resultBuilder: ExecutionResultBuilder
    ) {
        // Capture stdout/stderr
        val engineWriterOut = StringWriter()
        val engineWriterError = StringWriter()
        engine.stdOut = engineWriterOut
        engine.stdErr = engineWriterError
        engine.stdIn = "".reader()

        try {
            engine.eval(wrappedCode)
        } finally {
            val compilerOutput = engineWriterOut.toString().trim()
            val compilerError = engineWriterError.toString().trim()

            if (compilerOutput.isNotEmpty()) {
                resultBuilder.logProgress("Compiler Output:\n$compilerOutput")
            }

            if (compilerError.isNotEmpty()) {
                // If there's compiler error, it's a failure
                resultBuilder.reportFailed("Compiler Errors/Warnings:\n$compilerError")
            }

            if (compilerOutput.isNotEmpty() || compilerError.isNotEmpty()) {
                project.executionStorage.writeCodeExecutionData(
                    executionId,
                    "kotlinc.txt",
                    "--- STDOUT ---\n$compilerOutput\n\n--- STDERR ---\n$compilerError"
                )
            }
        }
    }
}
