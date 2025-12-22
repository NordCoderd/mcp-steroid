/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.execution

import com.intellij.openapi.components.service
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.intellij.mcp.mcp.ToolCallResult
import com.jonnyzzz.intellij.mcp.testExecParams
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.time.Duration.Companion.seconds

class ExecutionStorageTaskTest : BasePlatformTestCase() {
    private val manager: ExecutionManager get() = project.service()

    override fun setUp() {
        super.setUp()
        // Ensure review mode is NEVER for tests
        com.intellij.openapi.util.registry.Registry.get("mcp.steroids.review.mode").setValue("NEVER")
    }

    /**
     * Extracts the execution ID from the ToolCallResult's structuredContent.
     * The structuredContent is a JSON array containing ExecutionInfo objects.
     */
    private fun getExecutionIdFromResult(result: ToolCallResult): String {
        val structuredContent = result.structuredContent
            ?: error("No structuredContent in result")

        val array = structuredContent.jsonArray
        val executionInfo = array.firstOrNull {
            it.jsonObject.containsKey("executionId")
        } ?: error("No executionId found in structuredContent: $structuredContent")

        return executionInfo.jsonObject["executionId"]?.jsonPrimitive?.content
            ?: error("executionId is null")
    }

    /**
     * Gets the execution directory path for a given execution ID.
     */
    private fun getExecutionDir(executionId: String): Path {
        val mcpRunDir = project.basePath
            ?.let { Path.of(it, ".idea", "mcp-run") }
            ?: error("No project path found")

        return mcpRunDir.resolve(executionId)
    }

    fun testSuccessFileAndWrappedScriptCreated(): Unit = timeoutRunBlocking(30.seconds) {
        val code = "execute { println(\"Success Test\") }"
        val result = manager.executeWithProgress(testExecParams(code))

        assertFalse("Execution should not fail", result.isError)

        val executionId = getExecutionIdFromResult(result)
        val execDir = getExecutionDir(executionId)

        assertTrue("Execution directory should exist: $execDir", execDir.exists())

        val successFile = execDir.resolve("success.txt")
        assertTrue("success.txt should exist", successFile.exists())
        assertEquals("Execution successful", successFile.readText())

        val wrappedScript = execDir.resolve("script-wrapped.kts")
        assertTrue("script-wrapped.kts should exist", wrappedScript.exists())
        assertTrue("wrapped script should contain imports", wrappedScript.readText().contains("import com.intellij.openapi.project.*"))
    }

    fun testCompilationErrorInOutputJsonl(): Unit = timeoutRunBlocking(30.seconds) {
        val invalidCode = "invalid kotlin code here"
        val result = manager.executeWithProgress(testExecParams(invalidCode))

        assertTrue("Execution should fail", result.isError)

        val executionId = getExecutionIdFromResult(result)
        val execDir = getExecutionDir(executionId)

        assertTrue("Execution directory should exist: $execDir", execDir.exists())

        val outputJsonl = execDir.resolve("output.jsonl")
        assertTrue("output.jsonl should exist", outputJsonl.exists())

        val outputContent = outputJsonl.readText()
        assertTrue("output.jsonl should contain FAILED message", outputContent.contains("FAILED"))
        assertTrue(
            "output.jsonl should contain compilation error info:\n$outputContent",
            outputContent.contains("Script compilation/evaluation failed") || outputContent.contains("Compiler Errors/Warnings"))
    }
}
