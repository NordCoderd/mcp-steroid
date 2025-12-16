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
    fun wrapWithImports(code: String): String = buildString {
        appendLine(
            """
            import com.intellij.openapi.project.*
            import com.intellij.openapi.application.*
            import com.intellij.openapi.application.readAction
            import com.intellij.openapi.application.writeAction
            import com.intellij.openapi.vfs.*
            import com.intellij.openapi.editor.*
            import com.intellij.openapi.fileEditor.*
            import com.intellij.openapi.command.*
            import com.intellij.psi.*
            import kotlinx.coroutines.*
            """.trimIndent()
        )
        appendLine()
        // Bridge the script binding to a strongly-typed function in the script scope
        appendLine("val execute = bindings[\"execute\"] as (suspend com.jonnyzzz.intellij.mcp.execution.McpScriptContext.() -> Unit) -> Unit")
        appendLine()
        append(code)
    }
}
