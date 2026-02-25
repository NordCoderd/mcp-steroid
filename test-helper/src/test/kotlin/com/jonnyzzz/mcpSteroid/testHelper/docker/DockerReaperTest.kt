/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.docker

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessRunner
import com.jonnyzzz.mcpSteroid.testHelper.process.RunProcessRequest
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.process.startProcess
import com.jonnyzzz.mcpSteroid.testHelper.runWithCloseableStack
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Test for custom Docker reaper cleanup mechanism.
 *
 * This test verifies that:
 * 1. Normal cleanup via CloseableStack works
 * 2. Custom reaper starts and connects properly
 *
 * This test is disabled to avoid affecting the DockerReaper activity for the project
 */
class DockerReaperTest {
    private val processRunner = ProcessRunner("TEST", emptyList())

    @BeforeEach
    fun setUp() {
        //we try our best to give the reaper chance to kill all registered containers, if any
        try {
            DockerReaper.shutdown()
        } catch (_: Exception) {
        }
    }

    @Test
    fun `test container cleanup via CloseableStack`() {
        val lifetime = CloseableStackHost()

        DockerReaper.start()

        val container = startDockerContainerAndDispose(
            lifetime,
            StartContainerRequest()
                .image("alpine:latest")
                .logPrefix("test-image")
                .entryPoint("sleep", "infinity")
        )

        // Verify container is running
        val beforeResult = RunProcessRequest()
                .command("docker", "ps", "-q", "--filter", "id=${container.containerId}")
                .description("Check container before cleanup")
                .timeoutSeconds(5)
                .quietly()
                .startProcess(processRunner)
                .awaitForProcessFinish()

        assertTrue(beforeResult.stdout.trim().isNotEmpty(), "Container should be running")

        // Close lifetime (normal cleanup)
        lifetime.closeAllStacks()

        // Verify container is no longer running (-q without -a: running containers only)
        val afterResult = RunProcessRequest()
            .command("docker", "ps", "-q", "--filter", "id=${container.containerId}")
            .description("Check container after cleanup")
            .timeoutSeconds(5)
            .quietly()
            .startProcess(processRunner)
            .awaitForProcessFinish()

        assertTrue(afterResult.stdout.trim().isEmpty(), "Container should no longer be running")
        println("Container ${container.containerId} cleaned up successfully via CloseableStack")
    }

    @Test
    fun `test reaper starts and registers session`() {
        runWithCloseableStack { lifetime ->
            // Start reaper and a test container
            DockerReaper.start()
            val reaperId = DockerReaper.reaperContainerId
                ?: error("Reaper container ID not set after start()")

            val container = startDockerContainerAndDispose(
                lifetime = lifetime,
                StartContainerRequest()
                    .image("alpine:latest")
                    .logPrefix("test-image")
                    .entryPoint("sleep", "infinity")
            )

            // Verify reaper container is running (filter by container ID, not image tag —
            // the reaper image is built from a sha256 hash with no named tag)
            val reaperResult = RunProcessRequest()
                .command(
                    "docker", "ps",
                    "--filter", "id=$reaperId",
                    "--format", "{{.ID}}"
                )
                .description("Check reaper container")
                .timeoutSeconds(5)
                .quietly()
                .startProcess(processRunner)
                .assertExitCode(0) { "Failed to check reaper: $stderr" }

            assertTrue(reaperResult.stdout.trim().isNotEmpty(), "Reaper container should be running")

            println("Reaper is running: ${reaperResult.stdout.trim()}")
            println("Test container registered: ${container.containerId}")
        }
    }
}
