/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerProcessRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DockerGeminiSessionTest {
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

        val result = DockerGeminiSession.extractGeminiStreamJsonResult(raw)

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
    fun fallsBackToRawOutputWhenNoRelevantEventsFound() {
        val raw = """
            {"type":"init","session_id":"s1"}
            {"type":"message","role":"user","content":"ping"}
        """.trimIndent()

        val result = DockerGeminiSession.extractGeminiStreamJsonResult(raw)

        assertEquals(raw, result)
    }

    @Test
    fun retriesWithModernSandboxFlagWhenLegacyFlagIsRejected() {
        val first = ProcessResultValue(
            exitCode = 1,
            output = "",
            stderr = "Unknown arguments: sandbox-mode, sandboxMode"
        )
        val secondRaw = """
            {"type":"message","role":"assistant","content":"pong","delta":true}
            {"type":"result","stats":{"input_tokens":1,"output_tokens":1,"tool_calls":0,"duration_ms":1000}}
        """.trimIndent()
        val second = ProcessResultValue(
            exitCode = 0,
            output = secondRaw,
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
        assertEquals(
            """
                pong
                [done] in=1 out=1 tools=0 time=1.0s
            """.trimIndent(),
            result.output
        )
        assertEquals(secondRaw, result.rawOutput)
    }

    private class RecordingRunner(vararg results: ProcessResult) : ContainerProcessRunner {
        private val resultQueue = ArrayDeque(results.toList())
        val commands = mutableListOf<List<String>>()

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
    }
}
