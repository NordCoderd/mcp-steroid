/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.docker

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.testHelper.ProcessRunner
import com.jonnyzzz.mcpSteroid.testHelper.createTempDirectory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Test for Ryuk-based Docker cleanup mechanism.
 *
 * This test verifies that:
 * 1. Containers are labeled with session information
 * 2. Normal cleanup via CloseableStack works
 * 3. Ryuk reaper starts and connects properly
 */
class RyukReaperTest {
    private lateinit var workDir: File
    private lateinit var driver: DockerDriver
    private val processRunner = ProcessRunner("TEST", emptyList())

    @BeforeEach
    fun setUp() {
        workDir = createTempDirectory("ryuk-reaper-test")
        driver = DockerDriver(
            workDir = workDir,
            logPrefix = "TEST",
            secretPatterns = emptyList(),
            environmentVariables = emptyMap()
        )
    }

    @AfterEach
    fun tearDown() {
        // Shutdown Ryuk if started
        try {
            RyukReaper.getInstance().shutdown()
        } catch (e: Exception) {
            // Ignore
        }
        workDir.deleteRecursively()
    }

    @Test
    fun `test container has session labels`() {
        val lifetime = CloseableStackHost()

        try {
            // Start a container with sleep to keep it running
            val containerId = driver.startContainer(
                lifetime = lifetime,
                imageName = "alpine:latest",
                extraEnvVars = emptyMap(),
                cmd = listOf("sleep", "infinity")
            )

            // Verify container has session labels
            val result = processRunner.run(
                listOf(
                    "docker", "inspect",
                    "--format", "{{index .Config.Labels \"${DockerSessionLabels.SESSION_ID_LABEL}\"}}",
                    containerId
                ),
                description = "Inspect session label",
                workingDir = workDir,
                timeoutSeconds = 5
            )

            assertEquals(0, result.exitCode, "Failed to inspect container: ${result.stderr}")
            assertEquals(
                DockerSessionLabels.SESSION_ID,
                result.output.trim(),
                "Session ID label mismatch"
            )

            // Verify PID label
            val pidResult = processRunner.run(
                listOf(
                    "docker", "inspect",
                    "--format", "{{index .Config.Labels \"${DockerSessionLabels.PROCESS_ID_LABEL}\"}}",
                    containerId
                ),
                description = "Inspect PID label",
                workingDir = workDir,
                timeoutSeconds = 5
            )

            assertEquals(0, pidResult.exitCode, "Failed to inspect PID: ${pidResult.stderr}")
            assertEquals(
                DockerSessionLabels.PROCESS_ID,
                pidResult.output.trim(),
                "PID label mismatch"
            )

            println("Container $containerId has correct session labels")
        } finally {
            lifetime.closeAllStacks()
        }
    }

    @Test
    fun `test container cleanup via CloseableStack`() {
        val lifetime = CloseableStackHost()

        val containerId = driver.startContainer(
            lifetime = lifetime,
            imageName = "alpine:latest",
            extraEnvVars = emptyMap(),
            cmd = listOf("sleep", "infinity")
        )

        // Verify container is running
        val beforeResult = processRunner.run(
            listOf("docker", "ps", "-q", "--filter", "id=$containerId"),
            description = "Check container before cleanup",
            workingDir = workDir,
            timeoutSeconds = 5
        )
        assertTrue(beforeResult.output.trim().isNotEmpty(), "Container should be running")

        // Close lifetime (normal cleanup)
        lifetime.closeAllStacks()

        // Verify container is gone
        val afterResult = processRunner.run(
            listOf("docker", "ps", "-aq", "--filter", "id=$containerId"),
            description = "Check container after cleanup",
            workingDir = workDir,
            timeoutSeconds = 5
        )
        assertTrue(afterResult.output.trim().isEmpty(), "Container should be removed")
        println("Container $containerId cleaned up successfully via CloseableStack")
    }

    @Test
    fun `test Ryuk reaper starts and registers session`() {
        val lifetime = CloseableStackHost()

        try {
            // Start a container (this should start Ryuk automatically)
            val containerId = driver.startContainer(
                lifetime = lifetime,
                imageName = "alpine:latest",
                extraEnvVars = emptyMap(),
                cmd = listOf("sleep", "infinity")
            )

            // Verify Ryuk container is running
            val ryukResult = processRunner.run(
                listOf(
                    "docker", "ps",
                    "--filter", "ancestor=testcontainers/ryuk:0.5.1",
                    "--format", "{{.ID}}"
                ),
                description = "Check Ryuk container",
                workingDir = workDir,
                timeoutSeconds = 5
            )

            assertTrue(ryukResult.exitCode == 0, "Failed to check Ryuk: ${ryukResult.stderr}")
            assertTrue(ryukResult.output.trim().isNotEmpty(), "Ryuk container should be running")

            println("Ryuk reaper is running: ${ryukResult.output.trim()}")
            println("Test container registered: $containerId")
        } finally {
            lifetime.closeAllStacks()
        }
    }
}
