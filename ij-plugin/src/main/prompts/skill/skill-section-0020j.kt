/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
import com.intellij.lang.Language

// In IntelliJ 2025.3+, extensionArea.extensionPoints was removed.
// Use Language.getRegisteredLanguages() or specific ExtensionPointName instances instead.
Language.getRegisteredLanguages()
    .filter { it.displayName.contains("kotlin", ignoreCase = true) }
    .forEach { println("Language: ${it.displayName} (${it.id})") }
