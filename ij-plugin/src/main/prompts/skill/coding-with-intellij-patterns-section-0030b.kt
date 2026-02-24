/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
import com.intellij.ide.plugins.PluginManagerCore

// enabledPlugins lists all currently loaded plugins
PluginManagerCore.getPluginSet().enabledPlugins
    .forEach { println("${it.name}: ${it.version}") }
