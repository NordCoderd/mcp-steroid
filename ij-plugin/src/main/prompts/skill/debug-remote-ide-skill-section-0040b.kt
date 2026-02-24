/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
// This code runs IN the target IDE's JVM, not IntelliJ's
import com.intellij.openapi.project.ProjectManager
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId

// Get all open projects in target IDE
val projects = ProjectManager.getInstance().openProjects
println("Target IDE has ${projects.size} open projects:")
projects.forEach { p ->
    println("  - ${p.name} at ${p.basePath}")
}

// Check plugin status IN target IDE
val pluginId = PluginId.getId("YOUR_PLUGIN_ID")  // Replace with your plugin ID
val plugin = PluginManagerCore.getPlugin(pluginId)
println("Plugin:")
println("  Loaded: ${plugin != null}")
println("  Version: ${plugin?.version}")
