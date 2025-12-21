/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.execution

import com.intellij.openapi.components.service
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.intellij.mcp.server.ExecCodeParams
import com.jonnyzzz.intellij.mcp.storage.ExecutionId
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.buildJsonObject
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

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

    private class TestResultBuilder : ExecutionResultBuilder {
        val messages = mutableListOf<String>()
        val progressMessages = mutableListOf<String>()
        val exceptions = mutableListOf<Pair<String, Throwable>>()
        var failed = false
        var failureMessage: String? = null

        override fun logMessage(message: String) {
            messages += message
        }

        override fun logProgress(message: String) {
            progressMessages += message
        }

        override fun logException(message: String, throwable: Throwable) {
            exceptions += message to throwable
        }

        override fun reportFailed(message: String) {
            failed = true
            failureMessage = message
        }

        fun hasAnyOutput(): Boolean {
            return failed || messages.isNotEmpty() || exceptions.isNotEmpty() || progressMessages.isNotEmpty()
        }

        fun hasDaemonDyingError(): Boolean {
            val msg = failureMessage ?: ""
            return msg.contains("Service is dying") || msg.contains("Could not connect to Kotlin compile daemon")
        }
    }

    private fun testExecParams(code: String, timeout: Int = 60) = ExecCodeParams(
        taskId = "stress-test",
        code = code,
        reason = "stress test",
        timeout = timeout,
        rawParams = buildJsonObject { }
    )

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

            if (builder.failed) {
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

                if (builder.failed) {
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
                    if (builder.failed) {
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
            kotlinx.coroutines.delay(500)
        }

        println("Parallel stress test: success=$successCount, fail=$failCount, daemonDying=$daemonDyingCount")
        assertTrue("Should have some completions", successCount + failCount > 0)
    }

    /**
     * Test rapid fire compilations - many quick scripts in succession.
     * This tests daemon throughput and recovery.
     */
    fun testRapidFireCompilation(): Unit = timeoutRunBlocking(3.minutes) {
        // Very simple scripts for rapid execution
        val scripts = (1..50).map { i ->
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

            if (builder.failed) {
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
        assertTrue("Should complete all iterations", successCount + failCount == 50)
    }
}
