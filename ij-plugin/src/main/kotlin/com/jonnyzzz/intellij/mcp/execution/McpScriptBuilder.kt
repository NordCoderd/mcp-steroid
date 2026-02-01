/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

class McpScriptBuilder {
    /**
     * List of script blocks registered by the script.
     * Populated during script initialization (top-level code execution).
     */
    val executeBlocks = mutableListOf<suspend McpScriptContext.() -> Unit>()

    /**
     * Register a script block to be run later.
     * Called by user script during initialization.
     *
     * @param block The suspend lambda to be executed later with McpScriptContext
     */
    fun addBlock(block: suspend McpScriptContext.() -> Unit) {
        executeBlocks.add(block)
    }
}