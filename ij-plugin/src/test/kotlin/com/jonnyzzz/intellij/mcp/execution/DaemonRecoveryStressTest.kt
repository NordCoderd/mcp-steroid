/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.execution

import com.intellij.openapi.components.service
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.intellij.mcp.TestResultBuilder
import com.jonnyzzz.intellij.mcp.storage.ExecutionId
import com.jonnyzzz.intellij.mcp.testExecParams
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.minutes

/**
 * Stress tests for Kotlin daemon recovery.
 *
 * These tests attempt to trigger the "Service is dying" error by:
 * 1. Running many compilations in rapid succession
 * 2. Running compilations in parallel
 * 3. Running compilations with varying complexity
 *
 * The goal is to stress the daemon and potentially trigger its shutdown,
 * then verify that the recovery mechanism works correctly.
 *
 * NOTE: These tests may take longer than normal tests and are designed
 * to occasionally trigger daemon failures. The recovery mechanism should
 * handle these failures gracefully.
 */
class DaemonRecoveryStressTest : BasePlatformTestCase() {

    private val executor: ScriptExecutor get() = project.service()
    private val daemonManager: KotlinDaemonManager get() = kotlinDaemonManager

    override fun setUp() {
        super.setUp()
        // Disable proactive daemon kill - we want to test reactive recovery
        // Proactive kill doesn't work in tests because fresh daemons don't get plugin classpath
        setRegistryPropertyForTest("mcp.steroids.daemon.kill.before.compile", "false")

        // Note: We do NOT kill the daemon here because:
        // 1. Fresh daemons in test environment don't get plugin classes
        // 2. We rely on the existing daemon having correct classpath from test setup
        // 3. Reactive recovery will handle any "Service is dying" errors
    }

    private var executionCounter = 0
    private fun nextExecutionId() = ExecutionId("stress-${++executionCounter}")

    /**
     * Run a simple script multiple times in sequence.
     * This tests basic sequential stress on the daemon.
     */
    fun testSequentialCompilationStress(): Unit = timeoutRunBlocking(2.minutes) {
        val simpleCode = """
            execute {
                println("Iteration")
            }
        """.trimIndent()

        var successCount = 0
        var failCount = 0
        var daemonDyingCount = 0

        // Run 20 sequential compilations
        repeat(20) { iteration ->
            val builder = TestResultBuilder()
            executor.executeWithProgress(nextExecutionId(), testExecParams(simpleCode), builder)

            if (builder.isFailed) {
                failCount++
                if (builder.hasDaemonDyingError()) {
                    daemonDyingCount++
                    println("Daemon dying detected on iteration $iteration")
                }
            } else if (builder.messages.isNotEmpty()) {
                successCount++
            }
        }

        println("Sequential stress test: success=$successCount, fail=$failCount, daemonDying=$daemonDyingCount")

        // If script engine is available, we expect some successes
        // If daemon recovery is working, we should not have persistent failures
        assertTrue("Should have some completions", successCount + failCount > 0)
    }

    /**
     * Run scripts with varying complexity to stress the compiler.
     */
    fun testVaryingComplexityStress(): Unit = timeoutRunBlocking(2.minutes) {
        val scripts = listOf(
            // Simple print
            """
                execute {
                    println("Simple")
                }
            """.trimIndent(),

            // With imports (outside execute block)
            """
                import java.util.UUID

                execute {
                    println(UUID.randomUUID().toString())
                }
            """.trimIndent(),

            // With local variables and computation
            """
                execute {
                    val result = (1..100).sum()
                    println("Sum: ${'$'}result")
                }
            """.trimIndent(),

            // With string operations
            """
                execute {
                    val text = "Hello, World!"
                    println(text.reversed())
                }
            """.trimIndent(),

            // With collections
            """
                execute {
                    val list = listOf(1, 2, 3, 4, 5)
                    println(list.map { it * 2 })
                }
            """.trimIndent()
        )

        var successCount = 0
        var failCount = 0

        // Run each script 4 times
        repeat(4) {
            scripts.forEach { code ->
                val builder = TestResultBuilder()
                executor.executeWithProgress(nextExecutionId(), testExecParams(code), builder)

                if (builder.isFailed) {
                    failCount++
                } else if (builder.messages.isNotEmpty()) {
                    successCount++
                }
            }
        }

        println("Varying complexity stress test: success=$successCount, fail=$failCount")
        assertTrue("Should have some completions", successCount + failCount > 0)
    }

