/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.execution

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service

inline val codeButcher : CodeButcher get() = service()

@Service(Service.Level.APP)
class CodeButcher {
    /**
     * Wrap user code with imports and execute binding.
     * This is exposed so the review can show the final code.
     */
    fun wrapWithImports(code: String): String {
        val importLines = mutableListOf<String>()
        val otherLines = mutableListOf<String>()

        for (line in code.lineSequence()) {
            if (line.trim().trimStart(';').trim().startsWith("import ")) {
                importLines.add(line)
            } else {
                otherLines.add(line)
            }
        }

        return buildString {
            appendLine("import com.intellij.openapi.project.*")
            appendLine("import com.intellij.openapi.application.*")
            appendLine("import com.intellij.openapi.application.readAction")
            appendLine("import com.intellij.openapi.application.writeAction")
            appendLine("import com.intellij.openapi.vfs.*")
            appendLine("import com.intellij.openapi.editor.*")
            appendLine("import com.intellij.openapi.fileEditor.*")
            appendLine("import com.intellij.openapi.command.*")
            appendLine("import com.intellij.psi.*")
            appendLine("import kotlinx.coroutines.*")
            appendLine("import kotlin.time.Duration.Companion.seconds")
            appendLine("import kotlin.time.Duration.Companion.minutes")
            appendLine()
            appendLine("//imports from the submitted code")
            importLines.forEach { appendLine(it) }

            // Bridge the script binding to a strongly-typed function in the script scope
            appendLine("val execute = bindings[\"execute\"] as (suspend com.jonnyzzz.intellij.mcp.execution.McpScriptContext.() -> Unit) -> Unit")
            appendLine()
            appendLine()
            appendLine("//the rest of submitted code")
            otherLines.forEach { appendLine(it) }
        }
    }
}
