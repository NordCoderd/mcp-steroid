/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.koltinc

import org.junit.Assert.assertEquals
import org.junit.Test

class CodeWrapperForCompilationTest {

    @Test
    fun `line mapping maps import lines back to original positions`() {
        val code = """
            import foo.Bar

            val x: String = 123
            println(x)
        """.trimIndent()

        val result = CodeWrapperForCompilation.wrap("Test", code)
        val mapping = result.lineMapping

        // "import foo.Bar" is on original line 1.
        // In wrapped code, user imports start at line 15 (after 12 default imports + empty + comment).
        // So wrapped line 15 should map to original line 1.
        val remapped = mapping.remapCompilerOutput("input.kt:15:1: error: unresolved")
        assertEquals("input.kt:1:1: error: unresolved", remapped)
    }

    @Test
    fun `line mapping maps code lines back to original positions`() {
        val code = """
            import foo.Bar

            val x: String = 123
            println(x)
        """.trimIndent()

        val result = CodeWrapperForCompilation.wrap("Test", code)
        val mapping = result.lineMapping

        // "import foo.Bar" is 1 import line, so N=1.
        // Non-import lines: "" (line 2), "val x: String = 123" (line 3), "println(x)" (line 4)
        // User code starts at wrapped line 23 + N = 24.
        // Wrapped line 24 -> original line 2 (the empty line)
        // Wrapped line 25 -> original line 3 (val x: String = 123)
        // Wrapped line 26 -> original line 4 (println(x))
        val remapped3 = mapping.remapCompilerOutput("input.kt:25:21: error: type mismatch")
        assertEquals("input.kt:3:21: error: type mismatch", remapped3)

        val remapped4 = mapping.remapCompilerOutput("input.kt:26:1: error: unresolved reference")
        assertEquals("input.kt:4:1: error: unresolved reference", remapped4)
    }

    @Test
    fun `line mapping with no imports`() {
        val code = """
            val x: String = 123
            println(x)
        """.trimIndent()

        val result = CodeWrapperForCompilation.wrap("Test", code)
        val mapping = result.lineMapping

        // No user imports, so N=0.
        // User code starts at wrapped line 23 + 0 = 23.
        // "val x: String = 123" is original line 1 -> wrapped line 23
        // "println(x)" is original line 2 -> wrapped line 24
        val remapped1 = mapping.remapCompilerOutput("input.kt:23:21: error: type mismatch")
        assertEquals("input.kt:1:21: error: type mismatch", remapped1)

        val remapped2 = mapping.remapCompilerOutput("input.kt:24:1: error: unresolved reference")
        assertEquals("input.kt:2:1: error: unresolved reference", remapped2)
    }

    @Test
    fun `line mapping with multiple imports`() {
        val code = """
            import foo.Bar
            import baz.Qux

            val x = 42
        """.trimIndent()

        val result = CodeWrapperForCompilation.wrap("Test", code)
        val mapping = result.lineMapping

        // 2 import lines (N=2).
        // Import 1: wrapped line 15 -> original line 1
        // Import 2: wrapped line 16 -> original line 2
        val remappedImport1 = mapping.remapCompilerOutput("input.kt:15:8: error: unresolved")
        assertEquals("input.kt:1:8: error: unresolved", remappedImport1)

        val remappedImport2 = mapping.remapCompilerOutput("input.kt:16:8: error: unresolved")
        assertEquals("input.kt:2:8: error: unresolved", remappedImport2)

        // Non-import lines: "" (line 3), "val x = 42" (line 4)
        // User code starts at wrapped line 23 + 2 = 25.
        // Wrapped line 25 -> original line 3 (empty)
        // Wrapped line 26 -> original line 4 (val x = 42)
        val remappedCode = mapping.remapCompilerOutput("input.kt:26:5: error: something")
        assertEquals("input.kt:4:5: error: something", remappedCode)
    }

    @Test
    fun `line mapping with single line of code`() {
        val code = "val x: String = 123"
        val result = CodeWrapperForCompilation.wrap("Test", code)
        val mapping = result.lineMapping

        // No imports (N=0), one code line at original line 1.
        // Wrapped line 23 -> original line 1.
        val remapped = mapping.remapCompilerOutput("input.kt:23:21: error: type mismatch")
        assertEquals("input.kt:1:21: error: type mismatch", remapped)
    }

    @Test
    fun `wrapper boilerplate lines are not remapped`() {
        val code = "val x = 1"
        val result = CodeWrapperForCompilation.wrap("Test", code)
        val mapping = result.lineMapping

        // Line 16 is "class Test {" — a wrapper boilerplate line.
        val input = "input.kt:16:1: error: some weird error"
        assertEquals(input, mapping.remapCompilerOutput(input))
    }

    @Test
    fun `extractImportsWithLineNumbers tracks line numbers correctly`() {
        val code = """
            import foo.Bar

            val x = 1
            import baz.Qux
            println(x)
        """.trimIndent()

        val extracted = CodeWrapperForCompilation.extractImportsWithLineNumbers(code)

        assertEquals(listOf("import foo.Bar", "import baz.Qux"), extracted.importLines)
        assertEquals(listOf(1, 4), extracted.importLineNumbers)

        assertEquals(listOf("", "val x = 1", "println(x)"), extracted.otherLines)
        assertEquals(listOf(2, 3, 5), extracted.otherLineNumbers)
    }

    @Test
    fun `extractImports backward compatibility`() {
        val code = """
            import foo.Bar
            val x = 1
        """.trimIndent()

        val (imports, other) = CodeWrapperForCompilation.extractImports(code)
        assertEquals(listOf("import foo.Bar"), imports)
        assertEquals(listOf("val x = 1"), other)
    }

    @Test
    fun `line mapping end-to-end with realistic compiler output`() {
        val code = """
            import kotlin.math.sqrt

            val x: String = 123
            val y: Int = "hello"
        """.trimIndent()

        val result = CodeWrapperForCompilation.wrap("Script", code)

        // Simulate compiler output with wrapped line numbers:
        // 1 import (N=1), code lines start at 23+1=24
        // Line 24 -> original 2 (empty), line 25 -> original 3, line 26 -> original 4
        val compilerOutput = """
            e: input.kt:25:21: error: The integer literal does not conform to the expected type 'String'
            e: input.kt:26:18: error: The literal does not conform to the expected type 'Int'
        """.trimIndent()

        val remapped = result.lineMapping.remapCompilerOutput(compilerOutput)
        val expected = """
            e: input.kt:3:21: error: The integer literal does not conform to the expected type 'String'
            e: input.kt:4:18: error: The literal does not conform to the expected type 'Int'
        """.trimIndent()
        assertEquals(expected, remapped)
    }
}
