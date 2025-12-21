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
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.registry.Registry
import com.jonnyzzz.intellij.mcp.storage.ExecutionId
import com.jonnyzzz.intellij.mcp.storage.executionStorage
import kotlinx.coroutines.delay
import java.io.File
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

    private val DAEMON_DYING_RETRY_DELAY_MS = 2000L
    private val DAEMON_KILL_RETRY_DELAY_MS = 3000L

    /**
     * Returns true if daemon recovery is enabled via registry key.
     */
    private fun isDaemonRecoveryEnabled(): Boolean {
        return try {
            Registry.`is`("mcp.steroids.daemon.recovery", true)
        } catch (e: Exception) {
            // Registry key not defined - default to enabled
            true
        }
    }

    /**
     * Checks if the exception indicates the Kotlin daemon is dying.
     */
    private fun isDaemonDyingError(e: Throwable): Boolean {
        val message = e.message ?: ""
        val causeMessage = e.cause?.message ?: ""
        return message.contains("Service is dying") ||
                causeMessage.contains("Service is dying") ||
                message.contains("Could not connect to Kotlin compile daemon") ||
                causeMessage.contains("Could not connect to Kotlin compile daemon")
    }

    /**
     * Gets the Kotlin daemon directory path based on the operating system.
     */
    private fun getKotlinDaemonDir(): File? {
        val home = System.getProperty("user.home") ?: return null
        return when {
            SystemInfo.isMac -> File(home, "Library/Application Support/kotlin/daemon")
            SystemInfo.isWindows -> {
                val localAppData = System.getenv("LOCALAPPDATA")
                if (localAppData != null) {
                    File(localAppData, "kotlin/daemon")
                } else {
                    File(home, "AppData/Local/kotlin/daemon")
                }
            }
            else -> File(home, ".kotlin/daemon") // Linux and others
        }
    }

    /**
     * Forces the Kotlin daemon to shutdown by deleting its .run files.
     * The daemon monitors these files and shuts down when they're deleted.
     * Also cleans up stale client marker files.
     */
    private fun forceKillKotlinDaemon(): Boolean {
        val daemonDir = getKotlinDaemonDir()
        if (daemonDir == null || !daemonDir.exists()) {
            log.warn("Kotlin daemon directory not found")
            return false
        }

        var killedAny = false

        // Delete .run files to signal daemon shutdown
        daemonDir.listFiles()?.filter { it.name.endsWith(".run") }?.forEach { runFile ->
            try {
                if (runFile.delete()) {
                    log.info("Deleted Kotlin daemon run file: ${runFile.name}")
                    killedAny = true
                } else {
                    log.warn("Failed to delete run file: ${runFile.name}")
                }
            } catch (e: Exception) {
                log.warn("Error deleting run file ${runFile.name}: ${e.message}")
            }
        }

        // Clean up stale client marker files
        var cleanedMarkers = 0
        daemonDir.listFiles()?.filter { it.name.contains("-is-running") }?.forEach { marker ->
            try {
                if (marker.delete()) {
                    cleanedMarkers++
                }
            } catch (e: Exception) {
                // Ignore errors cleaning up markers
            }
        }
        if (cleanedMarkers > 0) {
            log.info("Cleaned up $cleanedMarkers stale client marker files")
        }

        return killedAny
    }

    suspend fun evalCode(executionId: ExecutionId, code: String, resultBuilder: ExecutionResultBuilder): EvalResult? {
        val wrappedCode = codeButcher.wrapWithImports(code)

        // Retry strategy for daemon issues:
        // 1. First attempt: normal execution
        // 2. If "Service is dying", wait and retry (daemon may recover)
        // 3. If still failing, force kill daemon and retry once more
        var lastException: Throwable? = null
        var daemonKilled = false

        for (attempt in 1..3) {
            val scope = DisposableScope(executionId)

            try {
                log.info("Compiling script $executionId (attempt $attempt)")
                if (attempt == 1) {
                    resultBuilder.logProgress("Compiling script...")
                }

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
                lastException = e

                if (isDaemonDyingError(e) && isDaemonRecoveryEnabled()) {
                    when (attempt) {
                        1 -> {
                            // First failure: wait and retry (daemon may be restarting)
                            log.warn("Kotlin daemon dying on attempt $attempt, waiting ${DAEMON_DYING_RETRY_DELAY_MS}ms before retry")
                            resultBuilder.logProgress("Kotlin daemon restarting, retrying...")
                            delay(DAEMON_DYING_RETRY_DELAY_MS)
                            continue
                        }
                        2 -> {
                            // Second failure: force kill daemon and retry
                            log.warn("Kotlin daemon still dying after retry, forcing daemon restart")
                            resultBuilder.logProgress("Forcing Kotlin daemon restart...")
                            daemonKilled = forceKillKotlinDaemon()
                            if (daemonKilled) {
                                delay(DAEMON_KILL_RETRY_DELAY_MS)
                            }
                            continue
                        }
                        else -> {
                            // Third failure: give up
                            log.warn("Kotlin daemon recovery failed after $attempt attempts (daemon killed: $daemonKilled)", e)
                        }
                    }
                }

                // Non-recoverable error or exhausted retries
                val message = "Script compilation/evaluation failed for $executionId: ${e.message}\n\n"
                if (e !is ScriptException) {
                    log.warn(message, e)
                    resultBuilder.logException(message, e)
                } else {
                    log.warn(message)
                }
                resultBuilder.reportFailed(message)
                return null

            } finally {
                Disposer.dispose(scope)
            }
        }

        // Should not reach here, but handle just in case
        val message = "Script execution failed after all retries: ${lastException?.message}"
        resultBuilder.reportFailed(message)
        return null
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

            var kotlinCOutput = ""
            if (compilerOutput.isNotEmpty()) {
                val text = "Compiler Output:\n$compilerOutput"
                resultBuilder.logProgress(text)
                kotlinCOutput += text
            }

            if (compilerError.isNotEmpty()) {
                val text = "Compiler Error: $compilerError"
                resultBuilder.reportFailed(text)
                if (kotlinCOutput.isNotEmpty()) kotlinCOutput += "\n"
                kotlinCOutput += text
            }

            if (kotlinCOutput.isNotEmpty()) {
                project.executionStorage.writeCodeExecutionData(
                    executionId,
                    "kotlinc.txt",
                    "Output:\n ${engineWriterOut}\n\nError:\n $engineWriterError"
                )
            }
        }
    }
}
