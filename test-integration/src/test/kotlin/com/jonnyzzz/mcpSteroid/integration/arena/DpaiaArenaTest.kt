/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import com.jonnyzzz.mcpSteroid.integration.infra.IdeTestFolders
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.testHelper.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.assertOutputContains
import com.jonnyzzz.mcpSteroid.testHelper.docker.BareRepoCache
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Integration test that runs dpaia.dev arena test cases.
 *
 * Each test case:
 * 1. Clones a repository from the dpaia dataset inside the Docker container
 * 2. Checks out the specified base commit
 * 3. Applies the test patch (failing tests that define expected behavior)
 * 4. Pre-warms IntelliJ: opens the project via MCP Steroid and waits for indexing
 * 5. Asks an AI agent to fix the code via MCP Steroid (project already open and indexed)
 * 6. Evaluates whether the agent engaged meaningfully
 * 7. Writes a run summary JSON for the improvement pipeline (docs/dpaia-runs/)
 *
 * The dataset is downloaded from:
 * https://github.com/dpaia/ee-dataset/blob/main/datasets/java-spring-ee-dataset.json
 *
 * To run a specific test case, set the system property:
 *   -Darena.test.instanceId=dpaia__empty__maven__springboot3-3
 *
 * To run only tests with a specific tag:
 *   -Darena.test.tag=JPA
 *
 * To limit the number of test cases (for CI):
 *   -Darena.test.maxCases=3
 */
class DpaiaArenaTest {

    @TestFactory
    @Timeout(value = 60, unit = TimeUnit.MINUTES)
    fun `arena test cases`(): List<DynamicTest> {
        val selectedCases = selectTestCases()
        println("[ARENA] Selected ${selectedCases.size} test case(s) for execution")

        val agents = session.aiAgents.aiAgents

        return selectedCases.flatMap { testCase ->
            agents.map { (agentName, agent) ->
                DynamicTest.dynamicTest("[$agentName] ${testCase.instanceId}") {
                    val runner = ArenaTestRunner(
                        container = session.scope,
                        projectGuestDir = ARENA_WORKSPACE,
                    )

                    val result = runner.runTest(
                        testCase = testCase,
                        agent = agent,
                        timeoutSeconds = 1800,
                        prewarm = { projectDir ->
                            // Open the cloned project in IntelliJ and wait for full indexing.
                            // This runs BEFORE the agent timer starts so IDE setup time is
                            // excluded from the agent's 30-minute budget.
                            println("[ARENA] Pre-warming: opening $projectDir in IntelliJ IDEA...")
                            session.mcpSteroid.mcpOpenProject(projectDir)
                            session.mcpSteroid.waitForArenaProjectIndexed(projectDir)
                            println("[ARENA] Pre-warm complete: $projectDir")
                        },
                    )

                    // Write run summary BEFORE assertions so the improvement pipeline
                    // always gets data even when the test fails (e.g. agent didn't use MCP).
                    writeRunSummary(testCase, agentName, result)

                    // Basic assertions: agent ran successfully and used MCP Steroid
                    result.agentResult.assertExitCode(0, message = "arena test ${testCase.instanceId}")
                    result.agentResult.assertOutputContains(
                        "steroid_execute_code",
                        message = "agent must use steroid_execute_code for ${testCase.instanceId}"
                    )

                    println("[ARENA] Test ${testCase.instanceId} completed with agent '$agentName'")
                    println("[ARENA]   Claimed fix: ${result.evaluation.agentClaimedFix}")
                    println("[ARENA]   Summary: ${result.evaluation.agentSummary ?: "(none)"}")
                }
            }
        }
    }

