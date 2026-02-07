/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Integration test: debugger demo from ai-tests/07-debugger.md.
 *
 * Runs Claude CLI inside a Docker container with IntelliJ IDEA + MCP Steroid plugin.
 * The agent is asked to debug DemoByJonnyzzz.kt and find the sortedByDescending bug.
 *
 * The container is kept alive after the test for debugging (removed on next run).
 * Video is always recorded and mounted to the host for live preview.
 */
class DebuggerDemoTest {
/*
    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    fun `agent finds sortedByDescending bug via debugger`() {
        val apiKey = readAnthropicApiKey()
        val outputDir = resolveBuildOutputDir("debugger-demo")

        // Start IDE container (removes previous container with same name)
        val session = IdeContainerSession.start(
            containerName = "mcp-steroid-debugger-demo",
            pluginZipPath = resolvePluginZip(),
            ideaArchivePath = resolveIdeaArchive(),
            testProjectDir = resolveTestProject(),
            dockerDir = resolveDockerDir(),
            videoDir = outputDir.resolve("video").also { it.mkdirs() },
        )

        // Start live video preview on macOS (opens QuickTime once video starts)
        val previewThread = startLiveVideoPreview(session.videoFile)

        println("[TEST] Waiting for IDE to be ready...")
        session.waitForIdeReady(timeoutSeconds = 300)
        println("[TEST] IDE is ready")

        // Register MCP server with Claude CLI inside container
        session.registerClaudeMcp(apiKey)

        // Send debugger prompt adapted from ai-tests/07-debugger.md
        val prompt = buildString {
            appendLine("Debug the file DemoByJonnyzzz.kt in this project to find the bug in the leaderboard function.")
            appendLine()
            appendLine("Requirements:")
            appendLine("1. Find and open DemoByJonnyzzz.kt in the project")
            appendLine("2. Set a breakpoint at the sortedByDescending line (line 7)")
            appendLine("3. Create a run configuration for DemoByJonnyzzz" + "Kt and start the debugger")
            appendLine("4. Wait for the debugger to suspend at the breakpoint")
            appendLine("5. Evaluate variables and expressions to understand the bug")
            appendLine("6. Step over the line and observe what happens")
            appendLine("7. Identify the root cause of the bug")
            appendLine()
            appendLine("Use ONLY steroid_execute_code (no screenshots or UI interaction).")
            appendLine()
            appendLine("At the end of your analysis, output these markers on separate lines:")
            appendLine("BUG_FOUND: yes")
            appendLine("ROOT_CAUSE: <one line description>")
        }

        val result = session.runClaudePrompt(prompt, apiKey, timeoutSeconds = 600)

        // Stop video recording (file is already on host via mount)
        try {
            session.stopVideoRecording()
            println("[TEST] Video saved to: ${session.videoFile}")
        } catch (e: Exception) {
            println("[TEST] Failed to stop video: ${e.message}")
        }

        // Interrupt preview thread
        previewThread?.interrupt()

        // Assertions
        val combined = result.output + "\n" + result.stderr

        check(result.exitCode == 0) {
            "Claude CLI exited with code ${result.exitCode}.\nOutput:\n$combined"
        }

        check(combined.contains("steroid_execute_code")) {
            "Agent did not use steroid_execute_code tool.\nOutput:\n$combined"
        }

        check(combined.contains("sortedByDescending")) {
            "Agent output does not mention sortedByDescending.\nOutput:\n$combined"
        }

        val rootCausePatterns = listOf(
            "ignor", "unused", "discard", "new list", "does not modify",
            "return value", "not assigned", "not used", "immutable",
        )
        val foundRootCause = rootCausePatterns.any { pattern ->
            combined.contains(pattern, ignoreCase = true)
        }
        check(foundRootCause) {
            "Agent did not identify the root cause (ignored return value).\n" +
                    "Expected one of: $rootCausePatterns\nOutput:\n$combined"
        }

        println("[TEST] Agent successfully identified the sortedByDescending bug")
    }*/
}
