/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.execution

import com.intellij.openapi.components.service
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.intellij.mcp.testExecParams
import java.nio.file.Files
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

    fun testSuccessFileAndWrappedScriptCreated(): Unit = timeoutRunBlocking(30.seconds) {
        val code = "execute { println(\"Success Test\") }"
        val result = manager.executeWithProgress(testExecParams(code))

        assertFalse("Execution should not fail", result.isError)

        // Find the execution directory
        val mcpRunDir = project.basePath
            ?.let { java.nio.file.Path.of(it, ".idea", "mcp-run") }
            ?: error("No project path found")

        val latestExecDir = Files.list(mcpRunDir).filter { Files.isDirectory(it) }
            .sorted { a, b -> b.fileName.toString().compareTo(a.fileName.toString()) }
            .findFirst().orElse(null)

        assertNotNull("Execution directory should exist", latestExecDir)
        
        val successFile = latestExecDir.resolve("success.txt")
        assertTrue("success.txt should exist", successFile.exists())
        assertEquals("SUCCESS", successFile.readText())

        val wrappedScript = latestExecDir.resolve("script-wrapped.kts")
        assertTrue("script-wrapped.kts should exist", wrappedScript.exists())
        assertTrue("wrapped script should contain imports", wrappedScript.readText().contains("import com.intellij.openapi.project.*"))
    }

    fun testCompilationErrorInOutputJsonl(): Unit = timeoutRunBlocking(30.seconds) {
        val invalidCode = "invalid kotlin code here"
        val result = manager.executeWithProgress(testExecParams(invalidCode))

        assertTrue("Execution should fail", result.isError)

        // Find the execution directory
        val mcpRunDir = project.basePath
            ?.let { java.nio.file.Path.of(it, ".idea", "mcp-run") }
            ?: error("No project path found")

        val latestExecDir = Files.list(mcpRunDir).filter { Files.isDirectory(it) }
            .sorted { a, b -> b.fileName.toString().compareTo(a.fileName.toString()) }
            .findFirst().orElse(null)

        assertNotNull("Execution directory should exist", latestExecDir)

        val outputJsonl = latestExecDir.resolve("output.jsonl")
        assertTrue("output.jsonl should exist", outputJsonl.exists())
        
        val outputContent = outputJsonl.readText()
        assertTrue("output.jsonl should contain FAILED message", outputContent.contains("FAILED"))
        assertTrue("output.jsonl should contain compilation error info", 
            outputContent.contains("Script compilation/evaluation failed") || outputContent.contains("Compiler Errors/Warnings"))
    }
}
