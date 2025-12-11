/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.execution

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope

/**
 * Context provided to scripts inside the execute { } block.
 * Implements Disposable for resource cleanup.
 */
interface McpScriptContext : Disposable {
    /** The IntelliJ Project this execution is associated with */
    val project: Project

    /** CoroutineScope bound to this context's lifecycle */
    val coroutineScope: CoroutineScope

    /** Execution ID for this script run */
    val executionId: String

    // === Output Methods ===

    /** Print a message to the output */
    fun println(message: Any?)

    /** Print a message without newline */
    fun print(message: Any?)

    /** Serialize an object to JSON and print */
    fun printJson(obj: Any?)

    /** Log an info message */
    fun logInfo(message: String)

    /** Log a warning message */
    fun logWarn(message: String)

    /** Log an error message */
    fun logError(message: String, throwable: Throwable? = null)

    // === IDE Utilities ===

    /** Wait for indexing to complete (smart mode) */
    suspend fun waitForSmartMode()

    /** Execute a read action on the correct thread */
    suspend fun <T> readAction(block: () -> T): T

    /** Execute a write action on the correct thread */
    suspend fun <T> writeAction(block: () -> T): T

    // === Reflection Helpers ===

    /** List all registered services */
    fun listServices(): List<String>

    /** List all extension points */
    fun listExtensionPoints(): List<String>

    /** Describe a class (methods, fields, etc.) */
    fun describeClass(className: String): String
}
