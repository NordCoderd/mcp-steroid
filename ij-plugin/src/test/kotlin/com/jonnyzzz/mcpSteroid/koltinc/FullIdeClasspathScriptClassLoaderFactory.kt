/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.koltinc

import com.intellij.openapi.components.service
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.walk

/**
 * A [ScriptClassLoaderFactory] for tests that includes ALL jars from the IDE home
 * (plugins/ and lib/ directories), ensuring that plugin-specific classes like
 */
class FullIdeClasspathScriptClassLoaderFactory {
    fun ideClasspath(): List<Path> {
        val home = System.getProperty("mcp.steroid.full.intellij") ?: error("Missing 'mcp.steroid.full.intellij'")
        val allJars = Path.of(home).walk().toList().filter { it.isRegularFile() && it.name.endsWith(".jar") }.toSortedSet()
        val ownClasspath = service<ScriptClassLoaderFactory>().ideClasspath()
        return (allJars + ownClasspath).distinct().toList()
    }
}
