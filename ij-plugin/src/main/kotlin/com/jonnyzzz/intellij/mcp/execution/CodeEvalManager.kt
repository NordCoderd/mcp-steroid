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

    suspend fun evalCode(executionId: ExecutionId, code: String, resultBuilder: ExecutionResultBuilder): EvalResult? {
        val wrappedCode = codeButcher.wrapWithImports(code)

        // Track captured execute blocks (FIFO)
        val scope = DisposableScope(executionId)

        // Phase 1: Compile and run a script to capture the execute block(s)
        log.info("Compiling script $executionId")
        resultBuilder.logProgress("Compiling script...")

        try {
            val engineManager = IdeScriptEngineManager.getInstance()
            val engine = engineManager.getEngineByFileExtension("kts", null)

            if (engine == null) {
                val errorMsg = "Kotlin script engine not available. Ensure Kotlin plugin is installed and enabled."
                // Use warn level since this is expected in test environment where Kotlin plugin is not loaded
                log.warn("Execution ${executionId}: $errorMsg")
                resultBuilder.reportFailed(errorMsg)
                return null
            }

            log.info("Script engine obtained for $executionId: ${engine.javaClass.name}")

            // Set up bindings (exposed to the script as a top-level `execute { }` function)
            engine.setBinding("execute") { block: suspend McpScriptContext.() -> Unit ->
                scope.execute(block)
            }

            runEngineAndLogOutput(engine, wrappedCode, executionId, resultBuilder)

        } catch (e: Throwable) {
            // Compilation/evaluation failed - report immediately
            val message = "Script compilation/evaluation failed for $executionId: ${e.message}\n\n"
            log.warn(message, e)
            resultBuilder.logException(message, e)
            resultBuilder.reportFailed(message)
            return null
        } finally {
            // Mark scope as disposed - no more executed {} calls allowed
            Disposer.dispose(scope)
        }

        val capturedBlocks = scope.capturedBlocks

        log.info("Script evaluation complete for $executionId. Captured ${capturedBlocks.size} execute block(s)")
        if (capturedBlocks.isEmpty()) {
            val message = "Script must call execute { ... } to interact with the IDE. No execute {} block found."
            resultBuilder.reportFailed(message)
            log.warn(message)

            return null
        }

        return EvalResult(capturedBlocks.toList())
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
