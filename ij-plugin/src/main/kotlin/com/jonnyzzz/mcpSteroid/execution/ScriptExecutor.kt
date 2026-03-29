/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.jonnyzzz.mcpSteroid.koltinc.LineMapping
import com.jonnyzzz.mcpSteroid.server.ExecCodeParams
import com.jonnyzzz.mcpSteroid.server.SkillReference
import com.jonnyzzz.mcpSteroid.storage.ExecutionId
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import kotlin.time.Duration.Companion.seconds

inline val Project.scriptExecutor: ScriptExecutor get() = service()

/**
 * Executes Kotlin scripts using IntelliJ's script engine.
 *
 * Execution flow:
 * 1. Script is compiled and evaluated to capture runnable script blocks
 * 2. Lambdas are executed in FIFO order inside a supervisorScope
 * 3. Any failure marks the whole execution as complete
 * 4. On timeout or cancellation, the Disposable is disposed and coroutine canceled
 *
 * Modal dialog handling:
 * - If a modal dialog appears during execution, execution is canceled
 * - A screenshot of the dialog is captured and returned
 * - Use steroid_input to interact with the dialog
 *
 * IMPORTANT: This executor runs the captured suspend block inside a supervisorScope.
 * The script code gets the coroutine context implicitly - no runBlocking needed.
 */
@Service(Service.Level.PROJECT)
class ScriptExecutor(
    private val project: Project
) : Disposable {
    private val log = Logger.getInstance(ScriptExecutor::class.java)
    override fun dispose() = Unit

    /**
     * Executes a script with progress reporting and returns its output.
     * It is a suspending function that runs inside the caller's coroutine context.
     *
     * Fast failure: If the script engine is not available or compilation fails,
     * it returns immediately with an error - no waiting.
     */
    suspend fun executeWithProgress(
        executionId: ExecutionId,
        exec: ExecCodeParams,
        resultBuilder: ExecutionResultBuilder,
    ) {
        val evalResult = project
            .codeEvalManager
            .evalCode(executionId, exec.code, resultBuilder) ?: return

        val lineMapping = evalResult.lineMapping

        log.info("Starting execution $executionId")

        // Create the parent Disposable for this execution
        val executionDisposable = Disposer.newDisposable(this, "mcp-execution-$executionId")

        // Create modality monitor to detect modal dialogs during execution
        val modalityMonitor = ModalityStateMonitor(project, executionId, executionDisposable)
        if (exec.cancelOnModal) {
            modalityMonitor.start()
        }

        // Create context for this execution with progress support
        val context = McpScriptContextImpl(
            project = project,
            params = exec.rawParams,
            executionId = executionId,
            disposable = executionDisposable,
            resultBuilder = resultBuilder,
            modalityMonitor = modalityMonitor,
        )

        try {
            val capturedBlocks = evalResult.result

            // Run captured blocks in FIFO order with timeout
            log.info("Running ${capturedBlocks.size} script block(s) for $executionId with timeout ${exec.timeout}s")

            coroutineScope {
                withContext(Dispatchers.IO) {
                    val exceptionJob = launch {
                        service<ExceptionCaptureService>().exceptions.collect { ex ->
                            context.println(buildString {
                                appendLine("=== IDE Exception Captured ===")
                                appendLine("Time: ${ex.timestamp}")
                                ex.pluginId?.let { appendLine("Plugin: $it") }
                                appendLine("Message: ${ex.message}")
                                appendLine("Stacktrace:")
                                append(ex.stacktrace)
                                appendLine("=== END ===")
                            })
                        }
                    }

                    try {
                        withTimeout(exec.timeout.seconds) {
                            // Use select to race between execution and modal dialog detection
                            val executionDeferred = async {
                                context.waitForSmartMode()
                                for ((index, block) in capturedBlocks.withIndex()) {
                                    yield()
                                    if (capturedBlocks.size > 1) {
                                        log.info("Executing block #${index + 1}/${capturedBlocks.size} for $executionId")
                                        context.progress("Executing block ${index + 1} of ${capturedBlocks.size}...")
                                    }
                                    block(context)
                                }
                            }

                            select {
                                modalityMonitor.onModalDialog { dialogInfo ->
                                    // Modal dialog detected - cancel execution and report
                                    log.info("Modal dialog detected during execution $executionId: ${dialogInfo.modalEntity}")
                                    executionDeferred.cancel("Modal dialog detected: ${dialogInfo.modalEntity}")
                                    reportModalDialog(dialogInfo, resultBuilder)
                                }
                                executionDeferred.onAwait {
                                    // Execution completed normally
                                    log.info("Execution $executionId completed normally")
                                }
                            }
                        }
                    } finally {
                        exceptionJob.cancel()
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            // Timeout - report as error (must be caught before CancellationException since it's a subclass)
            log.warn("Execution $executionId timed out: ${e.message}")
            resultBuilder.logRemappedException("Execution timed out", e, lineMapping)
            resultBuilder.reportFailed("Execution timed out after ${exec.timeout} seconds")
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            log.warn("Unexpected error during execution $executionId: ${t.message}", t)
            val remappedMessage = lineMapping.remapStackTrace(t.message ?: "")
            resultBuilder.logRemappedException("Unexpected error during execution: $remappedMessage", t, lineMapping)
            resultBuilder.reportFailed("Unexpected error during execution: $remappedMessage")
        } finally {
            modalityMonitor.stop()
            Disposer.dispose(executionDisposable)
        }
    }

    /**
     * Logs an exception with stack trace line numbers remapped from wrapped-file coordinates
     * to user-code coordinates, so agents see meaningful line references.
     */
    private fun ExecutionResultBuilder.logRemappedException(
        message: String,
        throwable: Throwable,
        lineMapping: LineMapping,
    ) {
        val cleanTrace = lineMapping.cleanStackTrace(throwable.stackTraceToString())
        val text = "ERROR: $message\n$cleanTrace"
        logMessage(text)

        val hint = SkillReference.getInstance().errorHint(throwable.message ?: message)
        logMessage("HINT: $hint")
    }

    private fun reportModalDialog(dialogInfo: ModalDialogInfo, resultBuilder: ExecutionResultBuilder) {
        resultBuilder.logMessage("=== MODAL DIALOG DETECTED ===")
        resultBuilder.logMessage("A modal dialog appeared during execution.")
        resultBuilder.logMessage("Modal entity: ${dialogInfo.modalEntity}")

        if (dialogInfo.dialogTitles.isNotEmpty()) {
            resultBuilder.logMessage("Dialog windows: ${dialogInfo.dialogTitles.joinToString(", ")}")
        }

        if (dialogInfo.screenshotBase64 != null) {
            resultBuilder.logMessage("Screenshot captured - see image below")
            resultBuilder.logImage("image/png", dialogInfo.screenshotBase64, "modal-dialog.png")
            resultBuilder.logMessage("Use steroid_input to interact with the dialog, or steroid_take_screenshot for a fresh view.")
        } else if (dialogInfo.screenshotError != null) {
            resultBuilder.logMessage("Screenshot capture failed: ${dialogInfo.screenshotError}")
        }
        resultBuilder.logMessage("=== END MODAL DIALOG ===")
    }
}
