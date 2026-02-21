/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessResultValue
import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessRunRequest
import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessRunner
import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessStreamType
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.process.assertNoErrorsInOutput
import com.jonnyzzz.mcpSteroid.testHelper.process.assertNoMessageInOutput
import com.jonnyzzz.mcpSteroid.testHelper.process.assertOutputContains
import com.jonnyzzz.mcpSteroid.testHelper.process.runProcess
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ProcessRunnerTest {
    private val runner = ProcessRunner("TEST", secretPatterns = emptyList())

    @TempDir
    lateinit var tempDir: File

    private fun request(vararg command: String, block: ProcessRunRequest.Builder.() -> Unit = {}) =
        ProcessRunRequest.builder()
            .command(*command)
            .description("test")
            .workingDir(tempDir)
            .apply(block)
            .build()

    // --- runProcess basic ---

    @Test
    fun `runProcess captures stdout`() {
        val result = runner.runProcess(request("echo", "hello world"))
        assertNotNull(result.output)
        assertTrue(result.output.contains("hello world"), "stdout should contain 'hello world', got: ${result.output}")
    }

    @Test
    fun `runProcess captures stderr`() {
        val result = runner.runProcess(request("bash", "-c", "echo error-text >&2"))
        assertTrue(result.stderr.contains("error-text"), "stderr should contain 'error-text', got: ${result.stderr}")
    }

    @Test
    fun `runProcess returns exit code zero on success`() {
        val result = runner.runProcess(request("true"))
        result.assertExitCode(0) { "runProcess should return 0 for 'true'" }
    }

    @Test
    fun `runProcess returns non-zero exit code on failure`() {
        val result = runner.runProcess(request("false"))
        result.assertExitCode(1) { "runProcess should return 1 for 'false'" }
    }

    @Test
    fun `runProcess returns specific exit code`() {
        val result = runner.runProcess(request("bash", "-c", "exit 42"))
        result.assertExitCode(42) { "runProcess should return 42 for 'exit 42'" }
    }

    @Test
    fun `runProcess rawOutput equals output by default`() {
        val result = runner.runProcess(request("echo", "data"))
        assertEquals(result.output, result.rawOutput)
    }

    // --- stdin ---

    @Test
    fun `runProcess passes stdin to process`() {
        val result = runner.runProcess(request("cat") {
            stdin("hello from stdin")
        })
        assertTrue(result.output.contains("hello from stdin"), "process should read stdin, got: ${result.output}")
    }

    @Test
    fun `runProcess passes byte array stdin`() {
        val bytes = "byte-content".toByteArray()
        val result = runner.runProcess(request("cat") {
            stdin(bytes)
        })
        assertTrue(result.output.contains("byte-content"), "process should read byte array stdin, got: ${result.output}")
    }

    // --- timeout ---

    @Test
    fun `runProcess times out and returns negative exit code`() {
        val result = runner.runProcess(request("sleep", "60") {
            timeoutSeconds(1)
        })
        result.assertExitCode(-1) { "runProcess should return -1 on timeout" }
        assertTrue(result.stderr.contains("Timeout"), "stderr should mention timeout, got: ${result.stderr}")
    }

    // --- secret filtering ---

    @Test
    fun `secrets are filtered from logged output but preserved in result`() {
        val secret = "my-secret-token-12345"
        val secretRunner = ProcessRunner("TEST", secretPatterns = listOf(secret))
        val result = secretRunner.runProcess(request("echo", secret))
        // The actual output should preserve the secret (secrets filtered only in logs, not result)
        assertTrue(result.output.contains(secret), "result output should preserve the secret, got: ${result.output}")
    }

    @Test
    fun `blank secret patterns are ignored`() {
        val secretRunner = ProcessRunner("TEST", secretPatterns = listOf("", "  ", "actual-secret"))
        val result = secretRunner.runProcess(request("echo", "no actual-secret here"))
        // blank patterns should not cause issues, only "actual-secret" is redacted in logs
        assertTrue(result.output.contains("actual-secret"), "result output should preserve the secret")
    }

    // --- quietly mode ---

    @Test
    fun `quietly mode does not affect result content`() {
        val result = runner.runProcess(request("echo", "quiet-output") {
            quietly()
        })
        assertTrue(result.output.contains("quiet-output"), "quietly mode should still capture output")
        result.assertExitCode(0) { "quietly mode should not affect exit code" }
    }

    // --- working directory ---

    @Test
    fun `process runs in the specified working directory`() {
        val result = runner.runProcess(request("pwd"))
        assertTrue(
            result.output.contains(tempDir.canonicalPath) || result.output.contains(tempDir.absolutePath),
            "process should run in tempDir, got: ${result.output}"
        )
    }

    // --- multi-line output ---

    @Test
    fun `runProcess captures multi-line output`() {
        val result = runner.runProcess(request("bash", "-c", "echo line1; echo line2; echo line3"))
        assertTrue(result.output.contains("line1"), "should contain line1")
        assertTrue(result.output.contains("line2"), "should contain line2")
        assertTrue(result.output.contains("line3"), "should contain line3")
    }

    @Test
    fun `runProcess joins multi-line output with newline not comma-space`() {
        val result = runner.runProcess(request("bash", "-c", "echo line1; echo line2; echo line3"))
        // Lines must be newline-separated, not joined with ", " (the joinToString default separator)
        val lines = result.output.lines().filter { it.isNotBlank() }
        assertEquals(listOf("line1", "line2", "line3"), lines,
            "multi-line output must be newline-joined, got: ${result.output.replace("\n", "\\n")}")
    }

    @Test
    fun `runProcess joins multi-line stderr with newline not comma-space`() {
        val result = runner.runProcess(request("bash", "-c", "echo a >&2; echo b >&2; echo c >&2"))
        val lines = result.stderr.lines().filter { it.isNotBlank() }
        assertEquals(listOf("a", "b", "c"), lines,
            "multi-line stderr must be newline-joined, got: ${result.stderr.replace("\n", "\\n")}")
    }

    @Test
    fun `runProcess captures both stdout and stderr from same process`() {
        val result = runner.runProcess(request("bash", "-c", "echo out-text; echo err-text >&2"))
        assertTrue(result.output.contains("out-text"), "stdout should contain out-text, got: ${result.output}")
        assertTrue(result.stderr.contains("err-text"), "stderr should contain err-text, got: ${result.stderr}")
    }
}

class ProcessRunRequestBuilderTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `builder requires command`() {
        val ex = assertThrows(IllegalStateException::class.java) {
            ProcessRunRequest.builder()
                .description("test")
                .workingDir(tempDir)
                .build()
        }
        assertTrue(ex.message!!.contains("command"), "error should mention 'command': ${ex.message}")
    }

    @Test
    fun `builder requires description`() {
        val ex = assertThrows(IllegalStateException::class.java) {
            ProcessRunRequest.builder()
                .command("echo")
                .workingDir(tempDir)
                .build()
        }
        assertTrue(ex.message!!.contains("description"), "error should mention 'description': ${ex.message}")
    }

    @Test
    fun `builder requires workingDir`() {
        val ex = assertThrows(IllegalStateException::class.java) {
            ProcessRunRequest.builder()
                .command("echo")
                .description("test")
                .build()
        }
        assertTrue(ex.message!!.contains("workingDir"), "error should mention 'workingDir': ${ex.message}")
    }

    @Test
    fun `builder accepts command as varargs`() {
        val req = ProcessRunRequest.builder()
            .command("echo", "hello", "world")
            .description("test")
            .workingDir(tempDir)
            .build()
        assertEquals(listOf("echo", "hello", "world"), req.command)
    }

    @Test
    fun `builder accepts command as list`() {
        val req = ProcessRunRequest.builder()
            .command(listOf("echo", "hello"))
            .description("test")
            .workingDir(tempDir)
            .build()
        assertEquals(listOf("echo", "hello"), req.command)
    }

    @Test
    fun `builder accepts command via lambda`() {
        val req = ProcessRunRequest.builder()
            .command {
                add("echo")
                add("hello")
            }
            .description("test")
            .workingDir(tempDir)
            .build()
        assertEquals(listOf("echo", "hello"), req.command)
    }

    @Test
    fun `builder defaults timeout to 30 seconds`() {
        val req = ProcessRunRequest.builder()
            .command("echo")
            .description("test")
            .workingDir(tempDir)
            .build()
        assertEquals(30L, req.timeoutSeconds)
    }

    @Test
    fun `builder defaults quietly to false`() {
        val req = ProcessRunRequest.builder()
            .command("echo")
            .description("test")
            .workingDir(tempDir)
            .build()
        assertFalse(req.quietly)
    }

    @Test
    fun `builder sets custom timeout`() {
        val req = ProcessRunRequest.builder()
            .command("echo")
            .description("test")
            .workingDir(tempDir)
            .timeoutSeconds(120)
            .build()
        assertEquals(120L, req.timeoutSeconds)
    }

    @Test
    fun `runProcess extension on builder works`() {
        val runner = ProcessRunner("TEST", secretPatterns = emptyList())
        val result = ProcessRunRequest.builder()
            .command("bash", "-c", "sleep 0.1; echo ext-test")
            .description("test")
            .workingDir(tempDir)
            .runProcess(runner)
        result.assertExitCode(0) { "builder extension runProcess should succeed" }
        assertTrue(result.output.contains("ext-test"), "output should contain ext-test, got: ${result.output}")
    }
}

