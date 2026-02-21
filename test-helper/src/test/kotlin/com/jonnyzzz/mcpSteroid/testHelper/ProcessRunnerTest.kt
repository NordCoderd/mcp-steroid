/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessResultValue
import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessRunRequest
import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessRunner
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.process.assertNoErrorsInOutput
import com.jonnyzzz.mcpSteroid.testHelper.process.assertNoMessageInOutput
import com.jonnyzzz.mcpSteroid.testHelper.process.assertOutputContains
import com.jonnyzzz.mcpSteroid.testHelper.process.runProcess
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

    // --- startProcess ---

    @Test
    fun `startProcess returns a valid PID`() {
        val started = runner.startProcess(request("echo", "hello"))
        assertNotNull(started.pid)
        assertFalse(started.pid.pid.isBlank())
    }

    @Test
    fun `startProcess runs the process asynchronously`() {
        // start a long-running process and verify it is alive
        val started = runner.startProcess(request("sleep", "10"))
        assertNotNull(started.pid)

        // clean up - kill via PID
        val pid = started.pid.pid.toLong()
        ProcessHandle.of(pid).ifPresent { it.destroyForcibly() }
    }

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
