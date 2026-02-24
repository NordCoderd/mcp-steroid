/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
val plugin = com.intellij.ide.plugins.PluginManagerCore.loadedPlugins
    .find { it.pluginId.idString == "org.jetbrains.kotlin" }
println("Kotlin plugin: ${plugin?.version}")
