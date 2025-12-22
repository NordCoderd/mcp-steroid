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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.StringWriter
import java.util.concurrent.atomic.AtomicBoolean

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

            project.executionStorage.writeCodeExecutionData(executionId, "compilation-success.txt", "Compiled")
            return EvalResult(capturedBlocks.toList())
        } catch (e: Throwable) {
            val message = "Error executing script $executionId: ${e.message}"

            if (e.toString().contains("Service is dying", ignoreCase = true)) {
                log.warn("Kotlin daemon is dying detected: ${e.message}", e)
                kotlinDaemonManager.forceKillKotlinDaemon()
                resultBuilder.logMessage("WARN: Script compilation/evaluation failed: Kotlin Daemon is dying. TRY AGAIN otherwise let user know")
                project.executionStorage.writeCodeExecutionData(
                    executionId,
                    "dying-kotlin-debug.txt",
                    buildString {
                        appendLine("Error: ${e.message}")
                        appendLine(e)
                        appendLine(e.stackTraceToString())
                    }
                )
            }

            if (e.toString().contains("Incomplete code", ignoreCase = true)
                || e.toString().contains("Code is incomplete", ignoreCase = true)) {

                log.warn("Kotlin incomplete code error detected: ${e.message}", e)
                resultBuilder.logMessage("WARN: Script compilation/evaluation failed: Incomplete code error. It usually means you put 'import' incorrectly or break Kotlin syntax")

                project.executionStorage.writeCodeExecutionData(
                    executionId,
                    "incomplete-code-debug.txt",
                    buildString {
                        appendLine("Error: ${e.message}")
                        appendLine(e)
                        appendLine(e.stackTraceToString())
                    }
                )
            }

            log.warn(message, e)
            resultBuilder.logException(message, e)
            resultBuilder.reportFailed(message)
            return null
        } finally {
            Disposer.dispose(scope)
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
