/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

import com.jonnyzzz.mcpSteroid.testHelper.process.*
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ProcessRunRequestBuilderTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `builder requires command`() {
        val ex = Assertions.assertThrows(IllegalStateException::class.java) {
            ProcessRunRequest.Companion.builder()
                .description("test")
                .workingDir(tempDir)
                .build()
        }
        Assertions.assertTrue(ex.message!!.contains("command"), "error should mention 'command': ${ex.message}")
    }

    @Test
    fun `builder requires description`() {
        val ex = Assertions.assertThrows(IllegalStateException::class.java) {
            ProcessRunRequest.Companion.builder()
                .command("echo")
                .workingDir(tempDir)
                .build()
        }
        Assertions.assertTrue(ex.message!!.contains("description"), "error should mention 'description': ${ex.message}")
    }

    @Test
    fun `builder requires workingDir`() {
        val ex = Assertions.assertThrows(IllegalStateException::class.java) {
            ProcessRunRequest.Companion.builder()
                .command("echo")
                .description("test")
                .build()
        }
        Assertions.assertTrue(ex.message!!.contains("workingDir"), "error should mention 'workingDir': ${ex.message}")
    }

    @Test
    fun `builder accepts command as varargs`() {
        val req = ProcessRunRequest.Companion.builder()
            .command("echo", "hello", "world")
            .description("test")
            .workingDir(tempDir)
            .build()
        Assertions.assertEquals(listOf("echo", "hello", "world"), req.command)
    }

    @Test
    fun `builder accepts command as list`() {
        val req = ProcessRunRequest.Companion.builder()
            .command(listOf("echo", "hello"))
            .description("test")
            .workingDir(tempDir)
            .build()
        Assertions.assertEquals(listOf("echo", "hello"), req.command)
    }

    @Test
    fun `builder accepts command via lambda`() {
        val req = ProcessRunRequest.Companion.builder()
            .command {
                add("echo")
                add("hello")
            }
            .description("test")
            .workingDir(tempDir)
            .build()
        Assertions.assertEquals(listOf("echo", "hello"), req.command)
    }

    @Test
    fun `builder defaults timeout to 30 seconds`() {
        val req = ProcessRunRequest.Companion.builder()
            .command("echo")
            .description("test")
            .workingDir(tempDir)
            .build()
        Assertions.assertEquals(30L, req.timeoutSeconds)
    }

    @Test
    fun `builder defaults quietly to false`() {
        val req = ProcessRunRequest.Companion.builder()
            .command("echo")
            .description("test")
            .workingDir(tempDir)
            .build()
        Assertions.assertFalse(req.quietly)
    }

    @Test
    fun `builder sets custom timeout`() {
        val req = ProcessRunRequest.Companion.builder()
            .command("echo")
            .description("test")
            .workingDir(tempDir)
            .timeoutSeconds(120)
            .build()
        Assertions.assertEquals(120L, req.timeoutSeconds)
    }

    @Test
    fun `runProcess extension on builder works`() {
        val runner = ProcessRunner("TEST", secretPatterns = emptyList())
        val result = ProcessRunRequest.Companion.builder()
            .command("bash", "-c", "sleep 0.1; echo ext-test")
            .description("test")
            .workingDir(tempDir)
            .startProcess(runner)
            .assertExitCode(0) { "builder extension runProcess should succeed" }

        Assertions.assertTrue(
            result.stdout.contains("ext-test"),
            "output should contain ext-test, got: ${result.stdout}"
        )
    }
}
