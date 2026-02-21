/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.docker

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.testHelper.ProjectHomeDirectory
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.File
import java.io.PrintWriter
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Custom Docker resource reaper that automatically cleans up containers
 * when the JVM process crashes or is killed with SIGKILL.
 *
 * This is our own implementation replacing testcontainers/ryuk:
 * - Builds and starts a custom reaper container (Docker CLI + socat)
 * - Uses [DockerDriver] to build the image and [startContainerDriver] to start the container
 * - Uses [ContainerDriver] to map ports
 * - Connects via TCP socket and sends line-based commands
 * - Protocol: `container=<id>` registers a container, `ping` keeps alive
 * - Reaper kills all registered containers if no ping for 3 seconds or connection lost
 * - Container IDs are buffered in a [Channel] with capacity 128 before connection is established
 * - The reaper's own container ID is filtered out of the channel (it exits on its own after cleanup)
 *
 * No mutable fields: socket, writer, lifetime are all local to [start] and captured by coroutines.
 * [shutdown] cancels child coroutines, whose `finally` blocks close the socket and lifetime.
 * All background work runs on [Dispatchers.IO] (daemon threads).
 */
object DockerReaper {
    private const val IMAGE_NAME = "mcp-steroid-reaper"

    private val started = AtomicBoolean(false)
    private val containerChannel = Channel<String>(128)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private data class ReaperEndpoint(
        val host: String,
        val port: Int,
        val label: String,
    )

    /**
     * Start the custom reaper container and establish connection.
     * Idempotent — only the first call performs actual work.
     *
     * Uses [DockerDriver] to build the image and [startContainerDriver] to start the container.
     * Uses [ContainerDriver.mapGuestPortToHostPort] for port mapping.
     * Container IDs registered before the connection is established are buffered
     * in a [Channel] with capacity 128.
     */
    fun start(workDir: File) {
        if (!started.compareAndSet(false, true)) return

        println("[REAPER] Starting custom reaper container...")

        var lifetime: CloseableStackHost? = null
        try {
            val driver = DockerDriver(workDir, "REAPER")

            // Build the reaper image from the docker/reaper directory
            val projectHome = ProjectHomeDirectory.requireProjectHomeDirectory().toFile()
            val reaperDockerfile = projectHome.resolve("test-helper/src/main/docker/reaper/Dockerfile")
            require(reaperDockerfile.isFile) { "Reaper Dockerfile must exist: $reaperDockerfile" }

            val reaperImageId = driver.buildDockerImage(
                IMAGE_NAME,
                reaperDockerfile,
                120
            )

            // Start the reaper container using ContainerDriver infrastructure.
            // startContainerDriver calls back into registerContainer()
            // which buffers the reaper's own container ID in the channel.
            val runningLifetime = CloseableStackHost()
            lifetime = runningLifetime
            val port8080 = ContainerPort(8080)
            val containerDriver = startContainerDriver(
                lifetime = runningLifetime,
                scope = driver,
                imageName = reaperImageId,
                volumes = listOf(ContainerVolume(File("/var/run/docker.sock"), "/var/run/docker.sock")),
                ports = listOf(port8080),
                autoRemove = true,
            )

            val reaperContainerId = containerDriver.containerId
            println("[REAPER] Container started: $reaperContainerId")

            // Map the container port to host port using ContainerDriver
            val hostPort = containerDriver.mapGuestPortToHostPort(port8080)
            val containerIp = driver.queryContainerIp(reaperContainerId)
            println("[REAPER] Listening on host port: $hostPort")

            val endpoints = buildList {
                // Works for tests running directly on host.
                add(ReaperEndpoint(host = "localhost", port = hostPort, label = "mapped host port"))
                // Works for tests running in a dockerized builder container.
                add(ReaperEndpoint(host = "host.docker.internal", port = hostPort, label = "docker host alias"))
                // Works from sibling containers on the default bridge network.
                if (!containerIp.isNullOrBlank()) {
                    add(ReaperEndpoint(host = containerIp, port = port8080.containerPort, label = "container bridge IP"))
                }
            }.distinctBy { it.host to it.port }

            // Connect to the reaper socket with retries.
            val socket = connectWithRetry(endpoints)
            val writer = PrintWriter(socket.getOutputStream(), true)
            val writeLock = Any()

            val sendLine: (String) -> Unit = { line ->
                synchronized(writeLock) {
                    try {
                        writer.println(line)
                    } catch (e: Exception) {
                        println("[REAPER] Failed to send '$line': ${e.message}")
                    }
                }
            }

            // Consumer coroutine: drains the channel and sends container IDs to reaper.
            // Filters out the reaper's own container ID — the reaper exits on its own after cleanup.
            // On cancellation: closes the socket (triggers reaper cleanup) and the lifetime.
            scope.launch {
                try {
                    for (containerId in containerChannel) {
                        if (containerId == reaperContainerId) continue
                        sendLine("container=$containerId")
                    }
                } finally {
                    withContext(NonCancellable) {
                        try {
                            socket.close()
                        } catch (_: Exception) {
                            // Ignore socket close errors
                        }
                        // Give reaper time to detect connection loss and clean up
                        delay(1000)
                        try {
                            runningLifetime.closeAllStacks()
                        } catch (_: Exception) {
                            // Ignore cleanup errors
                        }
                    }
                }
            }

            // Ping loop: sends "ping" every 1 second to keep the reaper alive
            scope.launch {
                while (isActive) {
                    delay(1000)
                    sendLine("ping")
                }
            }

            println("[REAPER] Ready.")
        } catch (e: Exception) {
            started.set(false)
            try {
                lifetime?.closeAllStacks()
            } catch (_: Exception) {
                // Ignore cleanup errors after startup failure.
            }
            throw e
        }
    }

    /**
     * Register a container for cleanup.
     * Implicitly starts the reaper on a daemon thread if not already started.
     * Container IDs are buffered in a [Channel] with capacity 128 —
     * safe to call before the reaper connection is established.
     */
    fun registerContainer(containerId: String, workDir: File) {
        containerChannel.trySend(containerId)
        if (!started.get()) {
            scope.launch { start(workDir) }
        }
    }

    /**
     * Shutdown the reaper (for testing).
     * Cancels child coroutines; their `finally` blocks close the socket and lifetime.
     * Uses [cancelChildren] so the scope stays usable for subsequent [start] calls.
     */
    fun shutdown() {
        println("[REAPER] Shutting down...")
        scope.coroutineContext.cancelChildren()
        started.set(false)
    }

    private fun connectWithRetry(endpoints: List<ReaperEndpoint>): Socket {
        require(endpoints.isNotEmpty()) { "No reaper endpoints provided" }

        var lastException: Exception? = null
        repeat(20) {
            for (endpoint in endpoints) {
                try {
                    val socket = Socket(endpoint.host, endpoint.port)
                    println("[REAPER] Connected to reaper socket via ${endpoint.host}:${endpoint.port} (${endpoint.label})")
                    return socket
                } catch (e: Exception) {
                    lastException = e
                }
            }
            Thread.sleep(500)
        }
        val targets = endpoints.joinToString { "${it.host}:${it.port}" }
        error("Failed to connect to reaper after retries (targets: $targets): ${lastException?.message}")
    }

}
