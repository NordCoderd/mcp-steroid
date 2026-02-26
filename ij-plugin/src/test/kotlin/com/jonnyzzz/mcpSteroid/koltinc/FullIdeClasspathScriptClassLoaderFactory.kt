/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.koltinc

import com.intellij.openapi.components.service
import com.intellij.util.lang.PathClassLoader
import com.jonnyzzz.mcpSteroid.execution.ScriptExecutor
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.walk

/**
 * A helper for tests that includes ALL jars from the IDE home
 * (plugins/ and lib/ directories), ensuring that plugin-specific classes like
 */
object FullIdeClasspathScriptClassLoaderFactory {
    fun ideClasspath(): List<Path> = ideClasspathCache

    private val ideClasspathCache : List<Path> by lazy {
        val home = System.getProperty("mcp.steroid.full.intellij") ?: error("Missing 'mcp.steroid.full.intellij'")
        val allJars = Path.of(home)
            .walk()
            .toList()
            .filter { it.name.endsWith(".jar") }

        val ownClasspath = service<ScriptClassLoaderFactory>().ideClasspath()

        val fromPath = listOf(javaClass, ScriptExecutor::class.java)
            .mapNotNull {
                runCatching {
                    @Suppress("UnstableApiUsage")
                    (javaClass.classLoader as? PathClassLoader)?.classPath?.files
                }.getOrNull()
            }.flatten()

        val fullList = listOfNotNull(
            allJars,
            ownClasspath,
            fromPath
        )
            .flatten()
            .filter { it.isRegularFile() || it.isDirectory() }
            .distinct()
            .toList()

        //reorder classpath a bit to speed up it, potentially
        val steroid = fullList.filter { it.toString().contains("plugins-test/mcp-steroid") }
        val ij = fullList.filter { it.toString().contains("lib") && !it.toString().contains("plugin") }
        val gradle = fullList.filter { it.toString().contains(".gradle") }

        listOf(steroid, ij, gradle, fullList).flatten().distinct()
    }
}
