/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.execution

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.jonnyzzz.intellij.mcp.storage.ExecutionStorage
import com.jonnyzzz.intellij.mcp.storage.OutputMessage
import com.jonnyzzz.intellij.mcp.storage.OutputType
import kotlinx.coroutines.suspendCancellableCoroutine
import java.lang.reflect.Modifier
import kotlin.coroutines.resume

/**
 * Implementation of McpScriptContext.
 * Note: No coroutineScope property - suspend functions get scope implicitly.
 */
class McpScriptContextImpl(
    override val project: Project,
    override val executionId: String,
    private val executionStorage: ExecutionStorage
) : McpScriptContextEx {

    private val objectMapper = ObjectMapper().apply {
        enable(SerializationFeature.INDENT_OUTPUT)
        // Don't fail on empty beans
        disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
    }

    private fun appendOutput(type: OutputType, message: String, level: String? = null) {
        executionStorage.appendOutput(
            executionId,
            OutputMessage(
                ts = System.currentTimeMillis(),
                type = type,
                msg = message,
                level = level
            )
        )
    }

    override fun println(vararg values: Any?) {
        val message = if (values.isEmpty()) {
            ""
        } else {
            values.joinToString(" ") { it?.toString() ?: "null" }
        }
        appendOutput(OutputType.OUT, message)
    }

    override fun printJson(obj: Any?) {
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

    override fun logInfo(message: String) {
        appendOutput(OutputType.LOG, message, "info")
    }

    override fun logWarn(message: String) {
        appendOutput(OutputType.LOG, message, "warn")
    }

    override fun logError(message: String, throwable: Throwable?) {
        val fullMessage = if (throwable != null) {
            "$message: ${throwable.message}\n${throwable.stackTraceToString()}"
        } else {
            message
        }
        appendOutput(OutputType.LOG, fullMessage, "error")
    }

    override suspend fun waitForSmartMode() {
        if (!DumbService.isDumb(project)) return

        suspendCancellableCoroutine { cont ->
            fun waitForSmart() {
                DumbService.getInstance(project).smartInvokeLater {
                    if (DumbService.isDumb(project)) {
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

    override fun dispose() {
        // No coroutineScope to cancel - context is just a data holder
    }
}
