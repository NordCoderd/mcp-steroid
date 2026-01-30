/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.execution

import com.intellij.openapi.components.service
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.intellij.mcp.getExecutionIdFromResult
import com.jonnyzzz.intellij.mcp.setServerPortProperties
import com.jonnyzzz.intellij.mcp.testExecParams
import com.jonnyzzz.intellij.mcp.storage.storagePaths
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.time.Duration.Companion.seconds

class ExecutionStorageTaskTest : BasePlatformTestCase() {
    private val manager: ExecutionManager get() = project.service()

    override fun setUp() {
        setServerPortProperties()
        super.setUp()
    }

    /**
     * Gets the execution directory path for a given execution ID.
     */
    private fun getExecutionDir(executionId: String): Path {
        return project.storagePaths.getGetMcpRunDir().resolve(executionId)
    }

    fun testSuccessFileAndWrappedScriptCreated(): Unit = timeoutRunBlocking(30.seconds) {
        val code = "println(\"Success Test\")"
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