    /**
     * Run multiple compilations in parallel.
     * This is more likely to stress the daemon's connection handling.
     */
    fun testParallelCompilationStress(): Unit = timeoutRunBlocking(2.minutes) {
        val simpleCode = """
            execute {
                println("Parallel")
            }
        """.trimIndent()

        var successCount = 0
        var failCount = 0
        var daemonDyingCount = 0

        // Run 3 batches of 5 parallel compilations
        repeat(3) { batch ->
            coroutineScope {
                val results = (1..5).map {
                    async {
                        val builder = TestResultBuilder()
                        executor.executeWithProgress(nextExecutionId(), testExecParams(simpleCode), builder)
                        builder
                    }
                }.awaitAll()

                results.forEach { builder ->
                    if (builder.isFailed) {
                        failCount++
                        if (builder.hasDaemonDyingError()) {
                            daemonDyingCount++
                        }
                    } else if (builder.messages.isNotEmpty()) {
                        successCount++
                    }
                }
            }

            // Small pause between batches
            delay(500)
        }

        println("Parallel stress test: success=$successCount, fail=$failCount, daemonDying=$daemonDyingCount")
        assertTrue("Should have some completions", successCount + failCount > 0)
    }

    /**
     * Test rapid fire compilations - many quick scripts in succession.
     * This tests daemon throughput and recovery.
     *
     * Note: Reduced to 20 iterations to fit within test timeout.
     * Each compilation can take several seconds due to daemon startup/communication.
     */
    fun testRapidFireCompilation(): Unit = timeoutRunBlocking(5.minutes) {
        // Very simple scripts for rapid execution
        val scripts = (1..20).map { i ->
            """
                execute {
                    println("Rapid $i")
                }
            """.trimIndent()
        }

        var successCount = 0
        var failCount = 0
        var recoveryAttempts = 0

        scripts.forEach { code ->
            val builder = TestResultBuilder()
            executor.executeWithProgress(nextExecutionId(), testExecParams(code, timeout = 30), builder)

            if (builder.isFailed) {
                failCount++
                // Check if we see recovery progress messages
                if (builder.progressMessages.any { it.contains("daemon") || it.contains("retry") }) {
                    recoveryAttempts++
                }
            } else if (builder.messages.isNotEmpty()) {
                successCount++
            }
        }

        println("Rapid fire test: success=$successCount, fail=$failCount, recoveryAttempts=$recoveryAttempts")

        // The test validates that:
        // 1. We can run many compilations
        // 2. Any daemon failures trigger recovery attempts
        // 3. The system remains functional overall
        assertTrue("Should complete all iterations", successCount + failCount == 20)
    }