class StartedProcessMessagesFlowTest {
    private val runner = ProcessRunner("TEST", secretPatterns = emptyList())

    @TempDir
    lateinit var tempDir: File

    private fun request(vararg command: String, block: ProcessRunRequest.Builder.() -> Unit = {}) =
        ProcessRunRequest.builder()
            .command(*command)
            .description("test")
            .workingDir(tempDir)
            .apply(block)
            .build()

    @Test
    fun `messagesFlow emits stdout lines`() = runBlocking {
        val started = runner.startProcess(
            request("bash", "-c", "echo hello; sleep 0.3; echo world; sleep 0.3")
        )
        try {
            val lines = withTimeout(5_000) {
                started.messagesFlow
                    .filter { it.type == ProcessStreamType.STDOUT }
                    .toList()
            }

            val texts = lines.map { it.line }
            assertTrue(texts.any { it.contains("hello") }, "should contain hello, got: $texts")
            assertTrue(texts.any { it.contains("world") }, "should contain world, got: $texts")
        } finally {
            started.destroyForcibly()
        }
    }

    @Test
    fun `messagesFlow emits stderr lines`() = runBlocking {
        val started = runner.startProcess(
            request("bash", "-c", "echo err-msg >&2; sleep 0.3")
        )
        try {
            val lines = withTimeout(5_000) {
                started.messagesFlow
                    .filter { it.type == ProcessStreamType.STDERR }
                    .toList()
            }

            val texts = lines.map { it.line }
            assertTrue(texts.any { it.contains("err-msg") }, "should contain err-msg, got: $texts")
        } finally {
            started.destroyForcibly()
        }
    }

    @Test
    fun `messagesFlow emits both stdout and stderr`() = runBlocking {
        val started = runner.startProcess(
            request("bash", "-c", "echo out-line; echo err-line >&2; sleep 0.3")
        )
        try {
            val lines = withTimeout(5_000) {
                started.messagesFlow.toList()
            }

            val stdoutLines = lines.filter { it.type == ProcessStreamType.STDOUT }.map { it.line }
            val stderrLines = lines.filter { it.type == ProcessStreamType.STDERR }.map { it.line }

            assertTrue(stdoutLines.any { it.contains("out-line") }, "stdout should contain out-line, got: $stdoutLines")
            assertTrue(stderrLines.any { it.contains("err-line") }, "stderr should contain err-line, got: $stderrLines")
        } finally {
            started.destroyForcibly()
        }
    }

    @Test
    fun `messagesFlow completes when process exits`() = runBlocking {
        val started = runner.startProcess(
            request("bash", "-c", "echo done; sleep 0.2")
        )
        try {
            val result = withTimeout(5_000) {
                started.messagesFlow.toList()
            }

            // flow completed within timeout — process must have exited
            assertNotNull(result)
        } finally {
            started.destroyForcibly()
        }
    }

    @Test
    fun `messagesFlow streams lines incrementally`() = runBlocking {
        val started = runner.startProcess(
            request("bash", "-c", "echo first; sleep 0.5; echo second; sleep 0.5; echo third; sleep 0.3")
        )
        try {
            val timestamps = mutableListOf<Pair<String, Long>>()
            val start = System.currentTimeMillis()

            withTimeout(10_000) {
                started.messagesFlow
                    .filter { it.type == ProcessStreamType.STDOUT }
                    .collect { line ->
                        timestamps.add(line.line to (System.currentTimeMillis() - start))
                    }
            }

            // We should have received at least 2 lines
            assertTrue(timestamps.size >= 2, "should have at least 2 stdout lines, got: $timestamps")

            // Lines should arrive at different times (not all batched together)
            // The first line should arrive noticeably before the last
            val firstTime = timestamps.first().second
            val lastTime = timestamps.last().second
            assertTrue(
                lastTime - firstTime >= 200,
                "lines should arrive incrementally, but first=$firstTime, last=$lastTime (diff=${lastTime - firstTime}ms)"
            )
        } finally {
            started.destroyForcibly()
        }
    }

