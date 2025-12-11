/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.execution

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project

/**
 * Context provided to scripts inside the execute { } block.
 *
 * IMPORTANT: All code inside execute { } runs in a suspend context.
 * Use IntelliJ's coroutine-aware APIs for read/write actions:
 *
 * ```kotlin
 * import com.intellij.openapi.application.readAction
 * import com.intellij.openapi.application.writeAction
 *
 * execute { ctx ->
 *     // Read PSI/VFS data:
 *     val psiFile = readAction {
 *         PsiManager.getInstance(ctx.project).findFile(virtualFile)
 *     }
 *
 *     // Modify PSI/VFS:
 *     writeAction {
 *         document.setText("new content")
 *     }
 * }
 * ```
 *
 * NEVER use runBlocking in production code - it blocks the thread and can cause deadlocks.
 */
interface McpScriptContext : Disposable {
    /** The IntelliJ Project this execution is associated with */
    val project: Project

    /** Execution ID for this script run */
    val executionId: String

    // === Output Methods ===

    /**
     * Print values to output, separated by spaces, followed by newline.
     * Each argument is converted to string via toString().
     *
     * ```kotlin
     * ctx.println("Hello", "World", 42)  // prints: "Hello World 42"
     * ctx.println()  // prints empty line
     * ```
     */
    fun println(vararg values: Any?)

    /**
     * Serialize an object to pretty-printed JSON and output it.
     * Uses Jackson ObjectMapper with indentation.
     *
     * ```kotlin
     * ctx.printJson(mapOf("name" to "value", "count" to 42))
     * ```
     */
    fun printJson(obj: Any?)

    /** Log an info message */
    fun logInfo(message: String)

    /** Log a warning message */
    fun logWarn(message: String)

    /** Log an error message */
    fun logError(message: String, throwable: Throwable? = null)

    // === IDE Utilities ===

    /**
     * Wait for indexing to complete (smart mode).
     * Use this before accessing indices or PSI that requires smart mode.
     *
     * ```kotlin
     * execute { ctx ->
     *     ctx.waitForSmartMode()
     *     // Now safe to use indices
     *     val classes = readAction {
     *         JavaPsiFacade.getInstance(ctx.project).findClasses("com.example.MyClass", GlobalSearchScope.allScope(ctx.project))
     *     }
     * }
     * ```
     */
    suspend fun waitForSmartMode()
}

/**
 * Extended context with reflection helpers.
 * These are moved to a separate interface as they may be deprecated in favor of
 * direct API usage documented in MCP tool descriptions.
 */
interface McpScriptContextEx : McpScriptContext {
    /** List all registered services (informational) */
    fun listServices(): List<String>

    /** List all extension points (informational) */
    fun listExtensionPoints(): List<String>

    /** Describe a class (methods, fields, etc.) */
    fun describeClass(className: String): String
}
