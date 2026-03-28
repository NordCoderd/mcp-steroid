/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.koltinc

import org.junit.Assert.assertEquals
import org.junit.Test

class LineMappingTest {

    @Test
    fun `single error line is remapped correctly`() {
        val mapping = LineMapping(mapOf(23 to 1))
        val input = "input.kt:23:21: error: type mismatch"
        val expected = "input.kt:1:21: error: type mismatch"
        assertEquals(expected, mapping.remapCompilerOutput(input))
    }

    @Test
    fun `multiple errors in one output are all remapped`() {
        val mapping = LineMapping(mapOf(23 to 1, 24 to 2, 25 to 3))
        val input = """
            input.kt:23:5: error: type mismatch
            input.kt:24:10: error: unresolved reference
            input.kt:25:1: warning: unused variable
        """.trimIndent()
        val expected = """
            input.kt:1:5: error: type mismatch
            input.kt:2:10: error: unresolved reference
            input.kt:3:1: warning: unused variable
        """.trimIndent()
        assertEquals(expected, mapping.remapCompilerOutput(input))
    }

    @Test
    fun `lines not in mapping are left unchanged`() {
        val mapping = LineMapping(mapOf(23 to 1))
        // Line 10 is a wrapper boilerplate line, not in the mapping
        val input = "input.kt:10:5: error: some wrapper error"
        assertEquals(input, mapping.remapCompilerOutput(input))
    }

    @Test
    fun `column numbers are preserved`() {
        val mapping = LineMapping(mapOf(23 to 1))
        val input = "input.kt:23:42: error: something wrong"
        val expected = "input.kt:1:42: error: something wrong"
        assertEquals(expected, mapping.remapCompilerOutput(input))
    }

    @Test
    fun `non-matching lines pass through unchanged`() {
        val mapping = LineMapping(mapOf(23 to 1))
        val input = "some random compiler message without file reference"
        assertEquals(input, mapping.remapCompilerOutput(input))
    }

    @Test
    fun `mixed matching and non-matching lines`() {
        val mapping = LineMapping(mapOf(23 to 1, 24 to 2))
        val input = """
            w: input.kt:23:5: warning: unused
            some other message
            e: input.kt:24:10: error: type mismatch
        """.trimIndent()
        val expected = """
            w: input.kt:1:5: warning: unused
            some other message
            e: input.kt:2:10: error: type mismatch
        """.trimIndent()
        assertEquals(expected, mapping.remapCompilerOutput(input))
    }

    @Test
    fun `custom file name is supported`() {
        val mapping = LineMapping(mapOf(23 to 1))
        val input = "script.kt:23:5: error: type mismatch"
        val expected = "script.kt:1:5: error: type mismatch"
        assertEquals(expected, mapping.remapCompilerOutput(input, fileName = "script.kt"))
    }

    @Test
    fun `identity mapping leaves everything unchanged`() {
        val input = "input.kt:23:5: error: type mismatch"
        assertEquals(input, LineMapping.IDENTITY.remapCompilerOutput(input))
    }

    @Test
    fun `empty output is handled`() {
        val mapping = LineMapping(mapOf(23 to 1))
        assertEquals("", mapping.remapCompilerOutput(""))
    }

    @Test
    fun `multiple references on same line are all remapped`() {
        val mapping = LineMapping(mapOf(23 to 1, 25 to 3))
        // This could happen in verbose output that references two locations on one line
        val input = "input.kt:23:5: see also input.kt:25:10:"
        val expected = "input.kt:1:5: see also input.kt:3:10:"
        assertEquals(expected, mapping.remapCompilerOutput(input))
    }
}