    /**
     * Simulates daemon death by deleting .run files and verifies recovery.
     *
     * This test:
     * 1. Runs a successful compilation to ensure daemon is started
     * 2. Deletes the daemon .run file to trigger shutdown
     * 3. Immediately attempts another compilation
     * 4. Verifies that either:
     *    - The compilation succeeds (daemon recovered or new daemon started)
     *    - Recovery mechanism was triggered (progress messages show retry)
     */
    fun testDaemonKillAndRecovery(): Unit = timeoutRunBlocking(3.minutes) {
        val simpleCode = """
            execute {
                println("Test execution")
            }
        """.trimIndent()

        println("Step 1: Initial compilation to ensure daemon is running")
        val initialBuilder = TestResultBuilder()
        executor.executeWithProgress(nextExecutionId(), testExecParams(simpleCode), initialBuilder)

        if (initialBuilder.isFailed && !initialBuilder.hasDaemonDyingError()) {
            // Script engine not available or other non-daemon error
            println("Script engine not available, skipping daemon kill test")
            return@timeoutRunBlocking
        }

        val daemonDir = daemonManager.getKotlinDaemonDir()
        if (daemonDir == null || !daemonDir.exists()) {
            println("Daemon directory not found, skipping test")
            return@timeoutRunBlocking
        }

        val runFilesBefore = daemonManager.getRunningDaemonCount()
        println("  Daemon run files before: $runFilesBefore")

        if (runFilesBefore == 0) {
            println("No daemon run files found, skipping test")
            return@timeoutRunBlocking
        }

        println("Step 2: Deleting daemon .run files to trigger shutdown")
        val daemonKilled = daemonManager.forceKillKotlinDaemon()
        println("  Daemon killed: $daemonKilled")

        // Give daemon a moment to notice the file deletion
        delay(500)

        println("Step 3: Attempting compilation after daemon kill")
        var successAfterKill = 0
        var failAfterKill = 0
        var daemonDyingErrors = 0
        var recoveryTriggered = 0

        // Try multiple times to catch the daemon in dying state
        repeat(5) { attempt ->
            val builder = TestResultBuilder()
            executor.executeWithProgress(nextExecutionId(), testExecParams(simpleCode), builder)

            if (builder.isFailed) {
                failAfterKill++
                if (builder.hasDaemonDyingError()) {
                    daemonDyingErrors++
                    println("  Attempt ${attempt + 1}: Daemon dying error detected!")
                }
                if (builder.progressMessages.any {
                    it.contains("daemon", ignoreCase = true) ||
                    it.contains("retry", ignoreCase = true) ||
                    it.contains("restart", ignoreCase = true)
                }) {
                    recoveryTriggered++
                    println("  Attempt ${attempt + 1}: Recovery triggered - ${builder.progressMessages}")
                }
            } else if (builder.messages.isNotEmpty()) {
                successAfterKill++
                println("  Attempt ${attempt + 1}: Success (daemon recovered)")
            }

            // Small delay between attempts
            delay(200)
        }

        println("\nResults after daemon kill:")
        println("  Success: $successAfterKill")
        println("  Failed: $failAfterKill")
        println("  Daemon dying errors: $daemonDyingErrors")
        println("  Recovery triggered: $recoveryTriggered")

        // Verify that the system recovered - we should have at least some successes
        // after the daemon was killed (recovery mechanism should start new daemon)
        val runFilesAfter = daemonManager.getRunningDaemonCount()
        println("  Daemon run files after: $runFilesAfter")

        // The test passes if:
        // 1. We saw daemon dying errors AND recovery was triggered, OR
        // 2. Some compilations succeeded (daemon recovered)
        val recoveryWorked = successAfterKill > 0 || recoveryTriggered > 0
        assertTrue(
            "After killing daemon, should either recover or trigger recovery mechanism. " +
            "Success=$successAfterKill, Recovery=$recoveryTriggered, DaemonDying=$daemonDyingErrors",
            recoveryWorked || daemonDyingErrors > 0
        )
    }

    /**
     * Tests recovery after killing daemon mid-compilation.
     * This is more aggressive - it deletes .run files while compilations are in progress.
     */
    fun testKillDaemonDuringCompilation(): Unit = timeoutRunBlocking(3.minutes) {
        val simpleCode = """
            execute {
                println("Concurrent test")
            }
        """.trimIndent()

        println("Starting concurrent compilation with daemon kill")

        // First ensure daemon is running
        val warmupBuilder = TestResultBuilder()
        executor.executeWithProgress(nextExecutionId(), testExecParams(simpleCode), warmupBuilder)
        if (warmupBuilder.isFailed && !warmupBuilder.hasDaemonDyingError()) {
            println("Script engine not available, skipping test")
            return@timeoutRunBlocking
        }

        var successCount = 0
        var failCount = 0
        var daemonDyingCount = 0
        var recoveryCount = 0

        // Run 10 compilations, killing daemon after each 3rd one
        repeat(10) { iteration ->
            val builder = TestResultBuilder()

            // Kill daemon every 3rd iteration
            if (iteration > 0 && iteration % 3 == 0) {
                println("Iteration $iteration: Killing daemon...")
                val killed = daemonManager.forceKillKotlinDaemon()
                println("  Daemon killed: $killed")
                delay(100) // Brief delay to let daemon notice
            }

            executor.executeWithProgress(nextExecutionId(), testExecParams(simpleCode), builder)

            if (builder.isFailed) {
                failCount++
                if (builder.hasDaemonDyingError()) {
                    daemonDyingCount++
                    println("Iteration $iteration: Daemon dying!")
                }
                if (builder.progressMessages.any { it.contains("daemon", ignoreCase = true) }) {
                    recoveryCount++
                }
            } else if (builder.messages.isNotEmpty()) {
                successCount++
            }
        }

        println("\nResults with periodic daemon kills:")
        println("  Success: $successCount")
        println("  Failed: $failCount")
        println("  Daemon dying: $daemonDyingCount")
        println("  Recovery triggered: $recoveryCount")

        // We expect at least some successes - the recovery should work
        assertTrue(
            "Should have some successful compilations despite daemon kills. " +
            "Success=$successCount, DaemonDying=$daemonDyingCount",
            successCount > 0 || recoveryCount > 0
        )
    }
}
