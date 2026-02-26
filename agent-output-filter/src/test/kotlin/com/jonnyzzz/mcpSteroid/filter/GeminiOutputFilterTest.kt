/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.filter

import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeminiOutputFilterTest {
    private val filter = GeminiOutputFilter()

    @Test
    fun `test assistant message is extracted`() {
        val input = """{"type":"message","role":"assistant","content":"Hello world\n","delta":true}"""
        val output = runFilter(input)
        assertEquals("Hello world\n", output)
    }

    @Test
    fun `test tool use is shown with detail`() {
        val input = """{"type":"tool_use","tool_name":"read_file","tool_id":"tool-1","parameters":{"file_path":"README.md"}}"""
        val output = runFilter(input)
        assertEquals(">> read_file (README.md)\n", output)
    }

    @Test
    fun `test tool result shows success`() {
        val input = """{"type":"tool_result","tool_id":"tool-1","tool_name":"read_file","status":"success","output":"file contents"}"""
        val output = runFilter(input)
        assertEquals("<< read_file: file contents\n", output)
    }

    @Test
    fun `test tool result shows error`() {
        val input = """{"type":"tool_result","tool_id":"tool-1","tool_name":"read_file","status":"error","output":"not found"}"""
        val output = runFilter(input)
        assertEquals("<< ERROR read_file: not found\n", output)
    }

    @Test
    fun `test result summary with stats`() {
        val input = """{"type":"result","status":"success","stats":{"input_tokens":12,"output_tokens":34,"tool_calls":1,"duration_ms":1500}}"""
        val output = runFilter(input)
        assertEquals("[done] in=12 out=34 tools=1 time=1.5s\n", output)
    }

    @Test
    fun `test result summary without stats`() {
        val input = """{"type":"result","status":"success"}"""
        val output = runFilter(input)
        assertEquals("[done]\n", output)
    }

    @Test
    fun `test error event`() {
        val input = """{"type":"error","error":{"type":"api_error","message":"boom"}}"""
        val output = runFilter(input)
        assertEquals("[ERROR api_error] boom\n", output)
    }

    @Test
    fun `test non-json lines pass through`() {
        val input = "YOLO mode is enabled."
        val output = runFilter(input)
        assertEquals("YOLO mode is enabled.\n", output)
    }

    @Test
    fun `test full conversation flow`() {
        val input = """
            YOLO mode is enabled. All tool calls will be automatically approved.
            {"type":"init","session_id":"s1"}
            {"type":"message","role":"user","content":"Read README"}
            {"type":"message","role":"assistant","content":"I will read the README file.\n","delta":true}
            {"type":"tool_use","tool_name":"read_file","tool_id":"tool-1","parameters":{"file_path":"README.md"}}
            {"type":"tool_result","tool_id":"tool-1","status":"success","output":""}
            {"type":"message","role":"assistant","content":"done","delta":true}
            {"type":"result","status":"success","stats":{"input_tokens":12,"output_tokens":34,"tool_calls":1,"duration_ms":1500}}
        """.trimIndent()

        val output = runFilter(input)
        assertEquals(
            """
                YOLO mode is enabled. All tool calls will be automatically approved.
                I will read the README file.
                >> read_file (README.md)
                << read_file
                done
                [done] in=12 out=34 tools=1 time=1.5s
            """.trimIndent() + "\n",
            output
        )
    }

    @Test
    fun `test tool id resolves to name from earlier tool_use`() {
        val input = """
            {"type":"tool_use","tool_name":"steroid_execute_code","tool_id":"t1","parameters":{}}
            {"type":"tool_result","tool_id":"t1","status":"success","output":"ok"}
        """.trimIndent()

        val output = runFilter(input)
        assertTrue(output.contains("<< steroid_execute_code: ok"))
    }

    @Test
    fun `test user messages are skipped`() {
        val input = """{"type":"message","role":"user","content":"Please do something"}"""
        val output = runFilter(input)
        assertEquals("", output)
    }

    @Test
    fun `test malformed json passes through`() {
        val input = """{"type":"message","broken"""
        val output = runFilter(input)
        assertEquals("""{"type":"message","broken""" + "\n", output)
    }

    @Test
    fun `test unknown event type passes through raw JSON`() {
        val input = """{"type":"future_unknown_event","data":"some_value"}"""
        val output = runFilter(input)
        assertEquals("$input\n", output)
    }

    private fun runFilter(input: String): String {
        val inputStream = ByteArrayInputStream(input.toByteArray())
        val outputStream = ByteArrayOutputStream()
        filter.process(inputStream, outputStream)
        return outputStream.toString()
    }
}
