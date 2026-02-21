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
     * @param withMcp when true, instructs the agent to use [steroid_execute_code] and IntelliJ IDEA;
     *                when false, instructs the agent to use shell commands (mvn/gradle/bash) only —
     *                used for the A/B comparison baseline (no IDE tooling).
     */
    fun buildPrompt(testCase: DpaiaTestCase, projectDir: String, withMcp: Boolean = true): String = buildString {
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
            appendLine("- Use `./mvnw` commands for building and testing (system `mvn` is not installed)")
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
        if (withMcp) {
            appendLine("The project at `$projectDir` is already open and fully indexed in IntelliJ IDEA.")
            appendLine()
            appendLine("**⚠️ Environment constraints (read BEFORE running any tests):**")
            appendLine("- **Docker is NOT available.** Tests using `@Testcontainers` or requiring a running database")
            appendLine("  will fail at Spring context load with: 'Could not find a valid Docker environment'.")
            appendLine("  → If integration tests fail at startup, that is the reason. Do NOT try to fix Docker.")
            appendLine("  → Instead: run unit tests (no Spring context) + `runInspectionsDirectly` + `mvnw test-compile`.")
            appendLine("- **Use `./mvnw` (Maven wrapper), not `mvn`** — the system `mvn` is not installed.")
            appendLine("  → Always: `ProcessBuilder(\"./mvnw\", \"test\", \"-Dtest=MyUnitTest\", \"-q\")`")
            appendLine("- If integration tests fail, document it in ARENA_SUMMARY and verify unit tests instead.")
            appendLine()
            appendLine("1. Call `steroid_list_projects` to confirm project name")
            appendLine("2. **Read the failing test files first** — use steroid_execute_code to print the full source of")
            appendLine("   each failing test. Extract expected method signatures, field names, and annotations.")
            appendLine("   Implement from the test expectations, not from guesses.")
            appendLine("3. Analyze the existing codebase to understand what already exists")
            appendLine("4. Implement the necessary changes to fix the issue")
            appendLine("5. Use steroid_execute_code to verify your changes compile (runInspectionsDirectly) and")
            appendLine("   run the failing tests — this is faster than mvn/gradle")
            appendLine()
            appendLine("**IMPORTANT — steroid_execute_code bypasses the agent sandbox**:")
            appendLine("Your agent's native file tools (read_file, write_file) are restricted to /home/agent.")
            appendLine("The project lives at $projectDir which is OUTSIDE that sandbox.")
            appendLine("→ If any file tool rejects a path, switch IMMEDIATELY to steroid_execute_code.")
            appendLine("→ Do NOT use shell heredocs (cat << 'EOF') for multi-line Java/Kotlin files — heredoc")
            appendLine("  syntax is often rejected by the shell parser. Use steroid_execute_code VFS writes instead.")
            appendLine()
            appendLine("**File creation via VFS (use this instead of shell):**")
            appendLine("```kotlin")
            appendLine("writeAction {")
            appendLine("    val dir = VfsUtil.createDirectoryIfMissing(project.baseDir, \"src/main/java/com/example/model\")")
            appendLine("    val f = dir.findChild(\"Product.java\") ?: dir.createChildData(this, \"Product.java\")")
            appendLine("    // Build content with joinToString — never put 'import ...' at line start in a triple-quoted string")
            appendLine("    VfsUtil.saveText(f, listOf(")
            appendLine("        \"package com.example.model;\",")
            appendLine("        \"import\" + \" jakarta.persistence.Entity;\",")
            appendLine("        \"@Entity public class Product { }\"")
            appendLine("    ).joinToString(\"\\n\"))")
            appendLine("}")
            appendLine("println(\"created\")")
            appendLine("```")
            appendLine()
            if (testCase.buildSystem == "maven") {
                appendLine("**After editing pom.xml, trigger Maven sync:**")
                appendLine("```kotlin")
                appendLine("import org.jetbrains.idea.maven.project.MavenProjectsManager")
                appendLine("MavenProjectsManager.getInstance(project).forceUpdateAllProjectsOrFindAllAvailablePomFiles()")
                appendLine("println(\"Maven sync triggered\")")
                appendLine("```")
                appendLine()
            }
            appendLine("**If steroid_execute_code fails**: read the error carefully — it contains the compiler output.")
            appendLine("Do NOT abandon steroid_execute_code after one failure. Diagnose the error and retry.")
            appendLine("Common cause: 'import' statements at line-start inside triple-quoted strings are extracted")
            appendLine("as Kotlin imports → use '\"import\" + \" foo.Bar;\"' or joinToString() instead.")
            appendLine()
            appendLine("Use steroid_execute_code for IDE interactions. The project is already open.")
        } else {
            appendLine("1. Navigate the project at `$projectDir` using bash/shell commands")
            appendLine("2. Read source files to understand the problem")
            appendLine("3. Implement the necessary changes using file editing tools")
            val buildCmd = if (testCase.buildSystem == "maven") "./mvnw test" else "./gradlew test"
            appendLine("4. Verify the fix by running `$buildCmd` in `$projectDir`")
            appendLine()
            appendLine("Use shell commands (bash, find, cat, grep) and the build tool to navigate and verify.")
            appendLine("No IntelliJ IDE tools are available.")
        }
        appendLine()
        appendLine("When done, output these markers on separate lines:")
        appendLine("ARENA_FIX_APPLIED: yes")
        appendLine("ARENA_SUMMARY: <one line summary of what you changed>")
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
