/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.docker

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessRunRequest
import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessRunner
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.createTempDirectory
import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessResult
import com.jonnyzzz.mcpSteroid.testHelper.process.builder
import com.jonnyzzz.mcpSteroid.testHelper.process.runProcess
import com.jonnyzzz.mcpSteroid.testHelper.process.startProcess
import com.jonnyzzz.mcpSteroid.testHelper.runWithCloseableStack
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Test for custom Docker reaper cleanup mechanism.
 *
 * This test verifies that:
 * 1. Normal cleanup via CloseableStack works
 * 2. Custom reaper starts and connects properly
 */
class DockerReaperTest {
    private lateinit var workDir: File
    private lateinit var driver: DockerDriver
    private val processRunner = ProcessRunner("TEST", emptyList())

    @BeforeEach
    fun setUp() {
        workDir = createTempDirectory("docker-reaper-test")
        driver = DockerDriver(
            workDir = workDir,
            logPrefix = "TEST",
            secretPatterns = emptyList(),
            environmentVariables = emptyMap()
        )
    }

    @AfterEach
    fun tearDown() {
        try {
            DockerReaper.shutdown()
        } catch (_: Exception) {
        }
        workDir.deleteRecursively()
    }

    @Test
    fun `test container cleanup via CloseableStack`() {
        val lifetime = CloseableStackHost()

        DockerReaper.start(workDir)

        val containerId = driver.startContainer(
            lifetime = lifetime,
            imageName = "alpine:latest",
            extraEnvVars = emptyMap(),
            cmd = listOf("sleep", "infinity")
        )

        DockerReaper.registerContainer(containerId, workDir)

        // Verify container is running
        val beforeResult = ProcessRunRequest.builder()
                .command("docker", "ps", "-q", "--filter", "id=$containerId")
                .description("Check container before cleanup")
                .workingDir(workDir)
                .timeoutSeconds(5)
                .quietly()
                .startProcess(processRunner)
                .awaitForProcessFinish()

        assertTrue(beforeResult.stdout.trim().isNotEmpty(), "Container should be running")

        // Close lifetime (normal cleanup)
        lifetime.closeAllStacks()

        // Verify container is gone
        val afterResult = ProcessRunRequest.builder()
            .command("docker", "ps", "-aq", "--filter", "id=$containerId")
            .description("Check container after cleanup")
            .workingDir(workDir)
            .timeoutSeconds(5)
            .quietly()
            .startProcess(processRunner)
            .awaitForProcessFinish()

        assertTrue(afterResult.stdout.trim().isEmpty(), "Container should be removed")
        println("Container $containerId cleaned up successfully via CloseableStack")
    }

    @Test
    fun `test reaper starts and registers session`() {
        runWithCloseableStack { lifetime ->
            // Start reaper and a test container
            DockerReaper.start(workDir)

            val containerId = driver.startContainer(
                lifetime = lifetime,
                imageName = "alpine:latest",
                extraEnvVars = emptyMap(),
                cmd = listOf("sleep", "infinity")
            )

            DockerReaper.registerContainer(containerId, workDir)

            // Verify reaper container is running
            val reaperResult = ProcessRunRequest.builder()
                .command(
                    "docker", "ps",
                    "--filter", "ancestor=mcp-steroid-reaper",
                    "--format", "{{.ID}}"
                )
                .description("Check reaper container")
                .workingDir(workDir)
                .timeoutSeconds(5)
                .quietly()
                .startProcess(processRunner)
                .assertExitCode(0) { "Failed to check reaper: $stderr" }

            assertTrue(reaperResult.stdout.trim().isNotEmpty(), "Reaper container should be running")

            println("Reaper is running: ${reaperResult.stdout.trim()}")
            println("Test container registered: $containerId")
        }
    }
}
