/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.execution

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