/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.execution

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import kotlinx.serialization.json.JsonElement
import com.intellij.openapi.application.readAction as intellijReadAction
import com.intellij.openapi.application.writeAction as intellijWriteAction
import com.intellij.openapi.application.smartReadAction as intellijSmartReadAction

/**
 * Context provided to scripts inside the execute { } block.
 *
 * IMPORTANT: All code inside execute { } runs in a suspend context.
 * This context provides helper functions for common IntelliJ operations.
 *
 * ## Quick Reference
 *
 * ```kotlin
 * execute {
 *     // Wait for indexing
 *     waitForSmartMode()
 *
 *     // Read PSI/VFS data (use helpers - no imports needed!):
 *     val psiFile = readAction {
 *         PsiManager.getInstance(project).findFile(virtualFile)
 *     }
 *
 *     // Or use even simpler helpers:
 *     val psiFile = findPsiFile("/path/to/file.kt")
 *
 *     // Modify PSI/VFS:
 *     writeAction {
 *         document.setText("new content")
 *     }
 *
 *     // Get search scopes easily:
 *     val scope = projectScope()  // or allScope()
 * }
 * ```
 *
 * NEVER use runBlocking - it causes deadlocks.
 */
interface McpScriptContext {
    /** The IntelliJ Project this execution is associated with */
    val project: Project

    /** Original tool execution parameters */
    val params: JsonElement

    /** Allows to bind a disposable to the execution context, use coroutineScope {} for coroutine API */
    val disposable: Disposable

    /** Allows to check if the context is disposed */
    val isDisposed: Boolean

    // ============================================================
    // Output Methods
    // ============================================================

    /**
     * Print values to output, separated by spaces, followed by newline.
     * Each argument is converted to string via toString().
     *
     * ```kotlin
     * println("Hello", "World", 42)  // prints: "Hello World 42"
     * println()  // prints empty line
     * ```
     */
    fun println(vararg values: Any?)

    /**
     * Serialize an object to pretty-printed JSON and output it.
     * Uses Jackson ObjectMapper with indentation.
     *
     * ```kotlin
     * printJson(mapOf("name" to "value", "count" to 42))
     * ```
     */
    fun printJson(obj: Any?)

    /**
     * Report an error to the MCP client with stack trace.
     * Does not mark the execution as failed.
     *
     * Recommended for error handling - includes full stack trace in output.
     */
    fun printException(message: String, throwable: Throwable)

    /**
     * Report progress to the MCP client.
     * Messages are throttled to at most once per second to avoid overwhelming the connection.
     *
     * ```kotlin
     * execute {
     *     progress("Starting analysis...")
     *     // do work
     *     progress("Processing file 1 of 10")
     *     // more work
     *     progress("Analysis complete")
     * }
     * ```
     */
    fun progress(message: String)

    /**
     * Capture a screenshot of the IDE frame and send it as image content in the MCP response.
     * The image and related artifacts are saved under the execution folder using fixed filenames:
     * - screenshot.png
     * - screenshot-tree.md
     * - screenshot-meta.json
     *
     * The fileName parameter is ignored to keep filenames stable across tools.
     *
     * @param fileName ignored; kept for compatibility (default: ide-screenshot.png)
     * @return absolute path to the saved screenshot, or null if capture failed
     */
    suspend fun takeIdeScreenshot(fileName: String = "ide-screenshot.png"): String?

    // ============================================================
    // IDE Utilities - Waiting
    // ============================================================

    /**
     * Wait for indexing to complete (smart mode).
     * Use this before accessing indices or PSI that requires smart mode.
     *
     * ```kotlin
     * execute {
     *     waitForSmartMode()
     *     // Now safe to use indices and PSI
     * }
     * ```
     */
    suspend fun waitForSmartMode()

    // ============================================================
    // Read/Write Actions - Convenience Wrappers
    // ============================================================
    // These save you from importing com.intellij.openapi.application.readAction/writeAction

    /**
     * Execute a block under read lock.
     * Use for all PSI/VFS/index reads.
     *
     * ```kotlin
     * val psiFile = readAction {
     *     PsiManager.getInstance(project).findFile(virtualFile)
     * }
     * ```
     *
     * @see com.intellij.openapi.application.readAction
     */
    suspend fun <T> readAction(action: () -> T): T = intellijReadAction(action)

    /**
     * Execute a block under write lock on EDT.
     * Use for all PSI/VFS/document modifications.
     *
     * ```kotlin
     * writeAction {
     *     document.insertString(0, "// Header\n")
     * }
     * ```
     *
     * @see com.intellij.openapi.application.writeAction
     */
    suspend fun <T> writeAction(action: () -> T): T = intellijWriteAction(action)

    /**
     * Execute a read action that automatically waits for smart mode.
     * Combines waitForSmartMode() + readAction {} in one call.
     *
     * ```kotlin
     * val classes = smartReadAction {
     *     KotlinClassShortNameIndex.get("MyClass", project, projectScope())
     * }
     * ```
     *
     * @see com.intellij.openapi.application.smartReadAction
     */
    suspend fun <T> smartReadAction(action: () -> T): T = intellijSmartReadAction(project, action)

    // ============================================================
    // Search Scopes - Convenience Methods
    // ============================================================

    /**
     * Get a search scope covering all project files (excludes libraries).
     *
     * ```kotlin
     * val scope = projectScope()
     * FilenameIndex.getFilesByName(project, "build.gradle.kts", scope)
     * ```
     */
    fun projectScope(): GlobalSearchScope = GlobalSearchScope.projectScope(project)

    /**
     * Get a search scope covering project files AND all libraries.
     *
     * ```kotlin
     * val scope = allScope()
     * JavaPsiFacade.getInstance(project).findClass("java.util.List", scope)
     * ```
     */
    fun allScope(): GlobalSearchScope = GlobalSearchScope.allScope(project)

    // ============================================================
    // File Access - Convenience Methods
    // ============================================================

    /**
     * Find a VirtualFile by absolute path.
     * Returns null if file doesn't exist.
     *
     * ```kotlin
     * val vf = findFile("/path/to/file.kt")
     * if (vf != null) {
     *     val content = String(vf.contentsToByteArray())
     * }
     * ```
     */
    fun findFile(absolutePath: String): VirtualFile? =
        LocalFileSystem.getInstance().findFileByPath(absolutePath)

    /**
     * Find a PsiFile by absolute path.
     * Requires read action context or uses one internally.
     * Returns null if file doesn't exist or can't be parsed.
     *
     * ```kotlin
     * val psiFile = findPsiFile("/path/to/file.kt")
     * println(psiFile?.name)
     * ```
     */
    suspend fun findPsiFile(absolutePath: String): PsiFile? {
        val vf = findFile(absolutePath) ?: return null
        return readAction { PsiManager.getInstance(project).findFile(vf) }
    }

    /**
     * Find a VirtualFile relative to project base path.
     * Returns null if file doesn't exist.
     *
     * ```kotlin
     * val vf = findProjectFile("src/main/kotlin/MyClass.kt")
     * ```
     */
    fun findProjectFile(relativePath: String): VirtualFile? {
        val basePath = project.basePath ?: return null
        return findFile("$basePath/$relativePath")
    }

    /**
     * Find a PsiFile relative to project base path.
     * Returns null if file doesn't exist or can't be parsed.
     *
     * ```kotlin
     * val psiFile = findProjectPsiFile("src/main/kotlin/MyClass.kt")
     * ```
     */
    suspend fun findProjectPsiFile(relativePath: String): PsiFile? {
        val basePath = project.basePath ?: return null
        return findPsiFile("$basePath/$relativePath")
    }
}
