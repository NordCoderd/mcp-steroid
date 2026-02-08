/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import com.jonnyzzz.mcpSteroid.testHelper.AiAgentSession
import com.jonnyzzz.mcpSteroid.testHelper.ProcessResult
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.GitDriver

/**
 * Manages the execution of a dpaia arena test case inside a Docker container
 * with IntelliJ IDEA + MCP Steroid.
 *
 * Responsibilities:
 * 1. Clone the test case repository inside the container
 * 2. Check out the base commit
 * 3. Apply test patches (so the agent has failing tests to fix)
 * 4. Build a prompt from the problem statement
 * 5. Run the prompt via an AI agent
 * 6. Evaluate the result
 */
class ArenaTestRunner(
    private val container: ContainerDriver,
    private val projectGuestDir: String,
) {

    private val git = GitDriver(container)

    /**
     * Clone a repository and check out a specific commit inside the container.
     *
     * @param testCase The test case with repo URL and base commit
     * @return The guest directory path where the project was cloned
     */
    fun cloneAndCheckout(testCase: DpaiaTestCase): String {
        // Use a unique suffix so parallel runs for different agents don't collide
        val suffix = System.nanoTime().toString(36)
        val projectDir = "$projectGuestDir/${testCase.repoName}-$suffix"

        println("[ARENA] Cloning ${testCase.cloneUrl} into $projectDir ...")
        git.cloneAndCheckout(testCase.cloneUrl, projectDir, testCase.baseCommit, timeoutSeconds = 120)
        return projectDir
    }

    /**
     * Apply the test patch to the cloned repository so the agent has
     * failing tests that define expected behavior.
     *
     * @param testCase The test case containing the test patch
     * @param projectDir The guest directory where the repo was cloned
     */
    fun applyTestPatch(testCase: DpaiaTestCase, projectDir: String) {
        if (testCase.testPatch.isBlank()) {
            println("[ARENA] No test patch to apply for ${testCase.instanceId}")
            return
        }
        println("[ARENA] Applying test patch for ${testCase.instanceId} ...")
        git.applyPatch(projectDir, testCase.testPatch)
    }

    /**
     * Build the prompt that will be sent to the AI agent.
     *
     * The prompt includes:
     * - The problem statement from the dataset
     * - Information about the build system
     * - Instructions to use MCP Steroid
     * - Hints about which tests to focus on
     */
    fun buildPrompt(testCase: DpaiaTestCase, projectDir: String): String = buildString {
        appendLine("You are working on a Java Spring project located at: $projectDir")
        appendLine()
        appendLine("## Problem Statement")
        appendLine()
        appendLine(testCase.problemStatement)
        appendLine()
        appendLine("## Project Information")
        appendLine()
        appendLine("- Build system: ${testCase.buildSystem}")
        if (testCase.buildSystem == "maven") {
            appendLine("- Use `mvn` commands for building and testing")
        } else {
            appendLine("- Use `./gradlew` commands for building and testing")
        }
        appendLine()

        if (testCase.failToPass.isNotEmpty()) {
            appendLine("## Tests to Fix")
            appendLine()
            appendLine("The following tests are currently failing and should pass after your fix:")
            for (test in testCase.failToPass) {
                appendLine("- `$test`")
            }
            appendLine()
        }

        if (testCase.passToPass.isNotEmpty()) {
            appendLine("## Regression Tests")
            appendLine()
            appendLine("The following tests must continue to pass:")
            for (test in testCase.passToPass) {
                appendLine("- `$test`")
            }
            appendLine()
        }

        appendLine("## Instructions")
        appendLine()
        appendLine("1. Open the project at `$projectDir` in IntelliJ IDEA using steroid_execute_code")
        appendLine("2. Analyze the codebase to understand the problem")
        appendLine("3. Implement the necessary changes to fix the issue")
        appendLine("4. Use steroid_execute_code to verify your changes compile")
        appendLine()
        appendLine("Use ONLY steroid_execute_code for IDE interactions.")
        appendLine()
        appendLine("When done, output these markers on separate lines:")
        appendLine("ARENA_FIX_APPLIED: yes")
        appendLine("ARENA_SUMMARY: <one line summary of what you changed>")
    }

    /**
     * Run a complete arena test: clone, patch, prompt agent, collect result.
     *
     * @param testCase The test case to run
     * @param agent The AI agent session to use
     * @param timeoutSeconds Maximum time for the agent to work
     * @return The arena test result with agent output and evaluation
     */
    fun runTest(
        testCase: DpaiaTestCase,
        agent: AiAgentSession,
        timeoutSeconds: Long = 900,
    ): ArenaTestResult {
        println("[ARENA] ========================================")
        println("[ARENA] Running: ${testCase.instanceId}")
        println("[ARENA] Repo: ${testCase.repo}")
        println("[ARENA] Tags: ${testCase.tags}")
        println("[ARENA] Build: ${testCase.buildSystem}")
        println("[ARENA] ========================================")

        // Step 1: Clone and checkout
        val projectDir = cloneAndCheckout(testCase)

        // Step 2: Apply test patch (failing tests)
        applyTestPatch(testCase, projectDir)

        // Step 3: Build prompt
        val prompt = buildPrompt(testCase, projectDir)
        println("[ARENA] Prompt length: ${prompt.length} chars")

        // Step 4: Run agent
        println("[ARENA] Running agent (timeout: ${timeoutSeconds}s) ...")
        val agentResult = agent.runPrompt(prompt, timeoutSeconds = timeoutSeconds)

        // Step 5: Evaluate
        val evaluation = evaluate(testCase, agentResult)

        println("[ARENA] ========================================")
        println("[ARENA] Result for ${testCase.instanceId}:")
        println("[ARENA]   Agent exit code: ${agentResult.exitCode}")
        println("[ARENA]   Agent claimed fix: ${evaluation.agentClaimedFix}")
        println("[ARENA]   Used MCP: ${evaluation.usedMcpSteroid}")
        println("[ARENA] ========================================")

        return ArenaTestResult(
            testCase = testCase,
            agentResult = agentResult,
            evaluation = evaluation,
        )
    }

    /**
     * Evaluate the agent's response against the test case expectations.
     */
    private fun evaluate(testCase: DpaiaTestCase, result: ProcessResult): ArenaEvaluation {
        val combined = result.output + "\n" + result.stderr

        return ArenaEvaluation(
            agentExitedSuccessfully = result.exitCode == 0,
            usedMcpSteroid = combined.contains("steroid_execute_code", ignoreCase = true),
            agentClaimedFix = combined.contains("ARENA_FIX_APPLIED: yes", ignoreCase = true),
            agentSummary = extractMarker(combined, "ARENA_SUMMARY:"),
        )
    }

    private fun extractMarker(text: String, marker: String): String? {
        val line = text.lines().find { it.trimStart().startsWith(marker, ignoreCase = true) }
        return line?.substringAfter(marker)?.trim()?.takeIf { it.isNotBlank() }
    }
}

/**
 * Result of running an arena test case.
 */
data class ArenaTestResult(
    val testCase: DpaiaTestCase,
    val agentResult: ProcessResult,
    val evaluation: ArenaEvaluation,
)

/**
 * Evaluation metrics for an arena test run.
 */
data class ArenaEvaluation(
    /** Whether the agent process exited with code 0 */
    val agentExitedSuccessfully: Boolean,

    /** Whether the agent used steroid_execute_code */
    val usedMcpSteroid: Boolean,

    /** Whether the agent reported it applied a fix */
    val agentClaimedFix: Boolean,

    /** The agent's one-line summary of changes, if provided */
    val agentSummary: String?,
)
