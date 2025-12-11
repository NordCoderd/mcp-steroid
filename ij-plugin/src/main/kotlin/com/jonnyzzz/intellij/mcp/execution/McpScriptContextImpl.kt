/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.execution

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.writeAction
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.jonnyzzz.intellij.mcp.storage.ExecutionStorage
import com.jonnyzzz.intellij.mcp.storage.OutputMessage
import com.jonnyzzz.intellij.mcp.storage.OutputType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.lang.reflect.Modifier
import kotlin.coroutines.resume

/**
 * Implementation of McpScriptContext.
 */
class McpScriptContextImpl(
    override val project: Project,
    override val executionId: String,
    private val executionStorage: ExecutionStorage,
    parentScope: CoroutineScope
) : McpScriptContext {

    private val json = Json { prettyPrint = true }

    override val coroutineScope: CoroutineScope = CoroutineScope(
        parentScope.coroutineContext + SupervisorJob()
    )

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

    override fun println(message: Any?) {
        appendOutput(OutputType.OUT, message?.toString() ?: "null")
    }

    override fun print(message: Any?) {
        appendOutput(OutputType.OUT, message?.toString() ?: "null")
    }

    override fun printJson(obj: Any?) {
        try {
            val jsonString = when (obj) {
                null -> "null"
                is String -> obj
                else -> json.encodeToString(obj.toString())
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

    override suspend fun <T> readAction(block: () -> T): T {
        return readAction { block() }
    }

    override suspend fun <T> writeAction(block: () -> T): T {
        return writeAction { block() }
    }

    override fun listServices(): List<String> {
        val result = mutableListOf<String>()
        try {
            // List project-level services
            val projectServiceManager = project.javaClass.methods
                .find { it.name == "getService" }
            if (projectServiceManager != null) {
                result.add("Project services available via project.getService(Class)")
            }

            // List application-level services
            result.add("Application services available via ApplicationManager.getApplication().getService(Class)")
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
        coroutineScope.cancel("McpScriptContext disposed")
    }
}
