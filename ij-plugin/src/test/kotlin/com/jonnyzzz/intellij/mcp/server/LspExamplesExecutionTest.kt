/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.server

import com.intellij.openapi.components.service
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.intellij.mcp.execution.ExecutionManager
import com.jonnyzzz.intellij.mcp.mcp.ContentItem
import com.jonnyzzz.intellij.mcp.mcp.ToolCallResult
import com.jonnyzzz.intellij.mcp.testExecParams
import kotlin.time.Duration.Companion.seconds

class LspExamplesExecutionTest : BasePlatformTestCase() {
    private val handler = LspExamplesResourceHandler()
    private lateinit var sampleFilePath: String
    private lateinit var positions: SamplePositions

    override fun setUp() {
        super.setUp()
        setRegistryPropertyForTest("mcp.steroids.review.mode", "NEVER")
        val sampleText = """
            package sample

            class Greeter(val name: String) {
                fun greet(times: Int): String {
                    return "Hello, ${'$'}name".repeat(times)
                }
            }

            fun main() {
                val greeter = Greeter("World")
                val message = greeter.greet(2)
                println(message)
            }
        """.trimIndent()
        val psiFile = myFixture.addFileToProject("src/sample/LspSample.kt", sampleText)
        sampleFilePath = psiFile.virtualFile.path
        positions = SamplePositions(
            classDeclaration = lineColumnFor(sampleText, "class Greeter", "class ".length),
            classUsage = lineColumnFor(sampleText, "Greeter(\"World\")"),
            methodDeclaration = lineColumnFor(sampleText, "fun greet", "fun ".length),
            methodCallName = lineColumnFor(sampleText, "greeter.greet(2)", "greeter.".length),
            methodCallArg = lineColumnFor(sampleText, "greeter.greet(2)", "greeter.greet(".length),
            messageUsage = lineColumnFor(sampleText, "println(message)", "println(".length),
            codeActionTarget = lineColumnFor(sampleText, "return \"Hello", "return ".length),
        )
    }

    private data class LineColumn(val line: Int, val column: Int)

    private data class SamplePositions(
        val classDeclaration: LineColumn,
        val classUsage: LineColumn,
        val methodDeclaration: LineColumn,
        val methodCallName: LineColumn,
        val methodCallArg: LineColumn,
        val messageUsage: LineColumn,
        val codeActionTarget: LineColumn,
    )

    private fun lineColumnAt(text: String, offset: Int): LineColumn {
        require(offset >= 0) { "Offset must be >= 0" }
        val line = text.substring(0, offset).count { it == '\n' } + 1
        val lineStart = text.lastIndexOf('\n', offset - 1).let { if (it == -1) 0 else it + 1 }
        val column = offset - lineStart + 1
        return LineColumn(line, column)
    }

    private fun lineColumnFor(text: String, needle: String, offsetInNeedle: Int = 0): LineColumn {
        val start = text.indexOf(needle)
        require(start >= 0) { "Needle not found: $needle" }
        return lineColumnAt(text, start + offsetInNeedle)
    }

    private fun configureExample(
        code: String,
        filePath: String? = null,
        line: Int? = null,
        column: Int? = null,
        query: String? = null,
        searchType: String? = null,
        newName: String? = null,
    ): String {
        var updated = code
        if (filePath != null) {
            updated = updated.replace(Regex("val filePath = \".*?\""), "val filePath = \"$filePath\"")
        }
        if (line != null) {
            updated = updated.replace(Regex("val line\\s*=\\s*\\d+"), "val line = $line")
        }
        if (column != null) {
            updated = updated.replace(Regex("val column\\s*=\\s*\\d+"), "val column = $column")
        }
        if (query != null) {
            updated = updated.replace(Regex("val query = \".*?\""), "val query = \"$query\"")
        }
        if (searchType != null) {
            updated = updated.replace(Regex("val searchType = \".*?\""), "val searchType = \"$searchType\"")
        }
        if (newName != null) {
            updated = updated.replace(Regex("val newName = \".*?\""), "val newName = \"$newName\"")
        }
        return updated
    }

    private fun executeExample(exampleId: String, code: String): ToolCallResult {
        val manager = project.service<ExecutionManager>()
        return manager.executeWithProgress(
            testExecParams(code, taskId = "lsp-$exampleId", reason = "lsp example"),
            NoOpProgressReporter
        )
    }

    private fun getTextContent(result: ToolCallResult): String {
        return result.content.filterIsInstance<ContentItem.Text>().joinToString("\n") { it.text }
    }

    fun testGoToDefinitionExampleExecutes(): Unit = timeoutRunBlocking(60.seconds) {
        val raw = handler.loadExample("/lsp-examples/go-to-definition.kts")
        val code = configureExample(
            raw,
            filePath = sampleFilePath,
            line = positions.classUsage.line,
            column = positions.classUsage.column
        )

        val result = executeExample("go-to-definition", code)

        assertTrue("Should execute without error", !result.isError)
        val text = getTextContent(result)
        assertTrue("Should include output header", text.contains("Definition"))
    }

    fun testFindReferencesExampleExecutes(): Unit = timeoutRunBlocking(60.seconds) {
        val raw = handler.loadExample("/lsp-examples/find-references.kts")
        val code = configureExample(
            raw,
            filePath = sampleFilePath,
            line = positions.methodDeclaration.line,
            column = positions.methodDeclaration.column
        )

        val result = executeExample("find-references", code)

        assertTrue("Should execute without error", !result.isError)
        val text = getTextContent(result)
        assertTrue("Should mention references", text.contains("references", ignoreCase = true))
    }

    fun testHoverExampleExecutes(): Unit = timeoutRunBlocking(60.seconds) {
        val raw = handler.loadExample("/lsp-examples/hover.kts")
        val code = configureExample(
            raw,
            filePath = sampleFilePath,
            line = positions.messageUsage.line,
            column = positions.messageUsage.column
        )

        val result = executeExample("hover", code)

        assertTrue("Should execute without error", !result.isError)
        val text = getTextContent(result)
        assertTrue("Should include hover header", text.contains("Hover Information"))
    }

    fun testCompletionExampleExecutes(): Unit = timeoutRunBlocking(60.seconds) {
        val raw = handler.loadExample("/lsp-examples/completion.kts")
        val code = configureExample(
            raw,
            filePath = sampleFilePath,
            line = positions.methodCallName.line,
            column = positions.methodCallName.column
        )

        val result = executeExample("completion", code)

        assertTrue("Should execute without error", !result.isError)
        val text = getTextContent(result)
        assertTrue("Should include completion header", text.contains("Completion at"))
    }

    fun testDocumentSymbolsExampleExecutes(): Unit = timeoutRunBlocking(60.seconds) {
        val raw = handler.loadExample("/lsp-examples/document-symbols.kts")
        val code = configureExample(raw, filePath = sampleFilePath)

        val result = executeExample("document-symbols", code)

        assertTrue("Should execute without error", !result.isError)
        val text = getTextContent(result)
        assertTrue("Should include document symbols header", text.contains("Document Symbols"))
    }
}
