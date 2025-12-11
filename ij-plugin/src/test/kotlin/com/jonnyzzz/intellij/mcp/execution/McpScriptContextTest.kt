/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.execution

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.intellij.mcp.storage.ExecutionParams
import com.jonnyzzz.intellij.mcp.storage.ExecutionStorage
import com.jonnyzzz.intellij.mcp.storage.OutputType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Test

/**
 * Tests for McpScriptContext implementation.
 */
class McpScriptContextTest : BasePlatformTestCase() {

    private lateinit var storage: ExecutionStorage
    private lateinit var testScope: CoroutineScope
    private lateinit var executionId: String

    override fun setUp() {
        super.setUp()
        storage = project.service()
        testScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        val code = "test"
        val params = ExecutionParams()
        executionId = storage.generateExecutionId(code, params)
        storage.createExecution(executionId, code, params)
    }

    override fun tearDown() {
        try {
            testScope.cancel()
        } finally {
            super.tearDown()
        }
    }

    @Test
    fun testPrintln() {
        val context = McpScriptContextImpl(
            project = project,
            executionId = executionId,
            executionStorage = storage,
            parentScope = testScope
        )

        context.println("Hello World")
        context.println(42)
        context.println(null)

        val output = storage.readOutput(executionId)
        assertEquals("Should have 3 messages", 3, output.size)
        assertEquals("Hello World", output[0].msg)
        assertEquals(OutputType.OUT, output[0].type)
        assertEquals("42", output[1].msg)
        assertEquals("null", output[2].msg)

        context.dispose()
    }

    @Test
    fun testLogMethods() {
        val context = McpScriptContextImpl(
            project = project,
            executionId = executionId,
            executionStorage = storage,
            parentScope = testScope
        )

        context.logInfo("Info message")
        context.logWarn("Warning message")
        context.logError("Error message")

        val output = storage.readOutput(executionId)
        assertEquals("Should have 3 log messages", 3, output.size)

        assertEquals(OutputType.LOG, output[0].type)
        assertEquals("info", output[0].level)
        assertEquals("Info message", output[0].msg)

        assertEquals(OutputType.LOG, output[1].type)
        assertEquals("warn", output[1].level)

        assertEquals(OutputType.LOG, output[2].type)
        assertEquals("error", output[2].level)

        context.dispose()
    }

    @Test
    fun testLogErrorWithThrowable() {
        val context = McpScriptContextImpl(
            project = project,
            executionId = executionId,
            executionStorage = storage,
            parentScope = testScope
        )

        val exception = RuntimeException("Test error")
        context.logError("Something failed", exception)

        val output = storage.readOutput(executionId)
        assertEquals(1, output.size)
        assertTrue(output[0].msg.contains("Something failed"))
        assertTrue(output[0].msg.contains("Test error"))
        assertTrue(output[0].msg.contains("RuntimeException"))

        context.dispose()
    }

    @Test
    fun testDescribeClass() {
        val context = McpScriptContextImpl(
            project = project,
            executionId = executionId,
            executionStorage = storage,
            parentScope = testScope
        )

        val description = context.describeClass("java.lang.String")

        assertTrue(description.contains("Class: java.lang.String"))
        assertTrue(description.contains("Public Methods:"))
        assertTrue(description.contains("length()"))
        assertTrue(description.contains("charAt("))

        context.dispose()
    }

    @Test
    fun testDescribeClassNotFound() {
        val context = McpScriptContextImpl(
            project = project,
            executionId = executionId,
            executionStorage = storage,
            parentScope = testScope
        )

        val description = context.describeClass("com.nonexistent.ClassName")

        assertTrue(description.contains("Class not found"))

        context.dispose()
    }

    @Test
    fun testDispose() {
        val context = McpScriptContextImpl(
            project = project,
            executionId = executionId,
            executionStorage = storage,
            parentScope = testScope
        )

        assertFalse(context.coroutineScope.coroutineContext[kotlinx.coroutines.Job]?.isCancelled ?: true)

        context.dispose()

        // After dispose, the coroutine scope should be cancelled
        assertTrue(context.coroutineScope.coroutineContext[kotlinx.coroutines.Job]?.isCancelled ?: false)
    }

    @Test
    fun testProjectAccess() {
        val context = McpScriptContextImpl(
            project = project,
            executionId = executionId,
            executionStorage = storage,
            parentScope = testScope
        )

        assertEquals(project, context.project)
        assertEquals(executionId, context.executionId)

        context.dispose()
    }
}
