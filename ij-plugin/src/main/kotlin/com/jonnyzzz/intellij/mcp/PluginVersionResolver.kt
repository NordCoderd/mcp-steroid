/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.util.JDOMUtil

/**
 * Service that resolves plugin version and ID from plugin.xml.
 *
 * Usage:
 * ```kotlin
 * val resolver = service<PluginVersionResolver>()
 * println("Version: ${resolver.version}")
 * println("Plugin ID: ${resolver.pluginId}")
 * ```
 */
@Service(Service.Level.APP)
class PluginVersionResolver {

    /** The plugin version from plugin.xml, or "unknown" if not found */
    val version: String by lazy { resolveFromPluginXml("version") ?: "unknown" }

    /** The plugin ID from plugin.xml, or "unknown" if not found */
    val pluginId: String by lazy { resolveFromPluginXml("id") ?: "unknown" }

    /** The plugin name from plugin.xml, or "unknown" if not found */
    val name: String by lazy { resolveFromPluginXml("name") ?: "unknown" }

    private fun resolveFromPluginXml(elementName: String): String? {
        val classLoader = javaClass.classLoader
        val stream = classLoader.getResourceAsStream("META-INF/plugin.xml") ?: return null
        val root = stream.use { JDOMUtil.load(it) }
        val value = root.getChildTextTrim(elementName)
        return if (value.isNullOrBlank()) null else value
    }

    companion object {
        @JvmStatic
        fun getInstance(): PluginVersionResolver = service()
    }
}
