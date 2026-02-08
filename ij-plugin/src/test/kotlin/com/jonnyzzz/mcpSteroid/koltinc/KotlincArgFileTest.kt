/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.koltinc

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class KotlincArgFileTest {

    @Test
    fun `simple arg is not quoted`() {
        assertEquals("hello", quoteForKotlinc("hello"))
    }

    @Test
    fun `arg with space is quoted`() {
        assertEquals("\"hello world\"", quoteForKotlinc("hello world"))
    }

    @Test
    fun `arg with tab is quoted`() {
        assertEquals("\"hello\tworld\"", quoteForKotlinc("hello\tworld"))
    }

    @Test
    fun `arg with double quote is quoted and escaped`() {
        assertEquals("\"hello\\\"world\"", quoteForKotlinc("hello\"world"))
    }

    @Test
    fun `arg with single quote is quoted`() {
        assertEquals("\"hello'world\"", quoteForKotlinc("hello'world"))
    }

    @Test
    fun `windows path without spaces is not quoted`() {
        assertEquals("C:\\Users\\test\\file.jar", quoteForKotlinc("C:\\Users\\test\\file.jar"))
    }

    @Test
    fun `windows path with spaces is quoted with escaped backslashes`() {
        assertEquals(
            "\"C:\\\\Users\\\\test\\\\JPA Model\\\\lib.jar\"",
            quoteForKotlinc("C:\\Users\\test\\JPA Model\\lib.jar")
        )
    }

    @Test
    fun `unix path without spaces is not quoted`() {
        assertEquals("/opt/idea/plugins/foo.jar", quoteForKotlinc("/opt/idea/plugins/foo.jar"))
    }

    @Test
    fun `unix path with spaces is quoted`() {
        assertEquals(
            "\"/opt/idea/plugins/JPA Model/lib.jar\"",
            quoteForKotlinc("/opt/idea/plugins/JPA Model/lib.jar")
        )
    }

    @Test
    fun `classpath string with spaced entry is quoted correctly`() {
        val cp = "/path1:/opt/idea/plugins/JPA Model/lib.jar:/path3"
        assertEquals(
            "\"/path1:/opt/idea/plugins/JPA Model/lib.jar:/path3\"",
            quoteForKotlinc(cp)
        )
    }

    @Test
    fun `empty arg is not quoted`() {
        assertEquals("", quoteForKotlinc(""))
    }

    @Test
    fun `arg with backslash and quote`() {
        assertEquals("\"path\\\\with\\\"quote\"", quoteForKotlinc("path\\with\"quote"))
    }

    @Test
    fun `writeKotlincArgFile creates correct content`() {
        val argFile = Files.createTempFile("kotlinc-test-", ".args")
        try {
            writeKotlincArgFile(argFile, listOf("-classpath", "/path1:/path with spaces/lib.jar:/path3"))
            val content = Files.readString(argFile)
            assertEquals(
                "-classpath\n\"/path1:/path with spaces/lib.jar:/path3\"",
                content
            )
        } finally {
            Files.deleteIfExists(argFile)
        }
    }

    @Test
    fun `writeKotlincArgFile with no special chars`() {
        val argFile = Files.createTempFile("kotlinc-test-", ".args")
        try {
            writeKotlincArgFile(argFile, listOf("-classpath", "/path1:/path2:/path3"))
            val content = Files.readString(argFile)
            assertEquals(
                "-classpath\n/path1:/path2:/path3",
                content
            )
        } finally {
            Files.deleteIfExists(argFile)
        }
    }

    @Test
    fun `writeKotlincArgFile with windows paths containing spaces`() {
        val argFile = Files.createTempFile("kotlinc-test-", ".args")
        try {
            writeKotlincArgFile(argFile, listOf("-classpath", "C:\\path1;C:\\path with spaces\\lib.jar;C:\\path3"))
            val content = Files.readString(argFile)
            assertEquals(
                "-classpath\n\"C:\\\\path1;C:\\\\path with spaces\\\\lib.jar;C:\\\\path3\"",
                content
            )
        } finally {
            Files.deleteIfExists(argFile)
        }
    }

    @Test
    fun `toArgFile writes args and returns argfile reference`() {
        val root = Files.createTempDirectory("kotlinc-argfile-test")
        try {
            val outputJar = root.resolve("out.jar")
            val argFile = root.resolve("kotlinc.args")

            val original = KotlincCommandLine(
                args = listOf("-classpath", "/path with space/lib.jar", "-jvm-target", "21"),
                outputJar = outputJar,
            )

            val result = original.toArgFile(argFile)

            assertTrue("Argfile should be created", Files.exists(argFile))
            assertEquals("Should have single @argfile arg", 1, result.args.size)
            assertTrue("Arg should reference the argfile", result.args[0].startsWith("@"))
            assertEquals("outputJar should be preserved", outputJar, result.outputJar)

            val content = Files.readString(argFile)
            assertEquals(
                "-classpath\n\"/path with space/lib.jar\"\n-jvm-target\n21",
                content
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
