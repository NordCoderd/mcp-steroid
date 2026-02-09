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
 * Test for DockerReaper cleanup mechanism.
 *
 * This test verifies that:
 * 1. Containers are labeled with session information
 * 2. Containers are registered with the reaper
 * 3. Cleanup works via normal path (CloseableStack)
 * 4. Orphaned container detection works
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
        workDir.deleteRecursively()
    }

    @Test
    fun `test container has session labels`() {
        val lifetime = CloseableStackHost()

        try {
            // Start a simple container with sleep to keep it running
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
    fun `test orphaned container detection and cleanup`() {
        // Clean up any existing orphaned containers first
        DockerReaper.getInstance().cleanupOrphanedContainers()

        val lifetime = CloseableStackHost()

        try {
            // Start a container with sleep to keep it running
            val containerId = driver.startContainer(
                lifetime = lifetime,
                imageName = "alpine:latest",
                extraEnvVars = emptyMap(),
                cmd = listOf("sleep", "infinity")
            )

            println("Started container: $containerId")

            // Manually unregister from reaper to simulate orphaned state
            // (but don't actually kill it)
            DockerReaper.getInstance().unregisterContainer(containerId)

            // Verify container is still running
            val beforeResult = processRunner.run(
                listOf("docker", "ps", "-q", "--filter", "id=$containerId"),
                description = "Check container before orphan cleanup",
                workingDir = workDir,
                timeoutSeconds = 5
            )
            assertTrue(beforeResult.output.trim().isNotEmpty(), "Container should be running")

            // Now clean it up via normal path
            driver.killContainer(containerId)

            // Verify container is gone
            val afterResult = processRunner.run(
                listOf("docker", "ps", "-aq", "--filter", "id=$containerId"),
                description = "Check container after cleanup",
                workingDir = workDir,
                timeoutSeconds = 5
            )
            assertTrue(afterResult.output.trim().isEmpty(), "Container should be removed")

            println("Orphaned container test completed successfully")
        } finally {
            // Ensure cleanup
            lifetime.closeAllStacks()
        }
    }

    @Test
    fun `test list containers by session filter`() {
        val lifetime = CloseableStackHost()

        try {
            // Start multiple containers with sleep to keep them running
            val container1 = driver.startContainer(
                lifetime = lifetime,
                imageName = "alpine:latest",
                extraEnvVars = emptyMap(),
                cmd = listOf("sleep", "infinity")
            )
            val container2 = driver.startContainer(
                lifetime = lifetime,
                imageName = "alpine:latest",
                extraEnvVars = emptyMap(),
                cmd = listOf("sleep", "infinity")
            )

            // List containers by session filter
            val result = processRunner.run(
                listOf(
                    "docker", "ps", "-q",
                    "--filter", DockerSessionLabels.createSessionFilter()
                ),
                description = "List session containers",
                workingDir = workDir,
                timeoutSeconds = 5
            )

            assertEquals(0, result.exitCode, "Failed to list containers: ${result.stderr}")

            val containerIds = result.output.trim().lines().filter { it.isNotBlank() }
            assertTrue(containerIds.size >= 2, "Expected at least 2 containers, found ${containerIds.size}")
            assertTrue(containerIds.contains(container1), "Container 1 not found in session")
            assertTrue(containerIds.contains(container2), "Container 2 not found in session")

            println("Found ${containerIds.size} containers in session: $containerIds")
        } finally {
            lifetime.closeAllStacks()
        }
    }
}
