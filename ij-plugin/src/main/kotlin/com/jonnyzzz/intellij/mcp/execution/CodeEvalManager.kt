/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.execution

import com.intellij.ide.script.IdeScriptEngineManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.jonnyzzz.intellij.mcp.storage.ExecutionResult
import com.jonnyzzz.intellij.mcp.storage.ExecutionStatus
import com.jonnyzzz.intellij.mcp.storage.ExecutionStorage
import com.jonnyzzz.intellij.mcp.storage.OutputType
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.atomic.AtomicBoolean

sealed interface EvalResult {
    data class Failed(val errorResult: ExecutionResult) : EvalResult
    data class Success(val result: List<suspend McpScriptContext.() -> Unit>) : EvalResult
}

private class DisposableScope(val executionId: String) : McpScriptScope, Disposable {
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

inline val Project.codeEvalManager : ScriptExecutor get() = service()

@Service(Service.Level.PROJECT)
class CodeEvalManager(
    private val project: Project,
) : Disposable {
    override fun dispose() = Unit

    private val log = thisLogger()

    private fun wrapWithImports(code: String): String {
        return """
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
            
            
            $code
        """.trimIndent()
    }

    fun evalCode(executionId: String, code: String): EvalResult {
        // Track captured execute blocks (FIFO)
        val scope = DisposableScope(executionId)
        val engineWriter = StringWriter()

        // Phase 1: Compile and run a script to capture the execute block(s)
        log.info("Compiling script $executionId")
        try {
            val engineManager = IdeScriptEngineManager.getInstance()
            val engine = engineManager.getEngineByFileExtension("kts", null)

            if (engine == null) {
                val errorMsg = "Kotlin script engine not available. Ensure Kotlin plugin is installed and enabled."
                // Use warn level since this is expected in test environment where Kotlin plugin is not loaded
                log.warn(errorMsg)
                return EvalResult.Failed(
                    ExecutionResult(
                        status = ExecutionStatus.ERROR,
                        errorMessage = errorMsg
                    )
                )
            }
            log.info("Script engine obtained for $executionId: ${engine.javaClass.name}")

            // Set up bindings
            engine.setBinding("execute", { block: suspend McpScriptContext.() -> Unit -> scope.execute(block) })

            // Capture stdout/stderr
            engine.stdOut = engineWriter
            engine.stdErr = engineWriter
            engine.stdIn = "".reader()

            val wrappedCode = wrapWithImports(code)
            try {
                engine.eval(wrappedCode)
            } finally {
                project.service<ExecutionStorage>()
                    .appendOutput(executionId, OutputType.COMPILER, engineWriter.toString())
            }
        } catch (e: Throwable) {
            // Compilation/evaluation failed - report immediately
            log.warn("Script compilation/evaluation failed for $executionId: ${e.message}", e)
            val sw = StringWriter()
            e.printStackTrace(PrintWriter(sw))


            return EvalResult.Failed(ExecutionResult(
                status = ExecutionStatus.ERROR,
                errorMessage = "Compilation error: ${e.message}",
                exceptionInfo = sw.toString()
            ))

        } finally {
            // Mark scope as disposed - no more executed {} calls allowed
            Disposer.dispose(scope)
        }

        val capturedBlocks = scope.capturedBlocks

        log.info("Script evaluation complete for $executionId. Captured ${capturedBlocks.size} execute block(s)")
        if (capturedBlocks.isEmpty()) {
            return EvalResult.Failed(ExecutionResult(
                status = ExecutionStatus.ERROR,
                errorMessage = "Script must call execute { ctx -> ... } to interact with the IDE. No execute {} block found."
            ))
        }

        //TODO: deal with compiler warnings
        return EvalResult.Success(capturedBlocks.toList())
    }
}