    companion object {
        /** Dataset URL */
        private const val DATASET_URL =
            "https://raw.githubusercontent.com/dpaia/ee-dataset/main/datasets/java-spring-ee-dataset.json"

        /** Container directory for cloned arena projects */
        private const val ARENA_WORKSPACE = "/home/agent/arena-projects"

        /**
         * Default test case to run when no system property is set.
         * This is a simple JPA entity test from a small Spring Boot project.
         */
        private const val DEFAULT_INSTANCE_ID = "dpaia__empty__maven__springboot3-3"

        @JvmStatic
        val lifetime by lazy { CloseableStackHost() }

        val session by lazy {
            IntelliJContainer.create(
                lifetime,
                "ide-agent",
                consoleTitle = "dpaia-arena",
            ).waitForProjectReady()
        }

        private val dataset by lazy {
            println("[ARENA] Downloading dataset from $DATASET_URL ...")
            val cases = DpaiaDatasetLoader.loadFromUrl(DATASET_URL)
            println("[ARENA] Loaded ${cases.size} test cases")
            cases
        }

        private fun selectTestCases(): List<DpaiaTestCase> {
            // Option 1: Specific instance ID via system property (defaults to DEFAULT_INSTANCE_ID)
            val specificId = System.getProperty("arena.test.instanceId") ?: DEFAULT_INSTANCE_ID
            if (System.getProperty("arena.test.tag") == null && System.getProperty("arena.test.maxCases") == null) {
                return listOf(DpaiaDatasetLoader.findById(dataset, specificId))
            }

            // Option 2: Filter by tag
            val tag = System.getProperty("arena.test.tag")
            val filtered = if (tag != null) {
                DpaiaDatasetLoader.filterByTag(dataset, tag)
            } else {
                dataset
            }

            // Option 3: Limit number of cases
            val maxCases = System.getProperty("arena.test.maxCases")?.toIntOrNull() ?: 1

            return filtered.take(maxCases)
        }

        /**
         * Write a run summary JSON for the improvement pipeline (docs/dpaia-runs/).
         *
         * Path: [IdeTestFolders.testOutputDir]/dpaia-arena-run-{instanceId}.json
         *
         * The shell pipeline (run-one.sh) reads this to locate the arena run directory and
         * metrics without searching through the build directory tree.
         *
         * Called BEFORE assertions so data is always captured even on test failure.
         */
        private fun writeRunSummary(testCase: DpaiaTestCase, agentName: String, result: ArenaTestResult) {
            val combined = result.agentResult.output + "\n" + result.agentResult.stderr
            val execCodeCalls = combined.lines().count { "steroid_execute_code" in it.lowercase() }

            val summary = buildJsonObject {
                put("instance_id", testCase.instanceId)
                put("agent", agentName)
                put("run_dir", session.runDirInContainer.absolutePath)
                put("exit_code", result.agentResult.exitCode ?: -1)
                put("agent_claimed_fix", result.evaluation.agentClaimedFix)
                put("used_mcp_steroid", result.evaluation.usedMcpSteroid)
                put("exec_code_calls", execCodeCalls)
                put("agent_duration_ms", result.agentDurationMs)
                put("prewarm_duration_ms", result.prewarmDurationMs)
                put("agent_summary", result.evaluation.agentSummary ?: "")
                put("timestamp", java.time.Instant.now().toString())
            }

            val summaryFile = IdeTestFolders.testOutputDir.resolve("dpaia-arena-run-${testCase.instanceId}.json")
            summaryFile.parentFile.mkdirs()
            summaryFile.writeText(summary.toString())
            println("[ARENA] Run summary written to: ${summaryFile.absolutePath}")
            println("[ARENA] Run dir (for improvement pipeline): ${session.runDirInContainer.absolutePath}")
        }

        @JvmStatic
        @BeforeAll
        fun beforeAll() {
            // Warm the bare repo cache on the host before the container starts.
            // This ensures /repo-cache is populated so cloneFromCachedBare() works inside
            // the container (avoids slow GitHub clones during each test run).
            val cacheDir = IdeTestFolders.repoCacheDirOrNull
            if (cacheDir != null) {
                BareRepoCache.warmDpaiaRepos(cacheDir)
            }
            session.toString()
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            lifetime.closeAllStacks()
        }
    }
}
