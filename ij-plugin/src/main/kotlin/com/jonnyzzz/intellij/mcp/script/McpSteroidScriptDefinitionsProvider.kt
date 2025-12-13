/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.script

import com.intellij.openapi.diagnostic.Logger
import kotlin.script.experimental.intellij.ScriptDefinitionsProvider
import java.io.File

/**
 * Provides script definitions for MCP Steroid .kts files.
 *
 * This extension point tells the Kotlin plugin how to analyze our review.kts files,
 * providing proper classpath so IntelliJ APIs are recognized without errors.
 *
 * Registered via plugin.xml as org.jetbrains.kotlin.scriptDefinitionsProvider
 */
class McpSteroidScriptDefinitionsProvider : ScriptDefinitionsProvider {
    private val log = Logger.getInstance(McpSteroidScriptDefinitionsProvider::class.java)

    override val id: String = "McpSteroidScriptDefinitionsProvider"

    override fun getDefinitionClasses(): Iterable<String> {
        log.info("Providing MCP Steroid script definition class")
        return listOf(McpSteroidScript::class.qualifiedName!!)
    }

    override fun getDefinitionsClassPath(): Iterable<File> {
        // Return the classpath that contains our script definition class
        // The Kotlin plugin will use this to load the definition
        val classLoader = McpSteroidScriptDefinitionsProvider::class.java.classLoader
        val classpath = mutableListOf<File>()

        // Get JAR/directory containing our plugin classes
        val resourcePath = McpSteroidScriptDefinitionsProvider::class.java.name.replace('.', '/') + ".class"
        val resource = classLoader.getResource(resourcePath)

        if (resource != null) {
            val path = resource.path
            when {
                path.contains("!") -> {
                    // JAR file - extract the JAR path
                    val jarPath = path.substringBefore("!").removePrefix("file:")
                    classpath.add(File(jarPath))
                }
                else -> {
                    // Directory (development mode) - go up to find the classes root
                    val classesDir = File(path).parentFile
                        ?.parentFile?.parentFile?.parentFile?.parentFile?.parentFile?.parentFile
                    if (classesDir != null && classesDir.exists()) {
                        classpath.add(classesDir)
                    }
                }
            }
        }

        log.info("Script definitions classpath: $classpath")
        return classpath
    }

    override fun useDiscovery(): Boolean = false
}
