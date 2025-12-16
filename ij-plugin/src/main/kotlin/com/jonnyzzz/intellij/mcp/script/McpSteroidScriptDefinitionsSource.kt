/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.script

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.jonnyzzz.intellij.mcp.execution.McpScriptContext
import org.jetbrains.kotlin.scripting.definitions.ScriptDefinition
import org.jetbrains.kotlin.scripting.definitions.ScriptDefinitionsSource
import kotlin.script.experimental.api.*
import kotlin.script.experimental.jvm.defaultJvmScriptingHostConfiguration
import kotlin.script.experimental.jvm.dependenciesFromClassContext
import kotlin.script.experimental.jvm.jvm

/**
 * Provides MCP Steroid script definitions directly to the Kotlin plugin.
 *
 * This implements ScriptDefinitionsSource which is the primary extension point
 * for script definitions in both K1 and K2 modes of the Kotlin plugin.
 */
class McpSteroidScriptDefinitionsSource(private val project: Project) : ScriptDefinitionsSource {
    private val log = Logger.getInstance(McpSteroidScriptDefinitionsSource::class.java)

    override val definitions: Sequence<ScriptDefinition>
        get() {
            log.info("Providing MCP Steroid script definitions for project: ${project.name}")
            return sequenceOf(createMcpSteroidDefinition())
        }

    private fun createMcpSteroidDefinition(): ScriptDefinition {
        val compilationConfiguration = ScriptCompilationConfiguration {
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
            jvm {
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

            // Display name for this script type
            displayName("MCP Steroid Script")

            // File extension
            fileExtension("kts")
        }

        val evaluationConfiguration = ScriptEvaluationConfiguration {
            // No special evaluation configuration needed for IDE analysis
        }

        return ScriptDefinition.FromConfigurations(
            defaultJvmScriptingHostConfiguration,
            compilationConfiguration,
            evaluationConfiguration
        ).apply {
            // Set order to be loaded after default definitions
            order = 100
        }
    }
}
