/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.docker

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.testHelper.createTempDirectory
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

    /**
     * Start the custom reaper container and establish connection.
     * Idempotent — only the first call performs actual work.
     *
     * Uses [DockerDriver] to build the image and [startContainerDriver] to start the container.
     * Uses [ContainerDriver.mapContainerPortToHostPort] for port mapping.
     * Container IDs registered before the connection is established are buffered
     * in a [Channel] with capacity 128.
     */
    fun start(workDir: File) {
        if (!started.compareAndSet(false, true)) return

        println("[REAPER] Starting custom reaper container...")

        val driver = DockerDriver(workDir, "REAPER")

        // Build the reaper image from classpath resources
        val buildContext = prepareBuildContext()
        try {
            driver.buildDockerImage(
                IMAGE_NAME,
                File(buildContext, "Dockerfile"),
                120
            )
        } finally {
            buildContext.deleteRecursively()
        }

        // Start the reaper container using ContainerDriver infrastructure.
        // startContainerDriver calls back into registerContainer()
        // which buffers the reaper's own container ID in the channel.
        val lifetime = CloseableStackHost()
        val port8080 = ContainerPort(8080)
        val containerDriver = startContainerDriver(
            lifetime = lifetime,
            scope = driver,
            imageName = IMAGE_NAME,
            volumes = listOf(ContainerVolume(File("/var/run/docker.sock"), "/var/run/docker.sock")),
            ports = listOf(port8080),
            autoRemove = true,
        )

        val reaperContainerId = containerDriver.containerId
        println("[REAPER] Container started: $reaperContainerId")

        // Map the container port to host port using ContainerDriver
        val hostPort = containerDriver.mapContainerPortToHostPort(port8080)
        println("[REAPER] Listening on host port: $hostPort")

        // Connect to the reaper socket with retries
        val socket = connectWithRetry("localhost", hostPort)
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
                    runCatching { socket.close() }
                    // Give reaper time to detect connection loss and clean up
                    delay(1000)
                    runCatching { lifetime.closeAllStacks() }
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

    private fun connectWithRetry(host: String, port: Int): Socket {
        var lastException: Exception? = null
        repeat(20) {
            try {
                val s = Socket(host, port)
                println("[REAPER] Connected to reaper socket")
                return s
            } catch (e: Exception) {
                lastException = e
                Thread.sleep(500)
            }
        }
        error("Failed to connect to reaper after retries: ${lastException?.message}")
    }

    private fun prepareBuildContext(): File {
        val dir = createTempDirectory("reaper-build")

        for (name in listOf("Dockerfile", "reaper.sh")) {
            val resourcePath = "docker/reaper/$name"
            val content = DockerReaper::class.java.classLoader
                .getResourceAsStream(resourcePath)
                ?.use { it.bufferedReader().readText() }
                ?: error("Resource not found: $resourcePath")
            val file = File(dir, name)
            file.writeText(content)
            file.setExecutable(true)
        }

        return dir
    }
}
