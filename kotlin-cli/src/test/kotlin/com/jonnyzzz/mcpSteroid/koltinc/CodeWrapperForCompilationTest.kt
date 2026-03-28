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
    fun `imports intermixed with code are extracted and lines remapped`() {
        val code = """
            import java.io.File
            val x = 1
            import java.util.Date
            val y = x + 1
            import java.net.URL
            println(y)
        """.trimIndent()

        val extracted = CodeWrapperForCompilation.extractImportsWithLineNumbers(code)
        assertEquals(listOf("import java.io.File", "import java.util.Date", "import java.net.URL"), extracted.importLines)
        assertEquals(listOf(1, 3, 5), extracted.importLineNumbers)
        assertEquals(listOf("val x = 1", "val y = x + 1", "println(y)"), extracted.otherLines)
        assertEquals(listOf(2, 4, 6), extracted.otherLineNumbers)

        val result = CodeWrapperForCompilation.wrap("Test", code)
        // 3 imports (N=3), code lines start at 23+3=26
        // otherLine[0]="val x = 1" (orig 2) -> wrapped 26
        // otherLine[1]="val y = x + 1" (orig 4) -> wrapped 27
        // otherLine[2]="println(y)" (orig 6) -> wrapped 28
        val remapped = result.lineMapping.remapCompilerOutput("input.kt:27:5: error: something on y")
        assertEquals("input.kt:4:5: error: something on y", remapped)

        // import lines: wrapped 15->orig 1, 16->orig 3, 17->orig 5
        val remappedImport = result.lineMapping.remapCompilerOutput("input.kt:16:8: error: unresolved")
        assertEquals("input.kt:3:8: error: unresolved", remappedImport)
    }

    @Test
    fun `inline functions and lambdas preserve line mapping`() {
        val code = """
            val items = listOf(1, 2, 3)
            val mapped = items.map { it * 2 }
            val filtered = mapped.filter {
                it > 3
            }
            val result = filtered.joinToString(",")
            val bad: String = result.length
            println(result)
        """.trimIndent()

        val result = CodeWrapperForCompilation.wrap("Test", code)
        // No imports (N=0), code starts at line 23
        // Line 23->orig 1, 24->2, 25->3, 26->4, 27->5, 28->6, 29->7, 30->8
        // Error on "val bad: String = result.length" -> orig line 7
        val remapped = result.lineMapping.remapCompilerOutput("input.kt:29:23: error: type mismatch")
        assertEquals("input.kt:7:23: error: type mismatch", remapped)
    }

    @Test
    fun `multi-line string literal preserves line mapping`() {
        val code = "val text = \"\"\"\n    line 1\n    line 2\n    line 3\n\"\"\".trimIndent()\nval bad: Int = text\nprintln(text)"

        val result = CodeWrapperForCompilation.wrap("Test", code)
        // No imports, code starts at line 23
        // val text = """   -> orig 1, line 23
        //     line 1        -> orig 2, line 24
        //     line 2        -> orig 3, line 25
        //     line 3        -> orig 4, line 26
        // """.trimIndent()  -> orig 5, line 27
        // val bad: Int = text -> orig 6, line 28
        // println(text)     -> orig 7, line 29
        val remapped = result.lineMapping.remapCompilerOutput("input.kt:28:20: error: type mismatch")
        assertEquals("input.kt:6:20: error: type mismatch", remapped)
    }

    @Test
    fun `imports inside triple-quoted strings are not extracted`() {
        val code = "val sql = \"\"\"\n    import something\n    SELECT * FROM table\n\"\"\".trimIndent()\nimport java.io.File\nval f: Int = File(\"x\")\nprintln(f)"

        val extracted = CodeWrapperForCompilation.extractImportsWithLineNumbers(code)
        // "import something" inside triple-quoted string is NOT an import
        // "import java.io.File" on line 5 IS an import
        assertEquals(listOf("import java.io.File"), extracted.importLines)
        assertEquals(listOf(5), extracted.importLineNumbers)

        // Other lines: lines 1-4 (the triple-quoted string) + line 6 + line 7
        assertEquals(6, extracted.otherLines.size)
        assertEquals(listOf(1, 2, 3, 4, 6, 7), extracted.otherLineNumbers)

        val result = CodeWrapperForCompilation.wrap("Test", code)
        // 1 import (N=1), code starts at 24
        // otherLine[4]="val f: Int = File(\"x\")" (orig 6) -> wrapped 28
        val remapped = result.lineMapping.remapCompilerOutput("input.kt:28:18: error: type mismatch")
        assertEquals("input.kt:6:18: error: type mismatch", remapped)
    }

    @Test
    fun `complex code with closures and multiple errors`() {
        val code = """
            import java.util.concurrent.atomic.AtomicInteger

            val counter = AtomicInteger(0)
            val incrementer: () -> Unit = {
                counter.incrementAndGet()
            }

            val result: String = counter.get()
            val items = listOf("a", "b", "c")
            val joined: Int = items.joinToString()

            incrementer()
            println(counter.get())
        """.trimIndent()

        val result = CodeWrapperForCompilation.wrap("Test", code)
        // 1 import (N=1), code starts at 24
        // otherLines: ""(2), "val counter..."(3), "val incrementer..."(4), "counter.inc..."(5),
        //   "}"(6), ""(7), "val result: String..."(8), "val items..."(9), "val joined: Int..."(10),
        //   ""(11), "incrementer()"(12), "println..."(13)
        // otherLine[6] = "val result: String = counter.get()" (orig 8) -> wrapped 30
        // otherLine[8] = "val joined: Int = items.joinToString()" (orig 10) -> wrapped 32
        val compilerOutput = """
            input.kt:30:26: error: type mismatch: expected 'String', actual 'Int'
            input.kt:32:24: error: type mismatch: expected 'Int', actual 'String'
        """.trimIndent()
        val remapped = result.lineMapping.remapCompilerOutput(compilerOutput)
        val expected = """
            input.kt:8:26: error: type mismatch: expected 'String', actual 'Int'
            input.kt:10:24: error: type mismatch: expected 'Int', actual 'String'
        """.trimIndent()
        assertEquals(expected, remapped)
    }

    @Test
    fun `many imports scattered through code`() {
        val code = """
            import java.io.File
            import java.util.Date
            val a = 1
            import java.net.URL
            import java.util.UUID
            val b = 2
            import kotlin.math.sqrt
            val c: String = a + b
        """.trimIndent()

        val extracted = CodeWrapperForCompilation.extractImportsWithLineNumbers(code)
        assertEquals(5, extracted.importLines.size)
        assertEquals(listOf(1, 2, 4, 5, 7), extracted.importLineNumbers)
        assertEquals(listOf("val a = 1", "val b = 2", "val c: String = a + b"), extracted.otherLines)
        assertEquals(listOf(3, 6, 8), extracted.otherLineNumbers)

        val result = CodeWrapperForCompilation.wrap("Test", code)
        // 5 imports (N=5), code starts at 23+5=28
        // otherLine[2]="val c: String = a + b" (orig 8) -> wrapped 30
        val remapped = result.lineMapping.remapCompilerOutput("input.kt:30:21: error: type mismatch")
        assertEquals("input.kt:8:21: error: type mismatch", remapped)
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