    @Test
    fun `messagesFlow preserves line order`() = runBlocking {
        val started = runner.startProcess(
            request("bash", "-c", "for i in 1 2 3 4 5; do echo line-\$i; sleep 0.1; done; sleep 0.3")
        )
        try {
            val lines = withTimeout(10_000) {
                started.messagesFlow
                    .filter { it.type == ProcessStreamType.STDOUT }
                    .toList()
                    .map { it.line }
            }

            // Extract the numeric suffixes that were captured
            val numbers = lines.mapNotNull { line ->
                Regex("line-(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull()
            }

            assertTrue(numbers.size >= 3, "should capture at least 3 numbered lines, got: $lines")

            // Verify ordering is preserved
            for (i in 1 until numbers.size) {
                assertTrue(
                    numbers[i] > numbers[i - 1],
                    "lines should be in order, but got: $numbers"
                )
            }
        } finally {
            started.destroyForcibly()
        }
    }

    @Test
    fun `destroyForcibly terminates the process`() = runBlocking {
        val started = runner.startProcess(
            request("sleep", "60")
        )

        // Process should be running
        assertNull(started.exitCode, "process should still be running")

        started.destroyForcibly()

        // Give OS a moment to clean up
        Thread.sleep(200)

        // After destroy, exitCode should be available
        assertNotNull(started.exitCode, "process should have exited after destroyForcibly")
    }
}

class ProcessResultAssertionsTest {

    @Test
    fun `assertOutputContains passes when text in stdout`() {
        val result = ProcessResultValue(0, "hello world", "")
        result.assertOutputContains("hello")
    }

    @Test
    fun `assertOutputContains passes when text in stderr`() {
        val result = ProcessResultValue(0, "", "error details")
        result.assertOutputContains("error details")
    }

    @Test
    fun `assertOutputContains fails when text not found`() {
        val result = ProcessResultValue(0, "hello", "world")
        assertThrows(IllegalStateException::class.java) {
            result.assertOutputContains("missing-text")
        }
    }

    @Test
    fun `assertOutputContains checks multiple strings`() {
        val result = ProcessResultValue(0, "hello world", "error details")
        result.assertOutputContains("hello", "error")
    }

    @Test
    fun `assertOutputContains fails on first missing string`() {
        val result = ProcessResultValue(0, "hello", "world")
        assertThrows(IllegalStateException::class.java) {
            result.assertOutputContains("hello", "missing")
        }
    }

    @Test
    fun `assertExitCode passes on matching code`() {
        val result = ProcessResultValue(0, "", "")
        result.assertExitCode(0)
    }

    @Test
    fun `assertExitCode fails on mismatched code`() {
        val result = ProcessResultValue(1, "", "")
        assertThrows(IllegalStateException::class.java) {
            result.assertExitCode(0)
        }
    }

    @Test
    fun `assertExitCode with lambda message fails with custom message`() {
        val result = ProcessResultValue(1, "out", "err")
        val ex = assertThrows(IllegalStateException::class.java) {
            result.assertExitCode(0) { "custom-context" }
        }
        assertTrue(ex.message!!.contains("custom-context"))
    }

    @Test
    fun `assertNoErrorsInOutput passes when no errors`() {
        val result = ProcessResultValue(0, "all good", "clean")
        result.assertNoErrorsInOutput("test")
    }

    @Test
    fun `assertNoErrorsInOutput fails on ERROR pattern in stdout`() {
        val result = ProcessResultValue(0, "ERROR: something broke", "")
        assertThrows(IllegalStateException::class.java) {
            result.assertNoErrorsInOutput("test")
        }
    }

    @Test
    fun `assertNoErrorsInOutput fails on tool not available`() {
        val result = ProcessResultValue(0, "tool xyz is not available", "")
        assertThrows(IllegalStateException::class.java) {
            result.assertNoErrorsInOutput("test")
        }
    }

    @Test
    fun `assertNoErrorsInOutput fails on failed to connect`() {
        val result = ProcessResultValue(0, "", "Failed to connect to server")
        assertThrows(IllegalStateException::class.java) {
            result.assertNoErrorsInOutput("test")
        }
    }

    @Test
    fun `assertNoMessageInOutput passes when pattern not found`() {
        val result = ProcessResultValue(0, "clean output", "clean err")
        result.assertNoMessageInOutput("forbidden")
    }

    @Test
    fun `assertNoMessageInOutput fails when pattern found`() {
        val result = ProcessResultValue(0, "contains forbidden-word here", "")
        assertThrows(IllegalStateException::class.java) {
            result.assertNoMessageInOutput("forbidden-word")
        }
    }

    @Test
    fun `assertOutputContains returns the same result for chaining`() {
        val result = ProcessResultValue(0, "hello", "")
        val returned = result.assertOutputContains("hello")
        assertSame(result, returned)
    }

    @Test
    fun `assertExitCode returns the same result for chaining`() {
        val result = ProcessResultValue(0, "", "")
        val returned = result.assertExitCode(0)
        assertSame(result, returned)
    }
}
