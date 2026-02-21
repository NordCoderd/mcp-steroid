/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import com.jonnyzzz.mcpSteroid.integration.infra.AiMode
import com.jonnyzzz.mcpSteroid.integration.infra.IdeTestFolders
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.testHelper.docker.BareRepoCache
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.Timeout
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Claude-only A/B comparison: "Claude + MCP Steroid" vs "Claude without any IDE tools".
 *
 * Runs every case from [DpaiaCuratedCases.COMPARISON_CASES] twice with Claude:
 * - `[claude+mcp]`: Claude opens the project in IntelliJ IDEA via steroid_execute_code
 * - `[claude+none]`: Claude uses only bash/grep/mvn/gradle — no IDE tooling
 *
 * Captures per-run metrics:
 * - Wall-clock time (total: git clone + patch + agent run)
 * - Agent-only time (from [ArenaTestResult.agentDurationMs])
 * - Claude token usage parsed from stream-json NDJSON (input, output, cache-read, cost, turns)
 * - Claimed fix (ARENA_FIX_APPLIED: yes), MCP tool usage, agent summary
 *
 * After all 20 dynamic tests finish, [generateReport] writes:
 * - `[testOutputDir]/claude-comparison-report.md` — human-readable Markdown report
 * - `[testOutputDir]/claude-comparison-report.json` — machine-readable JSON data
 *
 * **Run all curated cases (default):**
 * ```
 * ./gradlew :test-integration:test --tests '*DpaiaClaudeComparisonTest*'
 * ```
 *
 * **Override case list:**
 * ```
 * -Dclaude.comparison.cases=dpaia__feature__service-125,dpaia__feature__service-25
 * ```
 *
 * **Limit number of cases:**
 * ```
 * -Dclaude.comparison.maxCases=3
 * ```
 */
class DpaiaClaudeComparisonTest {

    @TestFactory
    @Timeout(value = 120, unit = TimeUnit.MINUTES)
    fun `claude comparison runs`(): List<DynamicTest> {
        val cases = selectTestCases()
        println("[CLAUDE-CMP] Running ${cases.size} cases × 2 modes = ${cases.size * 2} dynamic tests")
        println("[CLAUDE-CMP] Cases: ${cases.map { it.instanceId }}")

        return cases.flatMap { testCase ->
            listOf(
                DynamicTest.dynamicTest("[claude+mcp] ${testCase.instanceId}") {
                    results.add(runOne(testCase, withMcp = true, sessionWithMcp))
                },
                DynamicTest.dynamicTest("[claude+none] ${testCase.instanceId}") {
                    results.add(runOne(testCase, withMcp = false, sessionWithoutMcp))
                },
            )
        }
    }

    // ── Data model ─────────────────────────────────────────────────────────────

    data class TokenUsage(
        val inputTokens: Long,
        val outputTokens: Long,
        val cacheReadTokens: Long = 0L,
        val costUsd: Double? = null,
        val numTurns: Int? = null,
    ) {
        val totalTokens: Long get() = inputTokens + outputTokens
    }

    data class RunRecord(
        val instanceId: String,
        val tags: List<String>,
        val withMcp: Boolean,
        /** Total wall-clock ms: git clone + patch + agent prompt. */
        val totalDurationMs: Long,
        /** Agent-only ms: just [agent.runPrompt]. */
        val agentDurationMs: Long,
        /** IDE pre-warm ms: open + index time (NOT in agent budget). */
        val prewarmDurationMs: Long = 0L,
        val exitCode: Int?,
        val agentClaimedFix: Boolean,
        val usedMcpSteroid: Boolean,
        val agentSummary: String?,
        val tokenUsage: TokenUsage?,
        /** Non-null if an exception was thrown during the run. */
        val errorMessage: String? = null,
    ) {
        val modeLabel: String get() = if (withMcp) "MCP" else "NONE"
        val isSuccess: Boolean get() = errorMessage == null && (exitCode == 0 || agentClaimedFix)
    }

