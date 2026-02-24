/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId

val pluginId = PluginId.getId("YOUR_PLUGIN_ID")
val plugin = PluginManagerCore.getPlugin(pluginId)

if (plugin != null) {
    println("Plugin loaded: ${plugin.name} v${plugin.version}")
} else {
    println("Plugin not loaded")
}
