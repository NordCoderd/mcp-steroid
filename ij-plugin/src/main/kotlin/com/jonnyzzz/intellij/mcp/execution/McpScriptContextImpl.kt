/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.execution

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.jonnyzzz.intellij.mcp.storage.ExecutionId
import com.jonnyzzz.intellij.mcp.storage.executionStorage
import com.jonnyzzz.intellij.mcp.vision.VisionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.time.Duration

/**
 * Implementation of McpScriptContext.
 *
 * Key features:
 * - Has a Disposable that scripts can use to register cleanup
 * - Rejects output operations after disposed
 * - Supports progress reporting via MCP notifications (throttled to 1/sec)
 * - No coroutineScope property - suspend functions get scope implicitly
 */
class McpScriptContextImpl(
    override val project: Project,
    override val params: JsonElement,
    val executionId: ExecutionId,
    override val disposable: Disposable,
    private val resultBuilder: ExecutionResultBuilder,
    private val modalityMonitor: ModalityStateMonitor,
) : McpScriptContext {
    private val log = Logger.getInstance(McpScriptContextImpl::class.java)

    private val objectMapper = ObjectMapper().apply {
        enable(SerializationFeature.INDENT_OUTPUT)
        // Don't fail on empty beans
        disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
    }.writerWithDefaultPrettyPrinter()

    private val disposed = AtomicBoolean(false).also {
        Disposer.register(disposable) { it.set(true) }
    }

    override val isDisposed: Boolean
        get() = disposed.get()

    private fun checkDisposed() {
        if (disposed.get()) {
            throw IllegalStateException("Context has been disposed - cannot perform output operations")
        }
    }

    override fun println(vararg values: Any?) {
        checkDisposed()
        resultBuilder.logMessage(values.joinToString(" ") { it?.toString() ?: "null" })
    }

    override fun printException(message: String, throwable: Throwable) {
        checkDisposed()
        resultBuilder.logException(message, throwable)
    }

    override fun printJson(obj: Any?) {
        checkDisposed()
        try {
            val jsonString = when (obj) {
                null -> "null"
                is String -> obj
                else -> objectMapper.writeValueAsString(obj)
            }
            resultBuilder.logMessage(jsonString)
        } catch (e: Exception) {
            resultBuilder.logMessage("Failed to serialize to JSON: ${e.message}")
        }
    }

    override fun progress(message: String) {
        checkDisposed()
        log.info("[$executionId] progress: $message")
        // Report progress directly without storing in output
        resultBuilder.logProgress(message)
    }

    override suspend fun takeIdeScreenshot(fileName: String): String? {
        checkDisposed()
        if (fileName.isNotBlank() && fileName != "ide-screenshot.png") {
            resultBuilder.logMessage("NOTE: takeIdeScreenshot ignores custom fileName and uses screenshot.png.")
        }
        return try {
            val artifacts = VisionService.capture(project, executionId)
            resultBuilder.logImage("image/png", Base64.getEncoder().encodeToString(artifacts.imageBytes), artifacts.meta.imageFile)
            resultBuilder.logMessage("window_id: ${artifacts.meta.windowId}")
            resultBuilder.logMessage("Screenshot saved to ${artifacts.imagePath}")
            resultBuilder.logMessage("Component tree saved to ${artifacts.treePath}")
            resultBuilder.logMessage("Screenshot metadata saved to ${artifacts.metaPath}")
            artifacts.imagePath.toString()
        } catch (e: Exception) {
            if (e is ProcessCanceledException) throw e
            resultBuilder.logException("Failed to capture IDE screenshot", e)
            null
        }
    }

    override suspend fun waitForSmartMode() {
        checkDisposed()
        if (!DumbService.isDumb(project)) return

        log.info("[$executionId] Waiting for indexing to complete...")
        resultBuilder.logProgress("Waiting for indexing to complete...")

        suspendCancellableCoroutine { cont ->
            fun waitForSmart() {
                if (disposed.get()) {
                    cont.cancel()
                    return
                }
                DumbService.getInstance(project).smartInvokeLater {
                    if (disposed.get()) {
                        cont.cancel()
                    } else if (DumbService.isDumb(project)) {
                        waitForSmart()
                    } else {
                        cont.resume(Unit)
                    }
                }
            }
            waitForSmart()
        }
    }

    override fun doNotCancelOnModalityStateChange() {
        checkDisposed()
        log.info("[$executionId] Modal dialog cancellation disabled by script")
        modalityMonitor.doNotCancelOnModalityStateChange()
    }

    // ============================================================
    // Daemon Code Analysis
    // ============================================================

    override suspend fun isDaemonRunning(): Boolean {
        checkDisposed()
        return readAction {
            DaemonCodeAnalyzer.getInstance(project).isRunning
        }
    }

    override suspend fun waitForDaemonAnalysis(file: VirtualFile, timeout: Duration): Boolean {
        checkDisposed()
        log.info("[$executionId] Waiting for daemon analysis on ${file.name}...")
        resultBuilder.logProgress("Waiting for daemon analysis on ${file.name}...")

        // First wait for smart mode
        waitForSmartMode()

        // Find the editor for the file
        val editor = withContext(Dispatchers.EDT) {
            FileEditorManager.getInstance(project).getEditors(file)
                .filterIsInstance<TextEditor>()
                .firstOrNull()
        }

        if (editor == null) {
            log.warn("[$executionId] No text editor found for ${file.name}, cannot wait for highlighting")
            return false
        }

        // Wait for highlighting to complete
        val completed = withTimeoutOrNull(timeout) {
            while (!disposed.get()) {
                val isComplete = withContext(Dispatchers.EDT) {
                    DaemonCodeAnalyzerEx.isHighlightingCompleted(editor, project)
                }
                if (isComplete) break
                delay(50)
            }
            true
        } ?: false

        if (completed) {
            log.info("[$executionId] Daemon analysis completed for ${file.name}")
        } else {
            log.warn("[$executionId] Timeout waiting for daemon analysis on ${file.name}")
        }
        return completed
    }

    override suspend fun getHighlightsWhenReady(
        file: VirtualFile,
        minSeverityValue: Int,
        timeout: Duration
    ): List<HighlightInfo> {
        checkDisposed()

        // Wait for analysis to complete
        val completed = waitForDaemonAnalysis(file, timeout)
        if (!completed) {
            return emptyList()
        }

        // Get document for the file
        val document = readAction {
            com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(file)
        } ?: return emptyList()

        // Get all highlights
        return readAction {
            getHighlightsFromDaemon(document, minSeverityValue)
        }
    }

    private fun getHighlightsFromDaemon(document: Document, minSeverityValue: Int): List<HighlightInfo> {
        val allHighlights = mutableListOf<HighlightInfo>()

        DaemonCodeAnalyzerEx.processHighlights(
            document,
            project,
            null, // null severity means all severities
            0,
            document.textLength
        ) { info ->
            if (info.severity.myVal >= minSeverityValue) {
                allHighlights.add(info)
            }
            true // continue processing
        }

        return allHighlights
    }
}
