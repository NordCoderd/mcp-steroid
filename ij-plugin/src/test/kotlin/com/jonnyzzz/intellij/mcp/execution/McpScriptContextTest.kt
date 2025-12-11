/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.execution

import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.intellij.mcp.server.ProgressReporter
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

    private fun createContext(): McpScriptContextImpl {
        val disposable = Disposer.newDisposable(testRootDisposable, "test-context-$executionId")
        Disposer.register(testRootDisposable, disposable)
        return McpScriptContextImpl(
            project = project,
            executionId = executionId,
            disposable = disposable,
            progressReporter = ProgressReporter.noOp(),
        )
    }

    fun testPrintlnVarargs() {
        val context = createContext()

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
    }

    fun testPrintlnSingleValue() {
        val context = createContext()

        context.println("Single value")
        context.println(123)

        val output = storage.readOutput(executionId)
        assertEquals(2, output.size)
        assertEquals("Single value", output[0].msg)
        assertEquals("123", output[1].msg)
    }

    fun testPrintJsonWithMap() {
        val context = createContext()

        context.printJson(mapOf("name" to "test", "count" to 42))

        val output = storage.readOutput(executionId)
        assertEquals(1, output.size)
        assertEquals(OutputType.JSON, output[0].type)
        // Jackson output should contain the keys
        assertTrue(output[0].msg.contains("\"name\""))
        assertTrue(output[0].msg.contains("\"test\""))
        assertTrue(output[0].msg.contains("\"count\""))
        assertTrue(output[0].msg.contains("42"))
    }

    fun testPrintJsonWithNull() {
        val context = createContext()

        context.printJson(null)

        val output = storage.readOutput(executionId)
        assertEquals(1, output.size)
        assertEquals("null", output[0].msg)
    }

    fun testLogMethods() {
        val context = createContext()

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
    }

    fun testLogErrorWithThrowable() {
        val context = createContext()

        val exception = RuntimeException("Test error")
        context.logError("Something failed", exception)

        val output = storage.readOutput(executionId)
        assertEquals(1, output.size)
        assertTrue(output[0].msg.contains("Something failed"))
        assertTrue(output[0].msg.contains("Test error"))
        assertTrue(output[0].msg.contains("RuntimeException"))
    }

    fun testDescribeClass() {
        val context = createContext()

        val description = (context as McpScriptContextEx).describeClass("java.lang.String")

        assertTrue(description.contains("Class: java.lang.String"))
        assertTrue(description.contains("Public Methods:"))
        assertTrue(description.contains("length()"))
        assertTrue(description.contains("charAt("))
    }

    fun testDescribeClassNotFound() {
        val context = createContext()

        val description = (context as McpScriptContextEx).describeClass("com.nonexistent.ClassName")
        assertTrue(description.contains("Class not found"))
    }

    fun testProjectAccess() {
        val context = createContext()

        assertEquals(project, context.project)
        assertEquals(executionId, context.executionId)
    }

    fun testDisposedContextRejectsOutput() {
        val context = createContext()
        //hack!
        Disposer.dispose(context.disposable)

        try {
            context.println("Should fail")
            fail("Should throw IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("disposed") == true)
        }
    }
}
