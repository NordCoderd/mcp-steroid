/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

import com.jonnyzzz.mcpSteroid.filter.ClaudeOutputFilter
import com.jonnyzzz.mcpSteroid.filter.filterText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests that [ClaudeOutputFilter] produces readable output from NDJSON.
 * Detailed filter behavior is covered by ClaudeOutputFilterTest in agent-output-filter.
 */
class DockerClaudeSessionTest {
    private val filter = ClaudeOutputFilter()

    @Test
    fun textDeltaFragmentsAreExtracted() {
        val raw = """
            {"type":"content_block_delta","delta":{"type":"text_delta","text":"hello "}}
            {"type":"content_block_delta","delta":{"type":"text_delta","text":"world"}}
        """.trimIndent()

        val result = filter.filterText(raw)

        assertTrue(result.contains("hello world"), "Expected 'hello world' in: $result")
    }

    @Test
    fun resultEventProducesDoneSummary() {
        val raw = """
            {"type":"content_block_delta","delta":{"type":"text_delta","text":"hello "}}
            {"type":"content_block_delta","delta":{"type":"text_delta","text":"world"}}
            {"type":"result","cost_usd":0.01,"duration_ms":5000,"num_turns":1}
        """.trimIndent()

        val result = filter.filterText(raw)

        assertTrue(result.contains("hello world"), "Expected text in: $result")
        assertTrue(result.contains("[done]"), "Expected [done] summary in: $result")
    }

    @Test
    fun skipsMalformedLinesSafely() {
        val raw = """
            {"type":"content_block_delta","delta":{"type":"text_delta","text":"hello "}}
            {"type":"content_block_delta","delta":{"type":"text_delta","text":"broken"}
            {"type":"content_block_delta","delta":{"type":"text_delta","text":"world"}}
        """.trimIndent()

        val result = filter.filterText(raw)

        assertTrue(result.contains("hello "), "Expected 'hello ' in: $result")
        assertTrue(result.contains("world"), "Expected 'world' in: $result")
    }

    @Test
    fun nonJsonLinesPassThrough() {
        val raw = """
            plain line
            {"type":"ping"}
        """.trimIndent()

        val result = filter.filterText(raw)

        assertEquals("plain line", result)
    }

    @Test
    fun toolUseIsShown() {
        val raw = """
            {"type":"content_block_start","content_block":{"type":"tool_use","name":"Bash","input":{"command":"ls"}}}
        """.trimIndent()

        val result = filter.filterText(raw)

        assertTrue(result.contains(">> Bash"), "Expected '>> Bash' in: $result")
    }
}
