/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

import com.jonnyzzz.mcpSteroid.filter.CodexJsonFilter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests that [CodexJsonFilter] produces readable output from NDJSON.
 * Detailed filter behavior is covered by CodexJsonFilterTest in agent-output-filter.
 */
class DockerCodexSessionTest {
    private val filter = CodexJsonFilter()

    @Test
    fun extractsRepresentativeEvents() {
        val raw = """
            {"type":"thread.started","thread_id":"th_1"}
            {"type":"item.started","item":{"type":"command_execution","command":"ls -la"}}
            {"type":"item.started","item":{"type":"tool_call","name":"read_mcp_resource","input":{"uri":"mcp://demo/resource"}}}
            {"type":"item.completed","item":{"type":"agent_message","text":"final answer"}}
            {"type":"item.completed","item":{"type":"command_execution","output":"line1\nline2\n"}}
            {"type":"item.completed","item":{"type":"tool_call","output":"tool ok"}}
            {"type":"turn.completed","usage":{"input_tokens":12,"output_tokens":34}}
            {"type":"error","error":{"type":"api_error","message":"boom"}}
        """.trimIndent()

        val result = filter.filterText(raw)

        assertTrue(result.contains(">> ls -la"), "Expected '>> ls -la' in: $result")
        assertTrue(result.contains(">> read_mcp_resource"), "Expected tool call in: $result")
        assertTrue(result.contains("final answer"), "Expected 'final answer' in: $result")
        assertTrue(result.contains("line1"), "Expected command output in: $result")
        assertTrue(result.contains("[ERROR api_error] boom"), "Expected error in: $result")
    }

    @Test
    fun extractsProgressFromFunctionCallArgumentsJsonString() {
        val raw = """
            {"type":"item.started","item":{"type":"function_call","function":{"name":"Bash"},"arguments":"{\"command\":\"echo hello\"}"}}
        """.trimIndent()

        val result = filter.filterText(raw)

        assertTrue(result.contains(">> Bash"), "Expected '>> Bash' in: $result")
        assertTrue(result.contains("echo hello"), "Expected 'echo hello' in: $result")
    }

    @Test
    fun usesTopLevelAgentMessageText() {
        val raw = """
            {"type":"item.completed","item":{"type":"agent_message","metadata":{"text":"wrong"},"text":"right"}}
        """.trimIndent()

        val result = filter.filterText(raw)

        assertTrue(result.contains("right"), "Expected 'right' in: $result")
    }

    @Test
    fun extractsErrorTypeFromNestedErrorObject() {
        val raw = """
            {"type":"error","error":{"type":"rate_limit_error","message":"retry later"}}
        """.trimIndent()

        val result = filter.filterText(raw)

        assertEquals("[ERROR rate_limit_error] retry later", result)
    }

    @Test
    fun fallsBackToRawOutputWhenNothingRelevantIsFound() {
        val raw = """
            {"type":"thread.started","thread_id":"th_1"}
            {"type":"turn.started","turn_id":"turn_1"}
        """.trimIndent()

        val result = filter.filterText(raw)

        // Filter produces empty output for protocol-only events; filterText trims it
        assertTrue(result.isEmpty(), "Expected empty output for protocol-only events, got: $result")
    }
}
