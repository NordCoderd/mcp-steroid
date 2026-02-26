/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.filter

import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClaudeOutputFilterTest {
    private val filter = ClaudeOutputFilter()

    @Test
    fun `test tool_use event with details`() {
        val input = """{"type":"content_block_start","content_block":{"type":"tool_use","name":"steroid_execute_code","input":{"reason":"Test execution"}}}"""
        val output = runFilter(input)
        assertTrue(output.contains(">> steroid_execute_code (Test execution)"))
    }

    @Test
    fun `test tool_use with long reason is not truncated`() {
        val longReason = "a".repeat(100)
        val input = """{"type":"content_block_start","content_block":{"type":"tool_use","name":"steroid_execute_code","input":{"reason":"$longReason"}}}"""
        val output = runFilter(input)
        assertTrue(output.contains(">> steroid_execute_code"))
        assertTrue(output.contains(longReason), "Full reason should be preserved: $output")
    }

    @Test
    fun `test bash tool with command`() {
        val input = """{"type":"content_block_start","content_block":{"type":"tool_use","name":"Bash","input":{"command":"ls -la"}}}"""
        val output = runFilter(input)
        assertEquals("\n>> Bash (ls -la)\n", output)
    }

    @Test
    fun `test tool_result success`() {
        val input = """{"type":"tool_result","is_error":false,"content":"Success result"}"""
        val output = runFilter(input)
        assertEquals("<< Success result\n", output)
    }

    @Test
    fun `test tool_result error`() {
        val input = """{"type":"tool_result","is_error":true,"content":"Error occurred"}"""
        val output = runFilter(input)
        assertEquals("<< ERROR Error occurred\n", output)
    }

    @Test
    fun `test tool_result with array content`() {
        val input = """{"type":"tool_result","is_error":false,"content":[{"type":"text","text":"First line\nSecond line"}]}"""
        val output = runFilter(input)
        // Full content is now included so debugger suspension evidence is visible in test assertions
        assertEquals("<< First line\nSecond line\n", output)
    }

    @Test
    fun `test text_delta streaming`() {
        val input = """{"type":"content_block_delta","delta":{"type":"text_delta","text":"Hello world"}}"""
        val output = runFilter(input)
        assertEquals("Hello world", output)
    }

    @Test
    fun `test message_start with model`() {
        val input = """{"type":"message_start","message":{"model":"claude-sonnet-4.5"}}"""
        val output = runFilter(input)
        assertEquals("[model] claude-sonnet-4.5\n", output)
    }

    @Test
    fun `test result with cost and duration`() {
        val input = """{"type":"result","cost_usd":0.0123,"duration_ms":5000,"num_turns":3}"""
        val output = runFilter(input)
        assertTrue(output.contains("[done]"))
        assertTrue(output.contains("cost=${'$'}0.0123"))
        assertTrue(output.contains("time=5.0s"))
        assertTrue(output.contains("turns=3"))
    }

    @Test
    fun `test result with result text`() {
        val input = """{"type":"result","result":"TOOL: steroid_list_projects\nPROJECTS: {\"projects\":[]}","cost_usd":0.05,"duration_ms":10000,"num_turns":2}"""
        val output = runFilter(input)
        assertTrue(output.contains("TOOL: steroid_list_projects"), "Should contain result text: $output")
        assertTrue(output.contains("PROJECTS:"), "Should contain PROJECTS line: $output")
        assertTrue(output.contains("[done]"), "Should contain done marker: $output")
    }

    @Test
    fun `test error event`() {
        val input = """{"type":"error","error":{"type":"invalid_request","message":"Bad request"}}"""
        val output = runFilter(input)
        assertEquals("[ERROR invalid_request] Bad request\n", output)
    }

    @Test
    fun `test system message`() {
        val input = """{"type":"system","message":"System notification"}"""
        val output = runFilter(input)
        assertEquals("[system] System notification\n", output)
    }

    @Test
    fun `test non-JSON line passes through`() {
        val input = "Plain text line"
        val output = runFilter(input)
        assertEquals("Plain text line\n", output)
    }

    @Test
    fun `test malformed JSON passes through`() {
        val input = """{"broken json"""
        val output = runFilter(input)
        assertEquals("""{"broken json""" + "\n", output)
    }

    @Test
    fun `test empty lines are skipped`() {
        val input = "\n\n"
        val output = runFilter(input)
        assertEquals("", output)
    }

    @Test
    fun `test ping event is silently skipped`() {
        val input = """{"type":"ping"}"""
        val output = runFilter(input)
        assertEquals("", output)
    }

    @Test
    fun `test content_block_stop is silently skipped`() {
        val input = """{"type":"content_block_stop"}"""
        val output = runFilter(input)
        assertEquals("", output)
    }

    @Test
    fun `test unknown event type passes through raw JSON`() {
        val input = """{"type":"future_unknown_event","data":"some_value"}"""
        val output = runFilter(input)
        assertEquals("$input\n", output)
    }

    @Test
    fun `test message_delta with end_turn is skipped`() {
        val input = """{"type":"message_delta","delta":{"stop_reason":"end_turn"}}"""
        val output = runFilter(input)
        assertEquals("", output)
    }

    @Test
    fun `test message_delta with non-standard stop reason`() {
        val input = """{"type":"message_delta","delta":{"stop_reason":"max_tokens"}}"""
        val output = runFilter(input)
        assertEquals("[stop] max_tokens\n", output)
    }

    @Test
    fun `test multiple events in sequence`() {
        val input = """
            {"type":"message_start","message":{"model":"claude-sonnet-4.5"}}
            {"type":"content_block_start","content_block":{"type":"tool_use","name":"Bash","input":{"command":"echo hello"}}}
            {"type":"tool_result","is_error":false,"content":"hello"}
            {"type":"result","cost_usd":0.01}
        """.trimIndent()

        val output = runFilter(input)
        assertTrue(output.contains("[model] claude-sonnet-4.5"))
        assertTrue(output.contains(">> Bash (echo hello)"))
        assertTrue(output.contains("<< hello"))
        assertTrue(output.contains("[done]"))
    }

    // ── New-format tests (Claude Code 2.1.x+) ──────────────────────────────

    @Test
    fun `new format - assistant text content is extracted`() {
        val input = """{"type":"assistant","message":{"content":[{"type":"text","text":"BUG_FOUND: yes\nROOT_CAUSE: off-by-one"}]}}"""
        val output = runFilter(input)
        assertTrue(output.contains("BUG_FOUND: yes"), "Should extract assistant text: $output")
        assertTrue(output.contains("ROOT_CAUSE: off-by-one"), "Should contain all lines: $output")
    }

    @Test
    fun `new format - assistant tool_use is shown`() {
        val input = """{"type":"assistant","message":{"content":[{"type":"tool_use","name":"mcp__mcp-steroid__steroid_execute_code","input":{"reason":"test","task_id":"t1","code":"println(1)"}}]}}"""
        val output = runFilter(input)
        assertTrue(output.contains(">> mcp__mcp-steroid__steroid_execute_code"), "Should show tool name: $output")
        assertTrue(output.contains("test"), "Should show reason: $output")
    }

    @Test
    fun `new format - steroid_execute_feedback shows rating and explanation`() {
        val input = """{"type":"assistant","message":{"content":[{"type":"tool_use","name":"mcp__mcp-steroid__steroid_execute_feedback","input":{"project_name":"my-project","task_id":"t1","success_rating":0.9,"explanation":"Code compiled and ran correctly"}}]}}"""
        val output = runFilter(input)
        assertTrue(output.contains(">> mcp__mcp-steroid__steroid_execute_feedback"), "Should show tool name: $output")
        assertTrue(output.contains("rating=0.9"), "Should show success_rating: $output")
        assertTrue(output.contains("Code compiled"), "Should show explanation: $output")
    }

    @Test
    fun `new format - unknown tool with params shows first param value`() {
        val input = """{"type":"assistant","message":{"content":[{"type":"tool_use","name":"SomeFutureTool","input":{"target":"my-file.txt","extra":"ignored"}}]}}"""
        val output = runFilter(input)
        assertTrue(output.contains(">> SomeFutureTool"), "Should show tool name: $output")
        assertTrue(output.contains("my-file.txt"), "Generic fallback should show first param value: $output")
    }

    @Test
    fun `new format - assistant thinking block is skipped`() {
        val input = """{"type":"assistant","message":{"content":[{"type":"thinking","thinking":"Internal reasoning"}]}}"""
        val output = runFilter(input)
        assertEquals("", output)
    }

    @Test
    fun `new format - user tool_result string content`() {
        val input = """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"toolu_x","content":"Execution ID: eid_123\nDone"}]}}"""
        val output = runFilter(input)
        assertTrue(output.contains("<< Execution ID: eid_123"), "Should show first line of result on << line: $output")
        assertTrue(output.contains("Done"), "Should include all content lines: $output")
    }

    @Test
    fun `new format - user tool_result array content`() {
        val input = """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"toolu_x","content":[{"type":"text","text":"Line one\nLine two"}]}]}}"""
        val output = runFilter(input)
        assertTrue(output.contains("<< Line one"), "Should show first line on << line: $output")
        assertTrue(output.contains("Line two"), "Should include all content lines: $output")
    }

    @Test
    fun `new format - result event with empty result but stats`() {
        val input = """{"type":"result","subtype":"success","result":"","total_cost_usd":0.9224,"duration_ms":212000,"num_turns":28}"""
        val output = runFilter(input)
        assertTrue(output.contains("[done]"), "Should contain done marker: $output")
        assertTrue(output.contains("time=212.0s"), "Should contain duration: $output")
        assertTrue(output.contains("turns=28"), "Should contain turns: $output")
    }

    @Test
    fun `new format - mixed assistant events produce correct output`() {
        val input = """
            {"type":"system","subtype":"init","tools":[],"mcp_servers":[]}
            {"type":"assistant","message":{"content":[{"type":"thinking","thinking":"let me think"},{"type":"tool_use","name":"Glob","input":{"pattern":"*.kt"}}]}}
            {"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"id1","content":"src/Main.kt"}]}}
            {"type":"assistant","message":{"content":[{"type":"text","text":"BUG_LINE: var i = 1\n"}]}}
            {"type":"result","result":"","total_cost_usd":0.01,"duration_ms":5000,"num_turns":2}
        """.trimIndent()
        val output = runFilter(input)
        assertTrue(output.contains(">> Glob"), "Should show tool use: $output")
        assertTrue(output.contains("<< src/Main.kt"), "Should show tool result: $output")
        assertTrue(output.contains("BUG_LINE: var i = 1"), "Should show assistant text: $output")
        assertTrue(output.contains("[done]"), "Should have done marker: $output")
    }

    private fun runFilter(input: String): String {
        val inputStream = ByteArrayInputStream(input.toByteArray())
        val outputStream = ByteArrayOutputStream()
        filter.process(inputStream, outputStream)
        return outputStream.toString()
    }
}
