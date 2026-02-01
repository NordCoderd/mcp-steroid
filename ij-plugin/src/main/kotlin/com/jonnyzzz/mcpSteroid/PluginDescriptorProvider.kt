/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid

import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.util.JDOMUtil

/**
 * Provides access to the plugin descriptor for this plugin.
 *
 * Reads plugin ID from plugin.xml, then resolves the full descriptor
 * from PluginManagerCore. All property access goes through the descriptor.
 *
 * Usage:
 * ```kotlin
 * val provider = PluginDescriptorProvider.getInstance()
 * val descriptor = provider.descriptor
 * println("Version: ${descriptor.version}")
 * println("Plugin ID: ${descriptor.pluginId}")
 * ```
 */
@Service(Service.Level.APP)
class PluginDescriptorProvider {

    /**
     * The plugin descriptor for this plugin.
     *
     * This reads fresh values every time - not cached.
     *
     * @throws IllegalStateException if the plugin cannot be found
     */
    val descriptor: IdeaPluginDescriptor
        get() {
            val id = resolvePluginIdFromXml()
            require(id.isNotBlank()) { "Plugin ID not found in plugin.xml" }
            return PluginManagerCore.getPlugin(PluginId.getId(id))
                ?: error("Plugin descriptor not found for id=$id. This should not happen in production or tests.")
        }

    /** The plugin version from the descriptor */
    val version: String get() = descriptor.version

    /** The plugin ID string from the descriptor */
    val pluginId: String get() = descriptor.pluginId.idString

    /** The plugin name from the descriptor */
    val name: String get() = descriptor.name

    private fun resolvePluginIdFromXml(): String {
        val classLoader = javaClass.classLoader
        val stream = classLoader.getResourceAsStream("META-INF/plugin.xml")
            ?: error("plugin.xml not found in classpath")
        val root = stream.use { JDOMUtil.load(it) }
        val value = root.getChildTextTrim("id")
        return value ?: error("Plugin ID not found in plugin.xml")
    }

    companion object {
        @JvmStatic
        fun getInstance(): PluginDescriptorProvider = service()
    }
}
