/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.execution

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.intellij.mcp.storage.ExecutionParams
import com.jonnyzzz.intellij.mcp.storage.ExecutionStorage
import com.jonnyzzz.intellij.mcp.storage.OutputType

/**
 * Tests for McpScriptContext implementation.
 */
class McpScriptContextTest : BasePlatformTestCase() {

    private lateinit var storage: ExecutionStorage
    private lateinit var executionId: String

    override fun setUp() {
        super.setUp()
        storage = project.service()

        val code = "test"
        val params = ExecutionParams()
        executionId = storage.generateExecutionId(code, params)
        storage.createExecution(executionId, code, params)
    }

    fun testPrintlnVarargs() {
        val context = McpScriptContextImpl(
            project = project,
            executionId = executionId,
            executionStorage = storage
        )

        // Test varargs println
        context.println("Hello", "World", 42)
        context.println()  // Empty line
        context.println(null, "test")

        val output = storage.readOutput(executionId)
        assertEquals("Should have 3 messages", 3, output.size)
        assertEquals("Hello World 42", output[0].msg)
        assertEquals(OutputType.OUT, output[0].type)
        assertEquals("", output[1].msg)  // Empty line
        assertEquals("null test", output[2].msg)

        context.dispose()
    }

    fun testPrintlnSingleValue() {
        val context = McpScriptContextImpl(
            project = project,
            executionId = executionId,
            executionStorage = storage
        )

        context.println("Single value")
        context.println(123)

        val output = storage.readOutput(executionId)
        assertEquals(2, output.size)
        assertEquals("Single value", output[0].msg)
        assertEquals("123", output[1].msg)

        context.dispose()
    }

    fun testPrintJsonWithMap() {
        val context = McpScriptContextImpl(
            project = project,
            executionId = executionId,
            executionStorage = storage
        )

        context.printJson(mapOf("name" to "test", "count" to 42))

        val output = storage.readOutput(executionId)
        assertEquals(1, output.size)
        assertEquals(OutputType.JSON, output[0].type)
        // Jackson output should contain the keys
        assertTrue(output[0].msg.contains("\"name\""))
        assertTrue(output[0].msg.contains("\"test\""))
        assertTrue(output[0].msg.contains("\"count\""))
        assertTrue(output[0].msg.contains("42"))

        context.dispose()
    }

    fun testPrintJsonWithNull() {
        val context = McpScriptContextImpl(
            project = project,
            executionId = executionId,
            executionStorage = storage
        )

        context.printJson(null)

        val output = storage.readOutput(executionId)
        assertEquals(1, output.size)
        assertEquals("null", output[0].msg)

        context.dispose()
    }

    fun testLogMethods() {
        val context = McpScriptContextImpl(
            project = project,
            executionId = executionId,
            executionStorage = storage
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

    fun testLogErrorWithThrowable() {
        val context = McpScriptContextImpl(
            project = project,
            executionId = executionId,
            executionStorage = storage
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

    fun testDescribeClass() {
        val context = McpScriptContextImpl(
            project = project,
            executionId = executionId,
            executionStorage = storage
        )

        val description = (context as McpScriptContextEx).describeClass("java.lang.String")

        assertTrue(description.contains("Class: java.lang.String"))
        assertTrue(description.contains("Public Methods:"))
        assertTrue(description.contains("length()"))
        assertTrue(description.contains("charAt("))

        context.dispose()
    }

    fun testDescribeClassNotFound() {
        val context = McpScriptContextImpl(
            project = project,
            executionId = executionId,
            executionStorage = storage
        )

        val description = (context as McpScriptContextEx).describeClass("com.nonexistent.ClassName")

        assertTrue(description.contains("Class not found"))

        context.dispose()
    }

    fun testProjectAccess() {
        val context = McpScriptContextImpl(
            project = project,
            executionId = executionId,
            executionStorage = storage
        )

        assertEquals(project, context.project)
        assertEquals(executionId, context.executionId)

        context.dispose()
    }
}
