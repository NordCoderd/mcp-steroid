/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.server

object PluginVersionResolver {
    private val versionRegex = Regex("<version>([^<]+)</version>")

    fun resolve(classLoader: ClassLoader): String {
        val text = classLoader.getResourceAsStream("META-INF/plugin.xml")
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: return "unknown"

        return versionRegex.find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
            ?: "unknown"
    }
}
