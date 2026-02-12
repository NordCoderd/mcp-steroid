/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.filter

import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GeminiFilterTest {
    private val filter = GeminiFilter()

    @Test
    fun `test ANSI color codes are stripped`() {
        val input = "\u001b[32mGreen text\u001b[0m"
        val output = runFilter(input)
        assertEquals("Green text\n", output)
        assertFalse(output.contains("\u001b"))
    }

    @Test
    fun `test ANSI cursor positioning is stripped`() {
        val input = "\u001b[H\u001b[2JCleared screen"
        val output = runFilter(input)
        assertEquals("Cleared screen\n", output)
        assertFalse(output.contains("\u001b"))
    }

    @Test
    fun `test OSC terminal title is stripped`() {
        val input = "\u001b]0;Window Title\u0007Text content"
        val output = runFilter(input)
        assertEquals("Text content\n", output)
    }

    @Test
    fun `test empty lines are filtered`() {
        val input = "\n\n   \n\t\n"
        val output = runFilter(input)
        assertEquals("", output)
    }

    @Test
    fun `test decorative separators are filtered`() {
        val input = "---\n===\n___\n***\n   ---   "
        val output = runFilter(input)
        assertEquals("", output)
    }

    @Test
    fun `test spinner dots are filtered`() {
        val input = "...\n  ...  \n......."
        val output = runFilter(input)
        assertEquals("", output)
    }

    @Test
    fun `test carriage return lines are filtered`() {
        // Note: \r by itself acts as a line separator in BufferedReader,
        // so "A\rB" becomes two separate lines "A" and "B" with no \r in either.
        // This test verifies both lines are output (as they don't contain \r).
        val input = "Progress: 50%\rProgress: 100%"
        val output = runFilter(input)
        // After \r line split, we get two clean lines
        assertEquals("Progress: 50%\nProgress: 100%\n", output)
    }

    @Test
    fun `test consecutive identical lines are deduplicated`() {
        val input = "Line 1\nLine 1\nLine 1\nLine 2"
        val output = runFilter(input)
        assertEquals("Line 1\nLine 2\n", output)
    }

    @Test
    fun `test tool call patterns are highlighted`() {
        val input = "calling tool steroid_execute_code"
        val output = runFilter(input)
        assertTrue(output.startsWith(">>"))
        assertTrue(output.contains("calling tool"))
    }

    @Test
    fun `test execution ID is highlighted`() {
        val input = "Execution ID: eid_abc123"
        val output = runFilter(input)
        assertTrue(output.startsWith(">>"))
        assertTrue(output.contains("eid_abc123"))
    }

    @Test
    fun `test tool result marker is highlighted`() {
        val input = ">> steroid_execute_code"
        val output = runFilter(input)
        assertTrue(output.startsWith(">>"))
    }

    @Test
    fun `test tool completion marker is highlighted`() {
        val input = "<< completed successfully"
        val output = runFilter(input)
        assertTrue(output.startsWith(">>"))
    }

    @Test
    fun `test MCP resource access is highlighted`() {
        val input = "reading resource: file:///test.txt"
        val output = runFilter(input)
        assertTrue(output.startsWith(">>"))
    }

    @Test
    fun `test bash execution is highlighted`() {
        val input = "Bash execution: ls -la"
        val output = runFilter(input)
        assertTrue(output.startsWith(">>"))
    }

    @Test
    fun `test normal text passes through unchanged`() {
        val input = "Regular output text"
        val output = runFilter(input)
        assertEquals("Regular output text\n", output)
    }

    @Test
    fun `test Unicode content is preserved`() {
        val input = "Hello 世界 🌍"
        val output = runFilter(input)
        assertEquals("Hello 世界 🌍\n", output)
    }

    @Test
    fun `test complex ANSI sequence with multiple codes`() {
        val input = "\u001b[1;32;40mBold green on black\u001b[0m"
        val output = runFilter(input)
        assertEquals("Bold green on black\n", output)
    }

    @Test
    fun `test mixed content with ANSI and tool patterns`() {
        val input = "\u001b[32mcalling tool\u001b[0m steroid_execute_code"
        val output = runFilter(input)
        assertTrue(output.startsWith(">>"))
        assertTrue(output.contains("calling tool"))
        assertFalse(output.contains("\u001b"))
    }

    @Test
    fun `test real-world Gemini output sample`() {
        val input = """
            \u001b[32m.\u001b[0m
            \u001b[32m.\u001b[0m
            \u001b[32m.\u001b[0m
            calling tool steroid_execute_code
            Execution ID: eid_test123
            Tool result: Success
            ---
            Regular response text
        """.trimIndent()

        val output = runFilter(input)
        assertTrue(output.contains(">> calling tool"))
        assertTrue(output.contains(">> Execution ID"))
        assertTrue(output.contains(">> Tool result"))
        assertTrue(output.contains("Regular response text"))
        assertFalse(output.contains("...")) // Spinner filtered
        assertFalse(output.contains("---")) // Separator filtered
        assertFalse(output.contains("\u001b")) // ANSI filtered
    }

    @Test
    fun `test multiple tool calls in sequence`() {
        val input = """
            using tool Read
            Tool completed successfully
            executing function Bash
            Command output: test
        """.trimIndent()

        val output = runFilter(input)
        val lines = output.lines().filter { it.isNotEmpty() }
        assertEquals(4, lines.size)
        assertTrue(lines.all { it.startsWith(">>") })
    }

    private fun runFilter(input: String): String {
        val inputStream = ByteArrayInputStream(input.toByteArray())
        val outputStream = ByteArrayOutputStream()
        filter.process(inputStream, outputStream)
        return outputStream.toString()
    }
}
