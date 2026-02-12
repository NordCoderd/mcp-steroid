/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DockerClaudeSessionTest {
    @Test
    fun extractsLastResultEventText() {
        val raw = """
            {"type":"result","result":"first"}
            {"type":"result","result":"final\nvalue"}
        """.trimIndent()

        val result = DockerClaudeSession.extractStreamJsonResult(raw)

        assertEquals("final\nvalue", result)
    }

    @Test
    fun resultEventTakesPrecedenceOverTextDeltaFragments() {
        val raw = """
            {"type":"content_block_delta","delta":{"type":"text_delta","text":"hello "}}
            {"type":"content_block_delta","delta":{"type":"text_delta","text":"world"}}
            {"type":"result","result":"final answer"}
        """.trimIndent()

        val result = DockerClaudeSession.extractStreamJsonResult(raw)

        assertEquals("final answer", result)
    }

    @Test
    fun fallsBackToTextDeltaFragmentsWhenResultEventIsMissing() {
        val raw = """
            {"type":"content_block_delta","delta":{"type":"text_delta","text":"hello "}}
            {"type":"content_block_delta","delta":{"type":"text_delta","text":"world"}}
        """.trimIndent()

        val result = DockerClaudeSession.extractStreamJsonResult(raw)

        assertEquals("hello world", result)
    }

    @Test
    fun skipsMalformedLinesSafely() {
        val raw = """
            {"type":"content_block_delta","delta":{"type":"text_delta","text":"hello "}}
            {"type":"content_block_delta","delta":{"type":"text_delta","text":"broken"}
            {"type":"content_block_delta","delta":{"type":"text_delta","text":"world"}}
        """.trimIndent()

        val result = DockerClaudeSession.extractStreamJsonResult(raw)

        assertEquals("hello world", result)
    }

    @Test
    fun fallsBackToRawOutputWhenNothingCanBeExtracted() {
        val raw = """
            plain line
            {"type":"ping"}
        """.trimIndent()

        val result = DockerClaudeSession.extractStreamJsonResult(raw)

        assertEquals(raw, result)
    }
}
