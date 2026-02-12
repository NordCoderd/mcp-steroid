/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.filter

import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodexJsonFilterTest {
    private val filter = CodexJsonFilter()

    @Test
    fun `test agent_message item completed`() {
        val input = """{"type":"item.completed","item":{"type":"agent_message","text":"Hello world\nSecond line"}}"""
        val output = runFilter(input)
        assertEquals("Hello world\nSecond line\n", output)
    }

    @Test
    fun `test command_execution item started`() {
        val input = """{"type":"item.started","item":{"type":"command_execution","command":"ls -la"}}"""
        val output = runFilter(input)
        assertEquals(">> ls -la\n", output)
    }

    @Test
    fun `test command_execution item completed with output`() {
        val input = """{"type":"item.completed","item":{"type":"command_execution","command":"echo test","output":"test","exit_code":0}}"""
        val output = runFilter(input)
        assertEquals("  test\n", output)
    }

    @Test
    fun `test command_execution item completed with non-zero exit`() {
        val input = """{"type":"item.completed","item":{"type":"command_execution","command":"false","output":"","exit_code":1}}"""
        val output = runFilter(input)
        assertEquals(">> exit 1 (false)\n", output)
    }

    @Test
    fun `test tool_call item started with details`() {
        val input = """{"type":"item.started","item":{"type":"tool_call","name":"steroid_execute_code","input":{"reason":"Test execution"}}}"""
        val output = runFilter(input)
        assertEquals(">> steroid_execute_code (Test execution)\n", output)
    }

    @Test
    fun `test tool_call item started with long reason truncation`() {
        val longReason = "a".repeat(100)
        val input = """{"type":"item.started","item":{"type":"tool_call","name":"steroid_execute_code","input":{"reason":"$longReason"}}}"""
        val output = runFilter(input)
        assertTrue(output.contains(">> steroid_execute_code"))
        assertTrue(output.contains("..."))
    }

    @Test
    fun `test tool_call item completed with execution ID`() {
        val input = """{"type":"item.completed","item":{"type":"tool_call","name":"Bash","id":"exec_123","output":"Success"}}"""
        val output = runFilter(input)
        assertTrue(output.contains("<< Bash [exec_123]: Success"))
    }

    @Test
    fun `test tool_call with object input`() {
        // Test with input as a JSON object (most common case)
        val input = """{"type":"item.started","item":{"type":"tool_call","name":"Read","input":{"file_path":"/tmp/test.txt"}}}"""
        val output = runFilter(input)
        assertEquals(">> Read (/tmp/test.txt)\n", output)
    }

    @Test
    fun `test mcp_tool_call variant`() {
        val input = """{"type":"item.started","item":{"type":"mcp_tool_call","name":"read_mcp_resource","input":{"uri":"file:///test"}}}"""
        val output = runFilter(input)
        assertEquals(">> read_mcp_resource (file:///test)\n", output)
    }

    @Test
    fun `test function_call variant`() {
        val input = """{"type":"item.started","item":{"type":"function_call","function":{"name":"Grep"},"arguments":{"pattern":"test"}}}"""
        val output = runFilter(input)
        assertEquals(">> Grep (test)\n", output)
    }

    @Test
    fun `test turn completed with usage`() {
        val input = """{"type":"turn.completed","usage":{"input_tokens":100,"output_tokens":50}}"""
        val output = runFilter(input)
        assertEquals("[turn] in=100 out=50\n", output)
    }

    @Test
    fun `test error event with type`() {
        val input = """{"type":"error","error":{"type":"rate_limit","message":"Rate limit exceeded"}}"""
        val output = runFilter(input)
        assertEquals("[ERROR rate_limit] Rate limit exceeded\n", output)
    }

    @Test
    fun `test error event with code field`() {
        val input = """{"type":"error","error":{"code":"timeout","message":"Request timed out"}}"""
        val output = runFilter(input)
        assertEquals("[ERROR timeout] Request timed out\n", output)
    }

    @Test
    fun `test non-JSON line passes through`() {
        val input = "Plain text line"
        val output = runFilter(input)
        assertEquals("Plain text line\n", output)
    }

    @Test
    fun `test malformed JSON passes through`() {
        val input = """{"incomplete"""
        val output = runFilter(input)
        assertEquals("""{"incomplete""" + "\n", output)
    }

    @Test
    fun `test thread started event is silently skipped`() {
        val input = """{"type":"thread.started","thread_id":"123"}"""
        val output = runFilter(input)
        assertEquals("", output)
    }

    @Test
    fun `test turn started event is silently skipped`() {
        val input = """{"type":"turn.started"}"""
        val output = runFilter(input)
        assertEquals("", output)
    }

    @Test
    fun `test output truncation for long text`() {
        val longOutput = "a".repeat(250)
        val input = """{"type":"item.completed","item":{"type":"tool_call","name":"Test","output":"$longOutput"}}"""
        val output = runFilter(input)
        assertTrue(output.contains("..."))
        assertTrue(output.length < longOutput.length + 50)
    }

    @Test
    fun `test multiple events in sequence`() {
        val input = """
            {"type":"item.started","item":{"type":"command_execution","command":"echo test"}}
            {"type":"item.completed","item":{"type":"command_execution","output":"test","exit_code":0}}
            {"type":"turn.completed","usage":{"input_tokens":10,"output_tokens":5}}
        """.trimIndent()

        val output = runFilter(input)
        assertTrue(output.contains(">> echo test"))
        assertTrue(output.contains("  test"))
        assertTrue(output.contains("[turn] in=10 out=5"))
    }

    private fun runFilter(input: String): String {
        val inputStream = ByteArrayInputStream(input.toByteArray())
        val outputStream = ByteArrayOutputStream()
        filter.process(inputStream, outputStream)
        return outputStream.toString()
    }
}
