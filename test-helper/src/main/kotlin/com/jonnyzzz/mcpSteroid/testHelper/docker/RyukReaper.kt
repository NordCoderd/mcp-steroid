/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.docker

import com.jonnyzzz.mcpSteroid.testHelper.ProcessRunner
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.PrintWriter
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ryuk-based Docker resource reaper using socket communication.
 *
 * This implementation:
 * - Starts a testcontainers/ryuk container that monitors a TCP connection
 * - Connects to Ryuk and sends ping messages every 1 second
 * - Registers containers to be cleaned up via the socket (line-based protocol)
 * - Ryuk cleans up all registered resources when connection breaks or pings stop for 5 seconds
 * - Handles SIGKILL and hard crashes (OS closes socket automatically)
 */
class RyukReaper private constructor() {
    private val started = AtomicBoolean(false)
    private val registeredContainers = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var writer: PrintWriter? = null

    @Volatile
    private var pingJob: Job? = null

    @Volatile
    private var ryukContainerId: String? = null

    private val processRunner = ProcessRunner("RYUK-REAPER", emptyList())

    /**
     * Start the Ryuk reaper container and establish connection.
     * This is called automatically when the first container is registered.
     */
    @Synchronized
    fun start(workDir: java.io.File) {
        if (started.compareAndSet(false, true)) {
            println("[RYUK-REAPER] Starting Ryuk reaper container...")

            // Start Ryuk container with docker socket mounted
            val result = processRunner.run(
                listOf(
                    "docker", "run",
                    "-d",
                    "--privileged",
                    "-v", "/var/run/docker.sock:/var/run/docker.sock",
                    "-p", "0:8080",
                    "testcontainers/ryuk:0.5.1"
                ),
                description = "Start Ryuk reaper",
                workingDir = workDir,
                timeoutSeconds = 30
            )

            if (result.exitCode != 0) {
                started.set(false)
                error("Failed to start Ryuk container: ${result.stderr}")
            }

            ryukContainerId = result.output.trim()
            println("[RYUK-REAPER] Ryuk container started: $ryukContainerId")

            // Get mapped port
            val portResult = processRunner.run(
                listOf("docker", "port", ryukContainerId!!, "8080/tcp"),
                description = "Get Ryuk port",
                workingDir = workDir,
                timeoutSeconds = 5
            )

            if (portResult.exitCode != 0) {
                started.set(false)
                error("Failed to get Ryuk port: ${portResult.stderr}")
            }

            val port = portResult.output.trim().substringAfterLast(':').toIntOrNull()
                ?: error("Failed to parse Ryuk port: ${portResult.output}")

            println("[RYUK-REAPER] Ryuk listening on port: $port")

            // Connect to Ryuk
            connectToRyuk("localhost", port)

            // Start ping loop
            startPingLoop()

            println("[RYUK-REAPER] Session: ${DockerSessionLabels.SESSION_ID}")
        }
    }

    private fun connectToRyuk(host: String, port: Int) {
        try {
            socket = Socket(host, port)
            writer = PrintWriter(socket!!.getOutputStream(), true)

            // Register session filter with Ryuk
            // Ryuk protocol: "label=key=value\n"
            val filter = "label=${DockerSessionLabels.SESSION_ID_LABEL}=${DockerSessionLabels.SESSION_ID}"
            writer!!.println(filter)

            // Wait for ACK
            val reader = BufferedReader(socket!!.getInputStream().reader())
            val ack = reader.readLine()
            if (ack == "ACK") {
                println("[RYUK-REAPER] Connected and registered session filter")
            } else {
                error("Unexpected response from Ryuk: $ack")
            }
        } catch (e: Exception) {
            error("Failed to connect to Ryuk: ${e.message}")
        }
    }

    private fun startPingLoop() {
        // Start ping loop in background coroutine
        pingJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                while (isActive) {
                    writer?.println("ping") ?: break
                    delay(1000) // Send ping every 1 second
                }
            } catch (e: Exception) {
                println("[RYUK-REAPER] Ping loop stopped: ${e.message}")
            }
        }
    }

    /**
     * Register a container for cleanup.
     * The container will be killed when connection to Ryuk is lost.
     */
    fun registerContainer(containerId: String) {
        if (!started.get()) {
            error("Ryuk reaper not started. Call start() first.")
        }
        registeredContainers.add(containerId)
    }

    /**
     * Unregister a container (when cleaned up normally).
     */
    fun unregisterContainer(containerId: String) {
        registeredContainers.remove(containerId)
    }

    /**
     * Shutdown the reaper (for testing).
     */
    fun shutdown() {
        println("[RYUK-REAPER] Shutting down...")

        // Cancel ping loop
        pingJob?.cancel()

        // Close socket
        try {
            socket?.close()
        } catch (e: Exception) {
            println("[RYUK-REAPER] Error closing socket: ${e.message}")
        }

        started.set(false)
    }

    companion object {
        private val instance = RyukReaper()

        fun getInstance(): RyukReaper = instance
    }
}
