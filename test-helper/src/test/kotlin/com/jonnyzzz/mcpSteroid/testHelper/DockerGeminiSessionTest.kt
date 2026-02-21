/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

import com.jonnyzzz.mcpSteroid.filter.GeminiOutputFilter
import com.jonnyzzz.mcpSteroid.filter.filterText
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerPort
import com.jonnyzzz.mcpSteroid.testHelper.docker.RunningContainerProcess
import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessResult
import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessResultValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Tests that [GeminiOutputFilter] produces readable output from NDJSON.
 * Detailed filter behavior is covered by GeminiOutputFilterTest in agent-output-filter.
 */
class DockerGeminiSessionTest {
    private val filter = GeminiOutputFilter()

    @Test
    fun extractsAssistantTextAndProgressFromStreamJson() {
        val raw = """
            YOLO mode is enabled. All tool calls will be automatically approved.
            {"type":"init","session_id":"s1"}
            {"type":"message","role":"user","content":"Read README"}
            {"type":"message","role":"assistant","content":"I will read the README file.\n","delta":true}
            {"type":"tool_use","tool_name":"read_file","tool_id":"tool-1","parameters":{"file_path":"README.md"}}
            {"type":"tool_result","tool_id":"tool-1","status":"success","output":""}
            {"type":"message","role":"assistant","content":"done","delta":true}
            {"type":"result","status":"success","stats":{"input_tokens":12,"output_tokens":34,"tool_calls":1,"duration_ms":1500}}
        """.trimIndent()

        val result = filter.filterText(raw)

        assertEquals(
            """
                YOLO mode is enabled. All tool calls will be automatically approved.
                I will read the README file.
                >> read_file (README.md)
                << read_file
                done
                [done] in=12 out=34 tools=1 time=1.5s
            """.trimIndent(),
            result
        )
    }

    @Test
    fun fallsBackToEmptyOutputWhenNoRelevantEventsFound() {
        val raw = """
            {"type":"init","session_id":"s1"}
            {"type":"message","role":"user","content":"ping"}
        """.trimIndent()

        val result = filter.filterText(raw)

        // Filter skips init and user messages, filterText trims to empty
        assertTrue(result.isEmpty(), "Expected empty output for non-relevant events, got: $result")
    }

    @Test
    fun retriesWithModernSandboxFlagWhenLegacyFlagIsRejected() {
        val first = ProcessResultValue(
            exitCode = 1,
            stdout = "",
            stderr = "Unknown arguments: sandbox-mode, sandboxMode"
        )
        val secondRaw = """
            {"type":"message","role":"assistant","content":"pong","delta":true}
            {"type":"result","stats":{"input_tokens":1,"output_tokens":1,"tool_calls":0,"duration_ms":1000}}
        """.trimIndent()
        val second = ProcessResultValue(
            exitCode = 0,
            stdout = secondRaw,
            stderr = ""
        )

        val runner = RecordingRunner(first, second)
        val session = DockerGeminiSession(runner, apiKey = "test-key")

        val result = session.runPrompt("ping", timeoutSeconds = 30)

        assertEquals(2, runner.commands.size)
        assertTrue(runner.commands[0].contains("--sandbox-mode"))
        assertTrue(runner.commands[0].contains("none"))
        assertTrue(runner.commands[1].contains("--sandbox"))
        assertTrue(runner.commands[1].contains("false"))
        assertTrue(result.stdout.contains("pong"), "Expected 'pong' in: ${result.stdout}")
        assertTrue(result.stdout.contains("[done]"), "Expected '[done]' in: ${result.stdout}")
        assertEquals(secondRaw, result.rawOutput)
    }

    private class RecordingRunner(vararg results: ProcessResult) : ContainerDriver {
        private val resultQueue = ArrayDeque(results.toList())
        val commands = mutableListOf<List<String>>()

        override val containerId: String get() = "fake-container"

        override fun runInContainer(
            args: List<String>,
            workingDir: String?,
            timeoutSeconds: Long,
            extraEnvVars: Map<String, String>,
            quietly: Boolean
        ): ProcessResult {
            commands += args
            return resultQueue.removeFirst()
        }

        override fun mapGuestPathToHostPath(path: String): File = error("not needed in test")
        override fun mapGuestPortToHostPort(port: ContainerPort): Int = error("not needed in test")
        override fun withGuestWorkDir(guestWorkDir: String): ContainerDriver = error("not needed in test")
        override fun withSecretPattern(secretPattern: String): ContainerDriver = error("not needed in test")
        override fun withEnv(key: String, value: String): ContainerDriver = error("not needed in test")
        override fun runInContainerDetached(args: List<String>, workingDir: String?, extraEnvVars: Map<String, String>): RunningContainerProcess = error("not needed in test")
        override fun writeFileInContainer(containerPath: String, content: String, executable: Boolean) = error("not needed in test")
        override fun copyFromContainer(containerPath: String, localPath: File) = error("not needed in test")
        override fun copyToContainer(localPath: File, containerPath: String) = error("not needed in test")
    }
}
