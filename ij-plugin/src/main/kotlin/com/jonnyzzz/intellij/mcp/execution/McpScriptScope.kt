/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.execution

/**
 * Scope bound to script engine context.
 * Scripts must call execute { } to interact with the IDE.
 */
interface McpScriptScope {
    /**
     * Execute a suspend block with full MCP context.
     * This is the ONLY way for scripts to interact with the IDE.
     *
     * Example:
     * ```kotlin
     * execute { ctx ->
     *     ctx.println("Hello from IntelliJ!")
     *     ctx.waitForSmartMode()
     *     // ... actual work
     * }
     * ```
     */
    fun execute(block: suspend McpScriptContext.() -> Unit)
}
