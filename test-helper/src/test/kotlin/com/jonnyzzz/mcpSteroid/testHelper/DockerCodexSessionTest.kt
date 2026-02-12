/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DockerCodexSessionTest {
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

        val result = DockerCodexSession.extractCodexJsonResult(raw)

        assertEquals(
            """
                >> ls -la
                >> read_mcp_resource (mcp://demo/resource)
                final answer
                line1
                line2
                tool ok
                [tokens] in=12 out=34
                [ERROR api_error] boom
            """.trimIndent(),
            result
        )
    }

    @Test
    fun extractsProgressFromFunctionCallArgumentsJsonString() {
        val raw = """
            {"type":"item.started","item":{"type":"function_call","function":{"name":"Bash"},"arguments":"{\"command\":\"echo hello\"}"}}
        """.trimIndent()

        val result = DockerCodexSession.extractCodexJsonResult(raw)

        assertEquals(
            ">> Bash (echo hello)",
            result
        )
    }

    @Test
    fun usesTopLevelAgentMessageText() {
        val raw = """
            {"type":"item.completed","item":{"type":"agent_message","metadata":{"text":"wrong"},"text":"right"}}
        """.trimIndent()

        val result = DockerCodexSession.extractCodexJsonResult(raw)

        assertEquals("right", result)
    }

    @Test
    fun supportsAggregatedOutputFieldForCommandExecution() {
        val raw = """
            {"type":"item.completed","item":{"type":"command_execution","aggregated_output":"from aggregated output\n"}}
        """.trimIndent()

        val result = DockerCodexSession.extractCodexJsonResult(raw)

        assertEquals("from aggregated output", result)
    }

    @Test
    fun extractsErrorTypeFromNestedErrorObject() {
        val raw = """
            {"type":"error","error":{"type":"rate_limit_error","message":"retry later"}}
        """.trimIndent()

        val result = DockerCodexSession.extractCodexJsonResult(raw)

        assertEquals("[ERROR rate_limit_error] retry later", result)
    }

    @Test
    fun fallsBackToRawOutputWhenNothingRelevantIsFound() {
        val raw = """
            {"type":"thread.started","thread_id":"th_1"}
            {"type":"turn.started","turn_id":"turn_1"}
        """.trimIndent()

        val result = DockerCodexSession.extractCodexJsonResult(raw)

        assertEquals(raw, result)
    }
}
