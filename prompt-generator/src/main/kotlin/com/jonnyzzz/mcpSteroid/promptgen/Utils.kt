/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.promptgen

fun String.toPromptIdentifierName(): String {
    return toPromptClassName().replaceFirstChar { it.lowercase() }
}

fun String.toPromptClassName(): String {
    return split("-", "_", ".")
        .map { if (it.all { it.isUpperCase() }) it.lowercase() else it }
        .map { it.titleCase() }
        .map { if (it.equals("intellij", ignoreCase = true)) "IntelliJ" else it }
        .joinToString("")
}

fun String.titleCase() = replaceFirstChar { it.titlecase() }
