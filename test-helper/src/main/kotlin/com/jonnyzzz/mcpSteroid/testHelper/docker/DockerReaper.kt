/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.docker

import com.jonnyzzz.mcpSteroid.testHelper.ProcessRunner
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Docker resource reaper that cleans up containers on JVM shutdown.
 * Similar to testcontainers' ResourceReaper, this ensures that containers
 * are cleaned up even if the test process crashes or is killed.
 *
 * Design:
 * - Singleton per JVM process (via companion object)
 * - Registers JVM shutdown hook on first use
 * - Tracks containers by ID in a thread-safe set
 * - Attempts cleanup via Docker CLI on shutdown
 *
 * This is a fallback mechanism - normal cleanup still happens via CloseableStack.
 */
class DockerReaper private constructor() {
    private val registeredContainers = ConcurrentHashMap.newKeySet<String>()
    private val shutdownHookRegistered = AtomicBoolean(false)
    private val processRunner = ProcessRunner("DOCKER-REAPER", emptyList())
    private val workingDir = File(System.getProperty("user.dir", "."))

    /**
     * Register a container for cleanup on shutdown.
     * This is called automatically by DockerDriver.
     */
    fun registerContainer(containerId: String) {
        registeredContainers.add(containerId)
        ensureShutdownHookRegistered()
    }

    /**
     * Unregister a container (called when it's cleaned up normally).
     */
    fun unregisterContainer(containerId: String) {
        registeredContainers.remove(containerId)
    }

    /**
     * Ensure the JVM shutdown hook is registered exactly once.
     */
    private fun ensureShutdownHookRegistered() {
        if (shutdownHookRegistered.compareAndSet(false, true)) {
            Runtime.getRuntime().addShutdownHook(Thread({
                performCleanup()
            }, "docker-reaper-shutdown-hook"))
            println("[DOCKER-REAPER] Shutdown hook registered for session: ${DockerSessionLabels.SESSION_ID}")
        }
    }

    /**
     * Perform cleanup of all registered containers.
     * Called by the JVM shutdown hook.
     */
    private fun performCleanup() {
        if (registeredContainers.isEmpty()) {
            println("[DOCKER-REAPER] No containers to clean up")
            return
        }

        println("[DOCKER-REAPER] Cleaning up ${registeredContainers.size} containers...")

        // Clean up all registered containers
        for (containerId in registeredContainers) {
            try {
                killContainer(containerId)
            } catch (e: Exception) {
                System.err.println("[DOCKER-REAPER] Failed to cleanup container $containerId: ${e.message}")
            }
        }

        // Also clean up any containers with our session label (belt and suspenders)
        try {
            cleanupSessionContainers()
        } catch (e: Exception) {
            System.err.println("[DOCKER-REAPER] Failed to cleanup session containers: ${e.message}")
        }

        println("[DOCKER-REAPER] Cleanup complete")
    }

    /**
     * Kill and remove a single container.
     */
    private fun killContainer(containerId: String) {
        // Kill the container
        processRunner.run(
            listOf("docker", "kill", containerId),
            description = "Kill container",
            workingDir = workingDir,
            timeoutSeconds = 10,
        )

        // Remove the container
        processRunner.run(
            listOf("docker", "rm", "-f", containerId),
            description = "Remove container",
            workingDir = workingDir,
            timeoutSeconds = 5,
        )

        println("[DOCKER-REAPER] Cleaned up container: $containerId")
    }

    /**
     * Clean up all containers from this session using Docker filter.
     */
    private fun cleanupSessionContainers() {
        // List all containers with our session label
        val result = processRunner.run(
            listOf(
                "docker", "ps", "-aq",
                "--filter", DockerSessionLabels.createSessionFilter()
            ),
            description = "List session containers",
            workingDir = workingDir,
            timeoutSeconds = 10,
        )

        if (result.exitCode != 0) {
            System.err.println("[DOCKER-REAPER] Failed to list containers: ${result.stderr}")
            return
        }

        val containerIds = result.output.trim().lines().filter { it.isNotBlank() }
        if (containerIds.isEmpty()) {
            println("[DOCKER-REAPER] No session containers found")
            return
        }

        println("[DOCKER-REAPER] Found ${containerIds.size} containers with session label")

        // Kill and remove all containers
        for (containerId in containerIds) {
            try {
                killContainer(containerId)
            } catch (e: Exception) {
                System.err.println("[DOCKER-REAPER] Failed to cleanup container $containerId: ${e.message}")
            }
        }
    }

    /**
     * Clean up orphaned containers from previous test runs that failed to clean up.
     * This can be called explicitly at the start of a test suite.
     */
    fun cleanupOrphanedContainers() {
        println("[DOCKER-REAPER] Scanning for orphaned test containers...")

        // List all containers with our base label
        val result = processRunner.run(
            listOf(
                "docker", "ps", "-aq",
                "--filter", DockerSessionLabels.createAllTestContainersFilter()
            ),
            description = "List all test containers",
            workingDir = workingDir,
            timeoutSeconds = 10,
        )

        if (result.exitCode != 0) {
            System.err.println("[DOCKER-REAPER] Failed to list containers: ${result.stderr}")
            return
        }

        val containerIds = result.output.trim().lines().filter { it.isNotBlank() }
        if (containerIds.isEmpty()) {
            println("[DOCKER-REAPER] No orphaned containers found")
            return
        }

        // Filter out containers from active processes
        val orphanedContainers = containerIds.filter { containerId ->
            isOrphanedContainer(containerId)
        }

        if (orphanedContainers.isEmpty()) {
            println("[DOCKER-REAPER] No orphaned containers to clean up")
            return
        }

        println("[DOCKER-REAPER] Found ${orphanedContainers.size} orphaned containers, cleaning up...")

        for (containerId in orphanedContainers) {
            try {
                killContainer(containerId)
            } catch (e: Exception) {
                System.err.println("[DOCKER-REAPER] Failed to cleanup orphaned container $containerId: ${e.message}")
            }
        }
    }

    /**
     * Check if a container is orphaned (its creator process is dead).
     */
    private fun isOrphanedContainer(containerId: String): Boolean {
        // Inspect container to get the PID label
        val result = processRunner.run(
            listOf(
                "docker", "inspect",
                "--format", "{{index .Config.Labels \"${DockerSessionLabels.PROCESS_ID_LABEL}\"}}",
                containerId
            ),
            description = "Inspect container PID",
            workingDir = workingDir,
            timeoutSeconds = 5,
        )

        if (result.exitCode != 0) {
            // If we can't inspect, assume it's orphaned
            return true
        }

        val pidStr = result.output.trim()
        if (pidStr.isEmpty() || pidStr == "<no value>") {
            // No PID label, could be old format or corrupted
            return true
        }

        val pid = pidStr.toLongOrNull() ?: return true

        // Check if the process is still alive
        return !ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
    }

    companion object {
        private val instance = DockerReaper()

        /**
         * Get the singleton instance of the reaper.
         */
        fun getInstance(): DockerReaper = instance
    }
}
