/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import com.intellij.openapi.components.service
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.mcpSteroid.TestResultBuilder
import com.jonnyzzz.mcpSteroid.storage.ExecutionId
import com.jonnyzzz.mcpSteroid.testExecParams
import org.junit.Assert
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
//This test was created for the embedded Kotlinc in IntelliJ, not needed anymore
abstract class DaemonRecoveryStressTest : BasePlatformTestCase() {

    private val executor: ScriptExecutor get() = project.service()
    private val daemonManager: KotlinDaemonManager get() = kotlinDaemonManager

    override fun setUp() {
        super.setUp()
        daemonManager.forceKillKotlinDaemon()
    }

    private var executionCounter = 0
    private fun nextExecutionId() = ExecutionId("stress-${++executionCounter}")

    fun testSequentialCompilationSimple(): Unit = timeoutRunBlocking(2.minutes) {
        val simpleCode = $$"""
            println("Iteration$${System.currentTimeMillis()}")
            val result = (1..100).sum()
            println("Sum: $result")
        """.trimIndent()

        // Run 20 sequential compilations
        repeat(20) {
            val builder = TestResultBuilder()
            executor.executeWithProgress(nextExecutionId(), testExecParams(simpleCode), builder)
            println(builder.toString())

            Assert.assertFalse("Builder must not fail", builder.isFailed)
        }
    }

    fun testSequentialCompilationWithImport(): Unit = timeoutRunBlocking(2.minutes) {
        val simpleCode = """
            ; import java.util.UUID;

            println(java.util.UUID.randomUUID().toString())
            println(UUID.randomUUID().toString())
            println("Iteration${System.currentTimeMillis()}")
        """.trimIndent()

        // Run 20 sequential compilations
        repeat(20) {
            val builder = TestResultBuilder()
            executor.executeWithProgress(nextExecutionId(), testExecParams(simpleCode), builder)
            println(builder.toString())

            Assert.assertFalse("Builder must not fail", builder.isFailed)
        }
    }
}
