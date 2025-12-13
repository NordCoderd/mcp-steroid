/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.script

import com.jonnyzzz.intellij.mcp.execution.McpScriptContext
import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.*
import kotlin.script.experimental.jvm.JvmDependency
import kotlin.script.experimental.jvm.dependenciesFromClassContext
import kotlin.script.experimental.jvm.jvm

/**
 * Script definition for MCP Steroid review.kts files.
 *
 * This tells IntelliJ how to analyze .kts files in the mcp-run folder,
 * providing the correct classpath so that IntelliJ APIs are recognized
 * without error highlighting.
 */
@KotlinScript(
    fileExtension = "kts",
    compilationConfiguration = McpSteroidScriptCompilationConfiguration::class
)
abstract class McpSteroidScript

/**
 * Compilation configuration that provides:
 * - Default imports for IntelliJ APIs
 * - Classpath including IntelliJ platform and all plugin JARs
 */
object McpSteroidScriptCompilationConfiguration : ScriptCompilationConfiguration({
    // Default imports - same as what CodeEvalManager.wrapWithImports() adds
    defaultImports(
        "com.intellij.openapi.project.*",
        "com.intellij.openapi.application.*",
        "com.intellij.openapi.application.readAction",
        "com.intellij.openapi.application.writeAction",
        "com.intellij.openapi.vfs.*",
        "com.intellij.openapi.editor.*",
        "com.intellij.openapi.fileEditor.*",
        "com.intellij.openapi.command.*",
        "com.intellij.psi.*",
        "kotlinx.coroutines.*"
    )

    // Include IntelliJ platform and plugin classpath
    // dependenciesFromClassContext picks up all JARs available to the specified class
    jvm {
        // Use McpScriptContext to get all IntelliJ platform + plugin JARs
        dependenciesFromClassContext(
            McpScriptContext::class,
            wholeClasspath = true
        )
    }

    // Accept this script type in the IDE
    ide {
        acceptedLocations(ScriptAcceptedLocation.Everywhere)
    }

    // Provide implicit receivers/bindings type information
    providedProperties(
        "execute" to KotlinType("(suspend com.jonnyzzz.intellij.mcp.execution.McpScriptContext.() -> Unit) -> Unit")
    )
})
