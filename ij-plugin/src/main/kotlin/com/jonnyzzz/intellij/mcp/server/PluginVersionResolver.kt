/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.server

import com.intellij.openapi.util.JDOMUtil

object PluginVersionResolver {
    fun resolve(classLoader: ClassLoader): String {
        val stream = classLoader.getResourceAsStream("META-INF/plugin.xml") ?: return "unknown"
        val root = stream.use { JDOMUtil.load(it) }
        val version = root.getChildTextTrim("version")
        return if (version.isNullOrBlank()) "unknown" else version
    }
}
