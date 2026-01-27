/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.execution

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service

inline val codeButcher: CodeButcher get() = service()

private val mcpScriptContextFqn = McpScriptContext::class.java.name
private val mcpScriptBuilderFqn = McpScriptBuilder::class.java.name
private val mcpScriptBuilderAddBlock = McpScriptBuilder::addBlock.name
private const val mcpScriptMethodName = "jonnyzzz_execute_all_script_content_77" //make it unlikely to clash with code

@Service(Service.Level.APP)
class CodeButcher {
    data class ScriptCoordinates(
        val classFqn: String,
        val methodName: String = mcpScriptMethodName,
        val code: String,
    )

    /**
     * Wrap user code with imports and execute binding.
     * This is exposed so the review can show the final code.
     */
    fun wrapToKotlinClass(scriptClassName: String, code: String): ScriptCoordinates {
        val clazzName = scriptClassName.replace("[^a-z0-9_]+".toRegex(RegexOption.IGNORE_CASE), "_")

        val importLines = mutableListOf<String>()
        val otherLines = mutableListOf<String>()
        for (line in code.lineSequence()) {
            if (line.trim().trimStart(';').trim().startsWith("import ")) {
                importLines.add(line)
            } else {
                otherLines.add(line)
            }
        }

        val code = buildString {
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
            appendLine()
            appendLine("class $clazzName {")
            appendLine("  inline fun $mcpScriptContextFqn.execute(ƒ: $mcpScriptContextFqn.() -> Unit) = ƒ()")
            appendLine("  fun $mcpScriptMethodName(builder : $mcpScriptBuilderFqn) { ")
            appendLine("    builder.$mcpScriptBuilderAddBlock { ${mcpScriptMethodName}_code() }")
            appendLine("  }")
            appendLine("  suspend fun $mcpScriptContextFqn.${mcpScriptMethodName}_code() {")
            appendLine("    //the rest of submitted code")
            otherLines.forEach { append("    ").appendLine(it) }
            appendLine("  }")
            appendLine("}")
            appendLine()
        }

        return ScriptCoordinates(classFqn = clazzName, code = code)
    }
}
