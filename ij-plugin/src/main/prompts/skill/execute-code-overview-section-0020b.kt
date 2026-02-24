/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
val pluginId = PluginId.getId("org.jetbrains.kotlin")
val installed = PluginManagerCore.isPluginInstalled(pluginId)
println("Plugin installed: $installed")
// If not installed: report the plugin ID and stop — do NOT attempt programmatic installation