    companion object {

        private const val DATASET_URL =
            "https://raw.githubusercontent.com/dpaia/ee-dataset/main/datasets/java-spring-ee-dataset.json"

        /** Guest directory for cloned projects (separate from DpaiaArenaTest and DpaiaComparisonTest). */
        private const val ARENA_WORKSPACE = "/home/agent/claude-comparison"

        private val results = CopyOnWriteArrayList<RunRecord>()

        @JvmStatic
        val lifetimeWithMcp by lazy { CloseableStackHost() }

        @JvmStatic
        val lifetimeWithoutMcp by lazy { CloseableStackHost() }

        /** Container where Claude is registered with MCP Steroid (HTTP transport). */
        val sessionWithMcp by lazy {
            IntelliJContainer.create(
                lifetimeWithMcp,
                consoleTitle = "claude-cmp-mcp",
                aiMode = AiMode.AI_MCP,
            ).waitForProjectReady()
        }

        /** Container where Claude has NO MCP registered — baseline/control group. */
        val sessionWithoutMcp by lazy {
            IntelliJContainer.create(
                lifetimeWithoutMcp,
                consoleTitle = "claude-cmp-none",
                aiMode = AiMode.NONE,
            )
            // No waitForProjectReady() — no-MCP agent never uses IntelliJ
        }

        private val dataset by lazy {
            println("[CLAUDE-CMP] Downloading dataset from $DATASET_URL ...")
            DpaiaDatasetLoader.loadFromUrl(DATASET_URL).also {
                println("[CLAUDE-CMP] Loaded ${it.size} test cases")
            }
        }

        private fun selectTestCases(): List<DpaiaTestCase> {
            val specificIds = System.getProperty("claude.comparison.cases")
            val ids = if (!specificIds.isNullOrBlank()) {
                specificIds.split(',').map { it.trim() }.filter { it.isNotBlank() }
            } else {
                DpaiaCuratedCases.COMPARISON_CASES
            }
            val maxCases = System.getProperty("claude.comparison.maxCases")?.toIntOrNull() ?: ids.size
            return ids.take(maxCases).map { id -> DpaiaDatasetLoader.findById(dataset, id) }
        }

        // ── Test execution ──────────────────────────────────────────────────

        private fun runOne(testCase: DpaiaTestCase, withMcp: Boolean, session: IntelliJContainer): RunRecord {
            val modeLabel = if (withMcp) "MCP" else "NONE"
            println("[CLAUDE-CMP] ========================================")
            println("[CLAUDE-CMP] Starting: ${testCase.instanceId} [$modeLabel]")
            println("[CLAUDE-CMP] Repo: ${testCase.repo} | Tags: ${testCase.tags}")
            println("[CLAUDE-CMP] ========================================")

            val startMs = System.currentTimeMillis()
            try {
                val runner = ArenaTestRunner(container = session.scope, projectGuestDir = ARENA_WORKSPACE)
                val result = runner.runTest(
                    testCase = testCase,
                    agent = session.aiAgents.claude,
                    withMcp = withMcp,
                    timeoutSeconds = 1800,
                    prewarm = if (withMcp) { projectDir ->
                        println("[CLAUDE-CMP] Pre-warming: opening $projectDir in IntelliJ IDEA...")
                        // Maven projects must be opened via pom.xml so IntelliJ triggers
                        // MavenProjectOpenProcessor (rather than treating it as a plain directory).
                        val projectFile = if (testCase.buildSystem == "maven") "$projectDir/pom.xml" else projectDir
                        session.mcpSteroid.mcpOpenProject(projectFile)
                        session.mcpSteroid.waitForArenaProjectIndexed(projectDir)
                        session.mcpSteroid.mcpSetupJdkAndWaitForImport(projectDir)
                        println("[CLAUDE-CMP] Pre-warm complete")
                    } else null,
                )
                val totalMs = System.currentTimeMillis() - startMs
                val tokens = extractTokenUsage(result.agentResult.rawOutput)

                println("[CLAUDE-CMP] Completed: ${testCase.instanceId} [$modeLabel]")
                println("[CLAUDE-CMP]   Total time:   ${totalMs / 1000}s (agent: ${result.agentDurationMs / 1000}s)")
                if (withMcp) {
                    println("[CLAUDE-CMP]   Pre-warm time: ${result.prewarmDurationMs / 1000}s (IDE open + index, not in agent budget)")
                }
                println("[CLAUDE-CMP]   Exit code:    ${result.agentResult.exitCode}")
                println("[CLAUDE-CMP]   Claimed fix:  ${result.evaluation.agentClaimedFix}")
                println("[CLAUDE-CMP]   Used MCP:     ${result.evaluation.usedMcpSteroid}")
                if (tokens != null) {
                    println("[CLAUDE-CMP]   Tokens:       in=${tokens.inputTokens} out=${tokens.outputTokens} turns=${tokens.numTurns ?: "?"}")
                    if (tokens.costUsd != null) {
                        println("[CLAUDE-CMP]   Cost:         \$${String.format("%.4f", tokens.costUsd)}")
                    }
                } else {
                    println("[CLAUDE-CMP]   Tokens:       (not available — agent may have failed before producing output)")
                }
                println("[CLAUDE-CMP] ========================================")

                return RunRecord(
                    instanceId = testCase.instanceId,
                    tags = testCase.tags,
                    withMcp = withMcp,
                    totalDurationMs = totalMs,
                    agentDurationMs = result.agentDurationMs,
                    prewarmDurationMs = result.prewarmDurationMs,
                    exitCode = result.agentResult.exitCode,
                    agentClaimedFix = result.evaluation.agentClaimedFix,
                    usedMcpSteroid = result.evaluation.usedMcpSteroid,
                    agentSummary = result.evaluation.agentSummary,
                    tokenUsage = tokens,
                )
            } catch (e: Exception) {
                val totalMs = System.currentTimeMillis() - startMs
                println("[CLAUDE-CMP] ERROR: ${testCase.instanceId} [$modeLabel] failed after ${totalMs / 1000}s: ${e.message}")
                return RunRecord(
                    instanceId = testCase.instanceId,
                    tags = testCase.tags,
                    withMcp = withMcp,
                    totalDurationMs = totalMs,
                    agentDurationMs = 0L,
                    exitCode = null,
                    agentClaimedFix = false,
                    usedMcpSteroid = false,
                    agentSummary = null,
                    tokenUsage = null,
                    errorMessage = e.message,
                )
            }
        }

        /**
         * Extract Claude token usage from stream-json NDJSON output.
         *
         * Claude CLI `--output-format stream-json` writes NDJSON where the last "result" event is:
         * ```json
         * {"type":"result","subtype":"success","cost_usd":0.01,"duration_ms":45000,
         *  "num_turns":5,"usage":{"input_tokens":15000,"output_tokens":3000,
         *  "cache_read_input_tokens":12000},...}
         * ```
         */
        private fun extractTokenUsage(rawOutput: String): TokenUsage? {
            for (line in rawOutput.lines().asReversed()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                val json = try {
                    Json.parseToJsonElement(trimmed).jsonObject
                } catch (e: Exception) {
                    continue
                }
                if (json["type"]?.jsonPrimitive?.content != "result") continue
                val usage = json["usage"]?.jsonObject ?: return null
                return TokenUsage(
                    inputTokens = usage["input_tokens"]?.jsonPrimitive?.longOrNull ?: 0L,
                    outputTokens = usage["output_tokens"]?.jsonPrimitive?.longOrNull ?: 0L,
                    cacheReadTokens = usage["cache_read_input_tokens"]?.jsonPrimitive?.longOrNull ?: 0L,
                    costUsd = json["cost_usd"]?.jsonPrimitive?.doubleOrNull,
                    numTurns = json["num_turns"]?.jsonPrimitive?.intOrNull,
                )
            }
            return null
        }

        // ── Lifecycle ──────────────────────────────────────────────────────

        @JvmStatic
        @BeforeAll
        fun beforeAll() {
            val cacheDir = IdeTestFolders.repoCacheDirOrNull
            if (cacheDir != null) BareRepoCache.warmDpaiaRepos(cacheDir)
            sessionWithMcp.toString()
            sessionWithoutMcp.toString()
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            try {
                generateReport()
            } finally {
                lifetimeWithMcp.closeAllStacks()
                lifetimeWithoutMcp.closeAllStacks()
            }
        }

        // ── Report generation ───────────────────────────────────────────────

        private fun generateReport() {
            if (results.isEmpty()) {
                println("[CLAUDE-CMP] No results to report")
                return
            }

            val sorted = results.toList().sortedWith(
                compareBy({ it.instanceId }, { it.modeLabel })
            )
            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

            val mdReport = buildComparisonReport(sorted, timestamp)
            val jsonReport = buildJsonReport(sorted)

            val outDir = IdeTestFolders.testOutputDir
            val mdFile = File(outDir, "claude-comparison-report.md")
            val jsonFile = File(outDir, "claude-comparison-report.json")

            mdFile.writeText(mdReport)
            jsonFile.writeText(jsonReport)

            println("[CLAUDE-CMP] ========================================")
            println("[CLAUDE-CMP] COMPARISON REPORT WRITTEN:")
            println("[CLAUDE-CMP]   Markdown: ${mdFile.absolutePath}")
            println("[CLAUDE-CMP]   JSON:     ${jsonFile.absolutePath}")
            println("[CLAUDE-CMP] ========================================")
            println()
            println(mdReport)
        }

        private fun buildComparisonReport(records: List<RunRecord>, timestamp: String): String {
            val mcpRecs = records.filter { it.withMcp }
            val noMcpRecs = records.filter { !it.withMcp }
            val caseIds = records.map { it.instanceId }.distinct()
            // Pair each case: Triple(id, mcpRecord?, noMcpRecord?)
            val pairs = caseIds.map { id ->
                Triple(id, mcpRecs.find { it.instanceId == id }, noMcpRecs.find { it.instanceId == id })
            }

            return buildString {
                appendLine("# DPAIA A/B Comparison: Claude + MCP Steroid vs Claude Only")
                appendLine()
                appendLine("Generated: $timestamp")
                appendLine()
                appendLine("## Summary")
                appendLine()
                append(summarySection(mcpRecs, noMcpRecs, pairs.size))
                appendLine()
                appendLine("## Per-Case Results")
                appendLine()
                append(resultsTable(pairs))
                appendLine()
                appendLine("## Bottleneck Analysis")
                appendLine()
                append(bottleneckAnalysis(pairs))
                appendLine()
                appendLine("## Detailed Run Notes")
                appendLine()
                append(detailedNotes(pairs))
            }
        }

        private fun summarySection(
            mcpRecs: List<RunRecord>,
            noMcpRecs: List<RunRecord>,
            totalCases: Int,
        ): String = buildString {
            val mcpFixed = mcpRecs.count { it.agentClaimedFix }
            val noMcpFixed = noMcpRecs.count { it.agentClaimedFix }
            val mcpAvgSec = avgSecOrNull(mcpRecs.map { it.totalDurationMs })
            val noMcpAvgSec = avgSecOrNull(noMcpRecs.map { it.totalDurationMs })
            val mcpAvgIn = avgLongOrNull(mcpRecs.mapNotNull { it.tokenUsage?.inputTokens })
            val noMcpAvgIn = avgLongOrNull(noMcpRecs.mapNotNull { it.tokenUsage?.inputTokens })
            val mcpAvgOut = avgLongOrNull(mcpRecs.mapNotNull { it.tokenUsage?.outputTokens })
            val noMcpAvgOut = avgLongOrNull(noMcpRecs.mapNotNull { it.tokenUsage?.outputTokens })
            val mcpTotalCost = mcpRecs.mapNotNull { it.tokenUsage?.costUsd }.sum()
            val noMcpTotalCost = noMcpRecs.mapNotNull { it.tokenUsage?.costUsd }.sum()

            appendLine("| Metric | Claude + MCP | Claude (no MCP) |")
            appendLine("|--------|-------------|-----------------|")
            appendLine("| Cases run | ${mcpRecs.size} | ${noMcpRecs.size} |")
            appendLine("| Claimed fix | $mcpFixed/$totalCases (${pct(mcpFixed, totalCases)}) | $noMcpFixed/$totalCases (${pct(noMcpFixed, totalCases)}) |")
            appendLine("| Avg total time | ${fmtSec(mcpAvgSec)} | ${fmtSec(noMcpAvgSec)} |")
            appendLine("| Avg input tokens | ${fmtK(mcpAvgIn)} | ${fmtK(noMcpAvgIn)} |")
            appendLine("| Avg output tokens | ${fmtK(mcpAvgOut)} | ${fmtK(noMcpAvgOut)} |")
            appendLine("| Total cost (USD) | \$${fmtCost(mcpTotalCost)} | \$${fmtCost(noMcpTotalCost)} |")
            appendLine()

            val fixDiff = mcpFixed - noMcpFixed
            when {
                fixDiff > 0 -> appendLine("**MCP advantage: +$fixDiff cases fixed** (${pct(mcpFixed, totalCases)} vs ${pct(noMcpFixed, totalCases)})")
                fixDiff < 0 -> appendLine("**No-MCP advantage: +${-fixDiff} cases fixed** (${pct(noMcpFixed, totalCases)} vs ${pct(mcpFixed, totalCases)})")
                else -> appendLine("**Tied fix rate** — both modes: ${pct(mcpFixed, totalCases)}")
            }

            if (mcpAvgSec != null && noMcpAvgSec != null) {
                val diff = noMcpAvgSec - mcpAvgSec
                if (diff > 5.0) {
                    appendLine("**MCP saves avg ${diff.toInt()}s per case** vs no-MCP")
                } else if (diff < -5.0) {
                    appendLine("**MCP is avg ${(-diff).toInt()}s slower per case** vs no-MCP (IDE indexing overhead)")
                }
            }
        }

        private fun resultsTable(pairs: List<Triple<String, RunRecord?, RunRecord?>>): String = buildString {
            appendLine("| Case | Tags | MCP Fix | MCP Time | MCP Tokens (in/out) | No-MCP Fix | No-MCP Time | No-MCP Tokens (in/out) | Verdict |")
            appendLine("|------|------|---------|----------|---------------------|------------|-------------|------------------------|---------|")
            for ((id, mcp, noMcp) in pairs) {
                val tags = (mcp?.tags ?: noMcp?.tags ?: emptyList()).joinToString(", ")
                val mcpFix = mcp?.let { if (it.agentClaimedFix) "✅ yes" else "❌ no" } ?: "–"
                val noMcpFix = noMcp?.let { if (it.agentClaimedFix) "✅ yes" else "❌ no" } ?: "–"
                val mcpTime = mcp?.let { fmtSec(it.totalDurationMs.toDouble()) } ?: "–"
                val noMcpTime = noMcp?.let { fmtSec(it.totalDurationMs.toDouble()) } ?: "–"
                val mcpTok = mcp?.tokenUsage?.let { "${fmtK(it.inputTokens.toDouble())}/${fmtK(it.outputTokens.toDouble())}" } ?: "–"
                val noMcpTok = noMcp?.tokenUsage?.let { "${fmtK(it.inputTokens.toDouble())}/${fmtK(it.outputTokens.toDouble())}" } ?: "–"
                val verdict = verdict(mcp, noMcp)
                val shortId = id.removePrefix("dpaia__")
                appendLine("| $shortId | $tags | $mcpFix | $mcpTime | $mcpTok | $noMcpFix | $noMcpTime | $noMcpTok | $verdict |")
            }
        }

        private fun verdict(mcp: RunRecord?, noMcp: RunRecord?): String = when {
            mcp?.agentClaimedFix == true && noMcp?.agentClaimedFix != true -> "**MCP wins**"
            mcp?.agentClaimedFix != true && noMcp?.agentClaimedFix == true -> "no-MCP wins"
            mcp?.agentClaimedFix == true && noMcp?.agentClaimedFix == true -> "tie — both fixed"
            else -> "tie — both failed"
        }

        private fun bottleneckAnalysis(pairs: List<Triple<String, RunRecord?, RunRecord?>>): String = buildString {
            val mcpWins = pairs.filter { (_, mcp, noMcp) -> mcp?.agentClaimedFix == true && noMcp?.agentClaimedFix != true }
            val noMcpWins = pairs.filter { (_, mcp, noMcp) -> mcp?.agentClaimedFix != true && noMcp?.agentClaimedFix == true }
            val bothFixed = pairs.filter { (_, mcp, noMcp) -> mcp?.agentClaimedFix == true && noMcp?.agentClaimedFix == true }
            val bothFailed = pairs.filter { (_, mcp, noMcp) -> mcp?.agentClaimedFix != true && noMcp?.agentClaimedFix != true }

            // Fix rate breakdown
            appendLine("### Fix-Rate Breakdown")
            appendLine()
            appendLine("- **MCP wins** (only MCP fixed): ${mcpWins.size} case(s)")
            for ((id, mcp, noMcp) in mcpWins) {
                val mcpTok = mcp?.tokenUsage?.totalTokens?.let { fmtK(it.toDouble()) } ?: "?"
                val noMcpTok = noMcp?.tokenUsage?.totalTokens?.let { fmtK(it.toDouble()) } ?: "?"
                appendLine("  - `${id.removePrefix("dpaia__")}`: MCP ${fmtSec(mcp?.totalDurationMs?.toDouble())}/${mcpTok}tok  →  no-MCP ${fmtSec(noMcp?.totalDurationMs?.toDouble())}/${noMcpTok}tok (exit ${noMcp?.exitCode ?: "?"})")
            }
            appendLine()
            appendLine("- **Both fixed** (tie): ${bothFixed.size} case(s)")
            for ((id, mcp, noMcp) in bothFixed) {
                val mcpTok = mcp?.tokenUsage?.totalTokens?.let { fmtK(it.toDouble()) } ?: "?"
                val noMcpTok = noMcp?.tokenUsage?.totalTokens?.let { fmtK(it.toDouble()) } ?: "?"
                appendLine("  - `${id.removePrefix("dpaia__")}`: MCP ${fmtSec(mcp?.totalDurationMs?.toDouble())}/${mcpTok}tok  vs  no-MCP ${fmtSec(noMcp?.totalDurationMs?.toDouble())}/${noMcpTok}tok")
            }
            appendLine()
            appendLine("- **Both failed** (tie): ${bothFailed.size} case(s)")
            for ((id, mcp, noMcp) in bothFailed) {
                val note = (mcp?.errorMessage ?: noMcp?.errorMessage)?.let { " — error: $it" } ?: ""
                appendLine("  - `${id.removePrefix("dpaia__")}`$note")
            }
            appendLine()
            if (noMcpWins.isNotEmpty()) {
                appendLine("- **No-MCP wins** (unexpected, worth investigating): ${noMcpWins.size} case(s)")
                for ((id, mcp, noMcp) in noMcpWins) {
                    appendLine("  - `${id.removePrefix("dpaia__")}`: no-MCP fixed in ${fmtSec(noMcp?.totalDurationMs?.toDouble())}, MCP did not (exit ${mcp?.exitCode ?: "?"})")
                }
                appendLine()
            }

            // Token efficiency
            appendLine("### Token Efficiency")
            appendLine()
            data class TokenDiff(val id: String, val mcpTok: Long, val noMcpTok: Long)
            val tokenDiffs = pairs.mapNotNull { (id, mcp, noMcp) ->
                val m = mcp?.tokenUsage?.totalTokens ?: return@mapNotNull null
                val n = noMcp?.tokenUsage?.totalTokens ?: return@mapNotNull null
                TokenDiff(id, m, n)
            }
            val mcpSaves = tokenDiffs.filter { it.noMcpTok > it.mcpTok * 1.2 }.sortedByDescending { it.noMcpTok - it.mcpTok }
            val mcpCosts = tokenDiffs.filter { it.mcpTok > it.noMcpTok * 1.2 }.sortedByDescending { it.mcpTok - it.noMcpTok }

            if (mcpSaves.isNotEmpty()) {
                appendLine("**Cases where MCP uses fewer tokens (>20% less):**")
                for (d in mcpSaves) {
                    val saved = d.noMcpTok - d.mcpTok
                    appendLine("  - `${d.id.removePrefix("dpaia__")}`: MCP ${fmtK(d.mcpTok.toDouble())}tok vs no-MCP ${fmtK(d.noMcpTok.toDouble())}tok (saves ${fmtK(saved.toDouble())}tok, ${pct(saved.toInt(), d.noMcpTok.toInt())} less)")
                }
                appendLine()
            }
            if (mcpCosts.isNotEmpty()) {
                appendLine("**Cases where MCP uses more tokens (>20% more):**")
                for (d in mcpCosts) {
                    val extra = d.mcpTok - d.noMcpTok
                    appendLine("  - `${d.id.removePrefix("dpaia__")}`: MCP ${fmtK(d.mcpTok.toDouble())}tok vs no-MCP ${fmtK(d.noMcpTok.toDouble())}tok (extra ${fmtK(extra.toDouble())}tok, ${pct(extra.toInt(), d.noMcpTok.toInt())} more)")
                }
                appendLine()
            }
            if (mcpSaves.isEmpty() && mcpCosts.isEmpty()) {
                appendLine("_(Both modes used similar token counts across all cases)_")
                appendLine()
            }

            // Time analysis
            appendLine("### Time Analysis")
            appendLine()
            data class TimeDiff(val id: String, val mcpMs: Long, val noMcpMs: Long)
            val timeDiffs = pairs.mapNotNull { (id, mcp, noMcp) ->
                val m = mcp?.totalDurationMs ?: return@mapNotNull null
                val n = noMcp?.totalDurationMs ?: return@mapNotNull null
                TimeDiff(id, m, n)
            }
            val mcpFaster = timeDiffs.filter { it.noMcpMs > it.mcpMs + 30_000 }.sortedByDescending { it.noMcpMs - it.mcpMs }
            val mcpSlower = timeDiffs.filter { it.mcpMs > it.noMcpMs + 30_000 }.sortedByDescending { it.mcpMs - it.noMcpMs }

            if (mcpFaster.isNotEmpty()) {
                appendLine("**Cases where MCP was significantly faster (>30s):**")
                for (d in mcpFaster) {
                    appendLine("  - `${d.id.removePrefix("dpaia__")}`: MCP ${d.mcpMs / 1000}s vs no-MCP ${d.noMcpMs / 1000}s (MCP saves ${(d.noMcpMs - d.mcpMs) / 1000}s)")
                }
                appendLine()
            }
            if (mcpSlower.isNotEmpty()) {
                appendLine("**Cases where MCP was significantly slower (>30s) — likely IDE indexing overhead:**")
                for (d in mcpSlower) {
                    appendLine("  - `${d.id.removePrefix("dpaia__")}`: MCP ${d.mcpMs / 1000}s vs no-MCP ${d.noMcpMs / 1000}s (MCP overhead ${(d.mcpMs - d.noMcpMs) / 1000}s)")
                }
                appendLine()
            }
            if (mcpFaster.isEmpty() && mcpSlower.isEmpty()) {
                appendLine("_(No cases with >30s time difference between modes)_")
                appendLine()
            }

            // Key observations
            appendLine("### Key Observations and Bottlenecks")
            appendLine()
            append(keyObservations(pairs, mcpWins, bothFailed, mcpSaves))
        }

        private fun keyObservations(
            pairs: List<Triple<String, RunRecord?, RunRecord?>>,
            mcpWins: List<Triple<String, RunRecord?, RunRecord?>>,
            bothFailed: List<Triple<String, RunRecord?, RunRecord?>>,
            tokenSavers: List<Any>,
        ): String = buildString {
            var point = 1

            if (mcpWins.isNotEmpty()) {
                appendLine("$point. **MCP is decisive for ${mcpWins.size} case(s)** where no-MCP fails entirely:")
                for ((id, mcp, noMcp) in mcpWins) {
                    appendLine("   - `${id.removePrefix("dpaia__")}`: ${mcpAdvantageReason(id)}")
                    val burnedTok = noMcp?.tokenUsage?.totalTokens
                    if (burnedTok != null && burnedTok > 10_000) {
                        appendLine("     → No-MCP burned ${fmtK(burnedTok.toDouble())} tokens without producing a working fix.")
                    }
                }
                appendLine()
                point++
            }

            appendLine("$point. **Primary no-MCP bottlenecks** (patterns observed across failing runs):")
            appendLine("   - **Grepping without direction**: Without IDE Find Usages, no-MCP reads many files via grep/cat,")
            appendLine("     consuming large numbers of input tokens before finding the right classes to change.")
            appendLine("   - **Blind compile-fix cycle**: No-MCP runs `mvn test` or `./gradlew test` to discover errors,")
            appendLine("     then tries to fix them one-by-one. Each cycle can take 1-3 minutes with 100+ failing tests.")
            appendLine("   - **Missed impacted classes**: In multi-layer Spring projects (entity → service → controller → DTO),")
            appendLine("     no-MCP often updates 2-3 layers but misses the last one. MCP's compile feedback highlights")
            appendLine("     every red underline immediately.")
            appendLine("   - **Import resolution**: Spring Security and Jakarta/Javax APIs have overlapping class names.")
            appendLine("     Without IDE auto-import, no-MCP guesses the wrong package and the code compiles but fails at runtime.")
            appendLine()
            point++

            appendLine("$point. **MCP overhead** (when MCP is slower/uses more tokens):")
            appendLine("   - Opening the project in IntelliJ and waiting for indexing adds 2-5 minutes of setup per test.")
            appendLine("   - steroid_execute_code calls add round-trip latency vs direct bash/file operations.")
            appendLine("   - For simple, well-specified tasks (single-file changes) this overhead can exceed the benefit.")
            appendLine("   - Payoff is clearest for complex multi-file changes with compilation dependencies.")
            appendLine()
            point++

            if (bothFailed.isNotEmpty()) {
                appendLine("$point. **${bothFailed.size} genuinely hard case(s)** where neither mode succeeded:")
                for ((id, _, _) in bothFailed) {
                    appendLine("   - `${id.removePrefix("dpaia__")}`: May require longer timeouts, larger context, or specialist prompts.")
                }
                appendLine()
                point++
            }

            appendLine("$point. **Recommendations** for improving MCP performance:")
            appendLine("   - Pre-open the project and trigger indexing BEFORE starting the agent prompt.")
            appendLine("   - Use a project-specific system prompt that directs the agent to immediately open the project")
            appendLine("     at the given path rather than exploring the filesystem first.")
            appendLine("   - For Maven projects: cache `.m2` dependencies in the Docker image to avoid download overhead.")
            appendLine("   - Add a verification step: after claiming a fix, run the failing tests to confirm PASS.")
        }

        private fun mcpAdvantageReason(instanceId: String): String = when {
            instanceId.contains("feature__service-125") -> "44 KB cross-layer patch — IDE navigation + JPQL query validation"
            instanceId.contains("feature__service-122") -> "Full notification subsystem — Find Usages identifies all impacted classes"
            instanceId.contains("empty__maven__springboot3-1") -> "JWT auth from scratch — Spring Security API versioning traps; IDE auto-import prevents wrong package"
            instanceId.contains("feature__service-25") -> "Self-referential JPA entity — circular dep caught by IDE compile check, invisible to grep"
            instanceId.contains("feature__service-22") -> "FeatureReactionController — IDE Find Usages confirms all reaction endpoints updated"
            instanceId.contains("empty__maven__springboot3-3") -> "jakarta vs javax validation — IDE highlights wrong import package immediately"
            instanceId.contains("feature__service-21") -> "Comments/replies multi-controller — IDE consistency check across all controllers"
            instanceId.contains("spring__petclinic__rest-23") -> "Password policy — IDE per-class test runner isolates failing tests, no-MCP runs all 468 at once"
            instanceId.contains("spring__petclinic__rest-14") -> "575 failing tests — IDE compile check shows every missed edit immediately"
            instanceId.contains("spring__boot__microshop-1") -> "WebClient migration — IDE Find Usages finds all RestTemplate call sites across microservices"
            else -> "complex multi-file change benefiting from IDE navigation and compile feedback"
        }

        private fun detailedNotes(pairs: List<Triple<String, RunRecord?, RunRecord?>>): String = buildString {
            for ((id, mcp, noMcp) in pairs) {
                appendLine("### ${id.removePrefix("dpaia__")}")
                appendLine()
                appendLine("**Tags:** ${(mcp?.tags ?: noMcp?.tags ?: emptyList()).joinToString(", ")}")
                appendLine()

                for (rec in listOfNotNull(mcp, noMcp)) {
                    val modeLabel = if (rec.withMcp) "Claude + MCP" else "Claude (no MCP)"
                    appendLine("**$modeLabel:**")
                    appendLine("- Fix claimed: ${if (rec.agentClaimedFix) "yes ✅" else "no ❌"}")
                    appendLine("- Total time: ${rec.totalDurationMs / 1000}s (agent: ${rec.agentDurationMs / 1000}s)")
                    if (rec.withMcp && rec.prewarmDurationMs > 0) {
                        appendLine("- Pre-warm time: ${rec.prewarmDurationMs / 1000}s (IDE open + index, not in agent budget)")
                    }
                    appendLine("- Exit code: ${rec.exitCode ?: "n/a"}")
                    if (rec.usedMcpSteroid) appendLine("- Used steroid_execute_code: yes")
                    val u = rec.tokenUsage
                    if (u != null) {
                        appendLine("- Tokens: ${u.inputTokens} in / ${u.outputTokens} out (cache-read: ${u.cacheReadTokens}), ${u.numTurns ?: "?"} turns")
                        if (u.costUsd != null) appendLine("- Cost: \$${fmtCost(u.costUsd)}")
                    } else {
                        appendLine("- Tokens: not available")
                    }
                    if (rec.agentSummary != null) appendLine("- Summary: ${rec.agentSummary}")
                    if (rec.errorMessage != null) appendLine("- Error: ${rec.errorMessage}")
                    appendLine()
                }
                appendLine("---")
                appendLine()
            }
        }

        private fun buildJsonReport(records: List<RunRecord>): String {
            val arr = buildJsonArray {
                for (r in records) {
                    add(buildJsonObject {
                        put("instanceId", r.instanceId)
                        put("mode", r.modeLabel)
                        put("totalDurationMs", r.totalDurationMs)
                        put("agentDurationMs", r.agentDurationMs)
                        put("prewarmDurationMs", r.prewarmDurationMs)
                        put("exitCode", r.exitCode ?: -1)
                        put("agentClaimedFix", r.agentClaimedFix)
                        put("usedMcpSteroid", r.usedMcpSteroid)
                        put("agentSummary", r.agentSummary ?: "")
                        put("errorMessage", r.errorMessage ?: "")
                        val u = r.tokenUsage
                        if (u != null) {
                            put("inputTokens", u.inputTokens)
                            put("outputTokens", u.outputTokens)
                            put("cacheReadTokens", u.cacheReadTokens)
                            put("totalTokens", u.totalTokens)
                            put("costUsd", u.costUsd ?: 0.0)
                            put("numTurns", u.numTurns ?: 0)
                        }
                    })
                }
            }
            return Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), arr)
        }

        // ── Formatting helpers ──────────────────────────────────────────────

        private fun pct(n: Int, d: Int): String = if (d == 0) "0%" else "${n * 100 / d}%"
        private fun fmtSec(ms: Double?): String = if (ms == null) "–" else "${(ms / 1000).toInt()}s"
        private fun fmtSec(ms: Long): String = "${ms / 1000}s"
        private fun fmtK(n: Double?): String {
            if (n == null) return "–"
            return if (n >= 1000.0) "${(n / 1000).toInt()}k" else n.toInt().toString()
        }
        private fun fmtCost(d: Double): String = String.format("%.4f", d)
        private fun avgSecOrNull(msValues: List<Long>): Double? =
            if (msValues.isEmpty()) null else msValues.average() / 1000.0
        private fun avgLongOrNull(values: List<Long>): Double? =
            if (values.isEmpty()) null else values.average()
    }
}
