/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.execution

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.jonnyzzz.intellij.mcp.server.ProgressReporter
import com.jonnyzzz.intellij.mcp.storage.ExecutionStorage
import com.jonnyzzz.intellij.mcp.storage.OutputType
import kotlinx.coroutines.suspendCancellableCoroutine
import java.lang.reflect.Modifier
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

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
    override val executionId: String,
    override val disposable: Disposable,
    private val progressReporter: ProgressReporter,
) : McpScriptContextEx {
    private val log = Logger.getInstance(McpScriptContextImpl::class.java)

    private val objectMapper = ObjectMapper().apply {
        enable(SerializationFeature.INDENT_OUTPUT)
        // Don't fail on empty beans
        disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
    }

    private val disposed = AtomicBoolean(false).also {
        Disposer.register(disposable) { it.set(true) }
    }

    // Collect all output for final result
    private val outputMessages = CopyOnWriteArrayList<String>()

    override val isDisposed: Boolean
        get() = disposed.get()

    /**
     * Get all output messages collected during execution.
     */
    fun getOutput(): List<String> = outputMessages.toList()

    private fun checkDisposed() {
        if (disposed.get()) {
            throw IllegalStateException("Context has been disposed - cannot perform output operations")
        }
    }

    private fun appendOutput(type: OutputType, message: String, level: String? = null) {
        // Log everything
        when (level) {
            "error" -> log.warn("[$executionId] $message")
            "warn" -> log.warn("[$executionId] $message")
            else -> log.info("[$executionId] $message")
        }

        // Store for final result
        outputMessages.add(message)

        // Also write to storage for persistence
        project.service<ExecutionStorage>().appendOutput(
            executionId,
            type = type,
            message = message,
            level = level,
        )

        // Report progress with latest output
        progressReporter.report(message)
    }

    override fun println(vararg values: Any?) {
        checkDisposed()
        appendOutput(OutputType.OUT, values.joinToString(" ") { it?.toString() ?: "null" })
    }

    override fun printJson(obj: Any?) {
        checkDisposed()
        try {
            val jsonString = when (obj) {
                null -> "null"
                is String -> obj
                else -> objectMapper.writeValueAsString(obj)
            }
            appendOutput(OutputType.JSON, jsonString)
        } catch (e: Exception) {
            appendOutput(OutputType.ERR, "Failed to serialize to JSON: ${e.message}")
        }
    }

    override fun progress(message: String) {
        checkDisposed()
        log.info("[$executionId] progress: $message")
        // Report progress directly without storing in output
        progressReporter.report(message)
    }

    override fun logInfo(message: String) {
        checkDisposed()
        appendOutput(OutputType.LOG, message, "info")
    }

    override fun logWarn(message: String) {
        checkDisposed()
        appendOutput(OutputType.LOG, message, "warn")
    }

    override fun logError(message: String, throwable: Throwable?) {
        checkDisposed()
        val fullMessage = if (throwable != null) {
            "$message: ${throwable.message}\n${throwable.stackTraceToString()}"
        } else {
            message
        }
        appendOutput(OutputType.LOG, fullMessage, "error")
    }

    override suspend fun waitForSmartMode() {
        checkDisposed()
        if (!DumbService.isDumb(project)) return

        log.info("[$executionId] Waiting for indexing to complete...")
        progressReporter.report("Waiting for indexing to complete...")

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

    // === McpScriptContextEx methods (reflection helpers) ===

    override fun listServices(): List<String> {
        checkDisposed()
        val result = mutableListOf<String>()
        try {
            result.add("Project services: project.getService(Class)")
            result.add("Application services: ApplicationManager.getApplication().getService(Class)")
            result.add("Common services:")
            result.add("  - ProjectRootManager: project.getService(ProjectRootManager::class.java)")
            result.add("  - PsiManager: PsiManager.getInstance(project)")
            result.add("  - FileEditorManager: FileEditorManager.getInstance(project)")
            result.add("  - VirtualFileManager: VirtualFileManager.getInstance()")
        } catch (e: Exception) {
            result.add("Error listing services: ${e.message}")
        }
        return result
    }

    override fun listExtensionPoints(): List<String> {
        checkDisposed()
        val result = mutableListOf<String>()
        try {
            result.add("Use ExtensionPointName.create(\"ep.name\") to access extension points")
            result.add("Common extension points:")
            result.add("  - com.intellij.projectService")
            result.add("  - com.intellij.applicationService")
            result.add("  - com.intellij.fileType")
            result.add("  - com.intellij.lang.parserDefinition")
        } catch (e: Exception) {
            result.add("Error listing extension points: ${e.message}")
        }
        return result
    }

    override fun describeClass(className: String): String {
        checkDisposed()
        return try {
            val clazz = Class.forName(className)
            buildString {
                appendLine("Class: ${clazz.name}")
                appendLine("Superclass: ${clazz.superclass?.name ?: "none"}")
                appendLine("Interfaces: ${clazz.interfaces.joinToString { it.name }}")
                appendLine()
                appendLine("Public Methods:")
                clazz.methods
                    .filter { Modifier.isPublic(it.modifiers) }
                    .sortedBy { it.name }
                    .forEach { method ->
                        val params = method.parameterTypes.joinToString(", ") { it.simpleName }
                        appendLine("  ${method.returnType.simpleName} ${method.name}($params)")
                    }
            }
        } catch (e: ClassNotFoundException) {
            "Class not found: $className"
        } catch (e: Exception) {
            "Error describing class: ${e.message}"
        }
    }
}
