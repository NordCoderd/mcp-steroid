/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import com.jonnyzzz.mcpSteroid.testHelper.AiAgentSession
import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessResult
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.git.GitDriver

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

        // Try fast local clone from the bare repo cache mounted at /repo-cache
        val ownerAndRepo = testCase.repo.removeSuffix(".git")
        val clonedFromCache = git.cloneFromCachedBare(ownerAndRepo, projectDir)
        if (!clonedFromCache) {
            // Cache miss: fall back to a full remote clone (needed to checkout any commit)
            git.clone(testCase.cloneUrl, projectDir, shallow = false, timeoutSeconds = 120)
        }

        git.checkout(projectDir, testCase.baseCommit)
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
     * Kept minimal on purpose: the MCP tool descriptions and mcp-steroid://skill/
     * resources already contain all API usage guidance (threading rules, output
     * truncation, VFS patterns, etc.). This prompt is purely a task brief.
     *
     * @param withMcp when true, instructs the agent to use [steroid_execute_code] and IntelliJ IDEA;
     *                when false, instructs the agent to use shell commands (mvn/gradle/bash) only —
     *                used for the A/B comparison baseline (no IDE tooling).
     */
    fun buildPrompt(testCase: DpaiaTestCase, projectDir: String, withMcp: Boolean = true): String = buildString {
        val buildWrapper = if (testCase.buildSystem == "maven") "./mvnw" else "./gradlew"
        val compileCommand = if (testCase.buildSystem == "maven") {
            "./mvnw -DskipTests compile"
        } else {
            "./gradlew compileJava compileTestJava --console=plain"
        }
        val runClassCommand = if (testCase.buildSystem == "maven") {
            "./mvnw test -Dtest=<TestClass>"
        } else {
            "./gradlew test --tests <TestClass> --console=plain"
        }

        appendLine("You are working on a Java Spring project located at: `$projectDir`")
        appendLine()
        appendLine("## Task Context")
        appendLine()
        appendLine(testCase.problemStatement)
        appendLine()

        if (testCase.failToPass.isNotEmpty()) {
            appendLine("### FAIL_TO_PASS")
            for (test in testCase.failToPass) {
                appendLine("- `$test`")
            }
            appendLine()
        }

        if (testCase.passToPass.isNotEmpty()) {
            appendLine("### PASS_TO_PASS")
            for (test in testCase.passToPass) {
                appendLine("- `$test`")
            }
            appendLine()
        }

        appendLine("## Environment Facts")
        appendLine()
        appendLine("- Build system: **${testCase.buildSystem}**")
        appendLine("- Use the project wrapper only: `$buildWrapper`")
        appendLine("- Test-class command template: `$runClassCommand`")
        appendLine("- Check Docker once at start (`docker info`).")
        appendLine("- If Docker is unavailable, **still attempt to run FAIL_TO_PASS tests** — many use H2 in-memory DB and work fine without Docker.")
        appendLine("  - Run the target test class: `$runClassCommand`")
        appendLine("  - If it fails with a Docker connection error: report `ARENA_FIX_APPLIED: no` with the error output.")
        appendLine("  - If it fails for other reasons: fix those reasons and retry.")
        appendLine("  - **NEVER output `ARENA_FIX_APPLIED: yes` based on compile checks alone. Tests must actually run and pass.**")

        if (withMcp) {
            appendLine("- IntelliJ MCP is available; the project is already open and indexed.")
            appendLine("- Use `steroid_execute_code` for IDE actions and command execution.")
            appendLine("- Keep one stable `task_id` for this task.")
        } else {
            appendLine("- IntelliJ MCP tools are unavailable in this run.")
            appendLine("- Use shell commands only (`bash`, `cat`, `find`, `grep`, `$buildWrapper`).")
            appendLine("- Do not call `steroid_*` tools.")
        }

        appendLine()
        appendLine("## Success Markers")
        appendLine()
        appendLine("- Implement the requested behavior with minimal code changes.")
        appendLine("- FAIL_TO_PASS tests must pass — run them with `$runClassCommand` and confirm `BUILD SUCCESS`.")
        if (testCase.passToPass.isNotEmpty()) {
            appendLine("- PASS_TO_PASS tests must stay passing.")
        }
        appendLine("- `ARENA_FIX_APPLIED: yes` requires actual test output showing BUILD SUCCESS — not just compile checks.")
        appendLine("- Output these markers on separate lines:")
        appendLine("ARENA_FIX_APPLIED: yes")
        appendLine("ARENA_SUMMARY: <one line summary of what changed and what test output confirmed success>")
    }

    /**
     * Run a complete arena test: clone (unless pre-deployed), patch, prompt agent, collect result.
     *
     * @param testCase The test case to run
     * @param agent The AI agent session to use
     * @param timeoutSeconds Maximum time for the agent to work
     * @param prewarm Optional lambda to run before the agent timer (excluded from agent budget).
     *                Use when pre-warming is needed AFTER container creation (legacy approach).
     *                When [predeployedProjectDir] is set, the project is already indexed by
     *                [waitForProjectReady] so no prewarm is needed.
     * @param predeployedProjectDir Guest path of a project already deployed and indexed in the IDE.
     *                              When set, skips clone + patch (done by IntelliJProject.deploy()).
     *                              The project was deployed via [IntelliJProject.ProjectFromGitCommitAndPatch]
     *                              and indexed by [IntelliJContainer.waitForProjectReady].
     */
    fun runTest(
        testCase: DpaiaTestCase,
        agent: AiAgentSession,
        withMcp: Boolean = true,
        timeoutSeconds: Long = 1800,
        prewarm: ((projectDir: String) -> Unit)? = null,
        predeployedProjectDir: String? = null,
    ): ArenaTestResult {
        println("[ARENA] ========================================")
        println("[ARENA] Running: ${testCase.instanceId}")
        println("[ARENA] Repo: ${testCase.repo}")
        println("[ARENA] Tags: ${testCase.tags}")
        println("[ARENA] Build: ${testCase.buildSystem}")
        println("[ARENA] ========================================")

        // Step 1+2: Clone and patch, unless the project was pre-deployed before IntelliJ started.
        val projectDir: String
        if (predeployedProjectDir != null) {
            println("[ARENA] Using pre-deployed project at $predeployedProjectDir (skipping clone+patch)")
            projectDir = predeployedProjectDir
        } else {
            projectDir = cloneAndCheckout(testCase)
            applyTestPatch(testCase, projectDir)
        }

        // Step 3: Build prompt
        val prompt = buildPrompt(testCase, projectDir, withMcp = withMcp)
        println("[ARENA] Prompt length: ${prompt.length} chars")

        // Pre-warm (NOT measured — IDE setup before the agent's timer starts)
        val prewarmStartMs = System.currentTimeMillis()
        if (prewarm != null) {
            println("[ARENA] Pre-warming IDE for ${testCase.instanceId} ...")
            prewarm(projectDir)
            println("[ARENA] Pre-warm complete")
        }
        val prewarmDurationMs = System.currentTimeMillis() - prewarmStartMs

        // Step 4: Run agent (START MEASURING)
        println("[ARENA] Running agent (timeout: ${timeoutSeconds}s) ...")
        val agentStartMs = System.currentTimeMillis()
        val agentResult = agent.runPrompt(prompt, timeoutSeconds = timeoutSeconds)
        val agentDurationMs = System.currentTimeMillis() - agentStartMs

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
            agentDurationMs = agentDurationMs,
            prewarmDurationMs = prewarmDurationMs,
        )
    }

    /**
     * Evaluate the agent's response against the test case expectations.
     */
    private fun evaluate(testCase: DpaiaTestCase, result: ProcessResult): ArenaEvaluation {
        val combined = result.stdout + "\n" + result.stderr

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
    /** Wall-clock milliseconds spent inside [agent.runPrompt] (excludes git clone and patch apply). */
    val agentDurationMs: Long = 0L,
    /** Wall-clock milliseconds spent in IDE pre-warm (open + index); excluded from agent budget. */
    val prewarmDurationMs: Long = 0L,
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
