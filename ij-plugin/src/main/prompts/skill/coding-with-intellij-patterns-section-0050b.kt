/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId

// Check if a plugin is installed (and loaded) — use this BEFORE calling any plugin-specific API
val pluginId = PluginId.getId("com.intellij.database")  // replace with the plugin you need
val installed = PluginManagerCore.isPluginInstalled(pluginId)
val loaded = PluginManagerCore.getPlugin(pluginId) != null
println("Plugin $pluginId: installed=$installed loaded=$loaded")

// If not loaded: do NOT attempt installation. Instead, report the missing plugin ID and stop.
// The steroid_execute_code `required_plugins` parameter is the correct way to declare dependencies.
