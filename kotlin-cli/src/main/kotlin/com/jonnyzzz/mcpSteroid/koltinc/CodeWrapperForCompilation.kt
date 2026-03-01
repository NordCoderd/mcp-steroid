/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.koltinc

/**
 * Wraps Kotlin code into a compilable class with imports and execution binding.
 *
 * This is the shared implementation used by both:
 * - `CodeButcher` in ij-plugin (for runtime script execution)
 * - `KtBlockCompilationTestBase` in prompts (for compilation-only testing)
 *
 * The caller supplies the FQNs for McpScriptContext and McpScriptBuilder since
 * kotlin-cli doesn't depend on ij-plugin and can't resolve them via reflection.
 */
object CodeWrapperForCompilation {
    val defaultImports = listOf(
        "import com.intellij.openapi.project.*",
        "import com.intellij.openapi.application.*",
        "import com.intellij.openapi.application.readAction",
        "import com.intellij.openapi.application.writeAction",
        "import com.intellij.openapi.vfs.*",
        "import com.intellij.openapi.editor.*",
        "import com.intellij.openapi.fileEditor.*",
        "import com.intellij.openapi.command.*",
        "import com.intellij.psi.*",
        "import kotlinx.coroutines.*",
        "import kotlin.time.Duration.Companion.seconds",
        "import kotlin.time.Duration.Companion.minutes",
    )

    const val DEFAULT_SCRIPT_CONTEXT_FQN = "com.jonnyzzz.mcpSteroid.execution.McpScriptContext"
    const val DEFAULT_SCRIPT_BUILDER_FQN = "com.jonnyzzz.mcpSteroid.execution.McpScriptBuilder"
    const val DEFAULT_ADD_BLOCK_NAME = "addBlock"
    const val DEFAULT_METHOD_NAME = "jonnyzzz_execute_all_script_content_77"

    data class WrapResult(
        val classFqn: String,
        val methodName: String,
        val code: String,
    )

    /**
     * Extracts import lines from code while respecting triple-quoted strings,
     * and returns (importLines, otherLines).
     */
    fun extractImports(code: String): Pair<List<String>, List<String>> {
        val importLines = mutableListOf<String>()
        val otherLines = mutableListOf<String>()
        var tripleQuoteCount = 0
        for (line in code.lineSequence()) {
            val inTripleQuotedString = tripleQuoteCount % 2 != 0
            var idx = 0
            while (idx <= line.length - 3) {
                if (line[idx] == '"' && line[idx + 1] == '"' && line[idx + 2] == '"') {
                    tripleQuoteCount++
                    idx += 3
                } else {
                    idx++
                }
            }
            if (!inTripleQuotedString && line.trim().trimStart(';').trim().startsWith("import ")) {
                importLines.add(line)
            } else {
                otherLines.add(line)
            }
        }
        return importLines to otherLines
    }

    /**
     * Wraps user code into a compilable Kotlin class.
     *
     * @param className base name for the generated class (sanitized internally)
     * @param code the user code to wrap
     * @param scriptContextFqn FQN of the McpScriptContext class
     * @param scriptBuilderFqn FQN of the McpScriptBuilder class
     * @param addBlockName name of the addBlock method on the builder
     * @param methodName name of the generated entry-point method
     */
    fun wrap(
        className: String,
        code: String,
        scriptContextFqn: String = DEFAULT_SCRIPT_CONTEXT_FQN,
        scriptBuilderFqn: String = DEFAULT_SCRIPT_BUILDER_FQN,
        addBlockName: String = DEFAULT_ADD_BLOCK_NAME,
        methodName: String = DEFAULT_METHOD_NAME,
    ): WrapResult {
        val clazzName = className.replace("[^a-z0-9_]+".toRegex(RegexOption.IGNORE_CASE), "_")
        val (importLines, otherLines) = extractImports(code)

        val wrappedCode = buildString {
            append(defaultImports.joinToString(separator = "\n", postfix = "\n"))
            appendLine()
            appendLine("//imports from the submitted code")
            importLines.forEach { appendLine(it) }
            appendLine()
            appendLine("class $clazzName {")
            appendLine("  inline fun $scriptContextFqn.execute(ƒ: $scriptContextFqn.() -> Unit) = ƒ()")
            appendLine("  fun $methodName(builder : $scriptBuilderFqn) { ")
            appendLine("    builder.$addBlockName { ${methodName}_code() }")
            appendLine("  }")
            appendLine("  suspend fun $scriptContextFqn.${methodName}_code() {")
            appendLine("    //the rest of submitted code")
            otherLines.forEach { append("    ").appendLine(it) }
            appendLine("  }")
            appendLine("}")
            append("\n")
        }

        return WrapResult(classFqn = clazzName, methodName = methodName, code = wrappedCode)
    }
}
