/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.execution

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.registry.Registry
import java.io.File

/**
 * Extension property to get the KotlinDaemonManager instance.
 */
inline val kotlinDaemonManager: KotlinDaemonManager get() = service()

/**
 * Manages the Kotlin compiler daemon lifecycle.
 *
 * The Kotlin daemon can enter corrupted states where:
 * - It loses the plugin's classpath (causing "incomplete code" errors)
 * - It enters "dying" state but is still discoverable (causing "Service is dying" errors)
 *
 * This service provides utilities to detect and recover from these states.
 *
 * ## Daemon Management Approach
 *
 * There is **no public API** to shutdown the Kotlin daemon from client code.
 * The `CompileService.shutdown()` method exists in the daemon but is not accessible.
 * Even JetBrains has a TODO in `KotlinJsr223JvmScriptEngine4IdeaBase.kt`:
 * `// TODO: need to manage resources here, i.e. call replCompiler.dispose when engine is collected`
 *
 * The **official mechanism** is file-based: the daemon monitors its `.run` files
 * and shuts down when they are deleted. This is per the
 * [Kotlin daemon documentation](https://kotlinlang.org/docs/kotlin-daemon.html).
 *
 * ## Important Limitations
 *
 * **Proactive daemon kill only works in production** (running IDE).
 * In tests, fresh daemons don't receive the plugin classpath from the test sandbox.
 * Tests must use reactive mode (`mcp.steroids.daemon.kill.before.compile=false`).
 *
 * @see <a href="https://kotlinlang.org/docs/kotlin-daemon.html">Kotlin Daemon Documentation</a>
 */
@Service(Service.Level.APP)
class KotlinDaemonManager {
    private val log = thisLogger()
    val DAEMON_KILL_RETRY_DELAY_MS = 1000L

    /**
     * Returns true if proactive daemon kill is enabled via registry key.
     * When enabled (default), the Kotlin daemon is killed before each compilation
     * to ensure a clean classpath state.
     */
    fun isProactiveDaemonKillEnabled(): Boolean {
        return Registry.`is`("mcp.steroids.daemon.kill.before.compile")
    }

    /**
     * Gets the Kotlin daemon directory path based on the operating system.
     *
     * Daemon directory locations:
     * - macOS: ~/Library/Application Support/kotlin/daemon
     * - Windows: %LOCALAPPDATA%/kotlin/daemon
     * - Linux: ~/.kotlin/daemon
     */
    fun getKotlinDaemonDir(): File? {
        val home = System.getProperty("user.home") ?: return null
        return when {
            SystemInfo.isMac -> File(home, "Library/Application Support/kotlin/daemon")
            SystemInfo.isWindows -> {
                val localAppData = System.getenv("LOCALAPPDATA")
                if (localAppData != null) {
                    File(localAppData, "kotlin/daemon")
                } else {
                    File(home, "AppData/Local/kotlin/daemon")
                }
            }

            else -> File(home, ".kotlin/daemon") // Linux and others
        }
    }

    /**
     * Forces the Kotlin daemon to shutdown by deleting its .run files.
     * The daemon monitors these files and shuts down when they're deleted.
     * Also cleans up stale client marker files.
     *
     * @return true if any daemon was killed, false if no daemons were running
     */
    fun forceKillKotlinDaemon(): Boolean {
        val daemonDir = getKotlinDaemonDir()
        if (daemonDir == null || !daemonDir.exists()) {
            log.info("Kotlin daemon directory not found")
            return false
        }

        var killedAny = false

        // Delete .run files to signal daemon shutdown
        daemonDir.listFiles()?.filter { it.name.endsWith(".run") }?.forEach { runFile ->
            try {
                if (runFile.delete()) {
                    log.info("Deleted Kotlin daemon run file: ${runFile.name}")
                    killedAny = true
                } else {
                    log.warn("Failed to delete run file: ${runFile.name}")
                }
            } catch (e: Exception) {
                log.warn("Error deleting run file ${runFile.name}: ${e.message}")
            }
        }

        // Clean up stale client marker files
        val cleanedMarkers = cleanupClientMarkers(daemonDir)
        if (cleanedMarkers > 0) {
            log.info("Cleaned up $cleanedMarkers stale client marker files")
        }

        return killedAny
    }

    /**
     * Cleans up stale client marker files (*-is-running) from the daemon directory.
     *
     * @return the number of marker files deleted
     */
    fun cleanupClientMarkers(daemonDir: File? = getKotlinDaemonDir()): Int {
        if (daemonDir == null || !daemonDir.exists()) return 0

        var cleanedMarkers = 0
        daemonDir.listFiles()?.filter { it.name.contains("-is-running") }?.forEach { marker ->
            try {
                if (marker.delete()) {
                    cleanedMarkers++
                }
            } catch (e: Exception) {
                // Ignore errors cleaning up markers
            }
        }
        return cleanedMarkers
    }

    /**
     * Returns the count of currently running daemon instances.
     * Each daemon has a .run file in the daemon directory.
     */
    fun getRunningDaemonCount(): Int {
        val daemonDir = getKotlinDaemonDir() ?: return 0
        if (!daemonDir.exists()) return 0
        return daemonDir.listFiles()?.count { it.name.endsWith(".run") } ?: 0
    }
}

