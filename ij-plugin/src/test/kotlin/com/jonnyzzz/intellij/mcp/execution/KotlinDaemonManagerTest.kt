/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.execution

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import javax.script.ScriptException

/**
 * Tests for KotlinDaemonManager.
 *
 * These tests verify the daemon management utilities work correctly.
 * Note: Tests that interact with actual daemon files may affect
 * running Kotlin daemons on the system.
 */
class KotlinDaemonManagerTest : BasePlatformTestCase() {

    private val manager: KotlinDaemonManager get() = kotlinDaemonManager

    // ========== Registry Key Tests ==========

    fun testProactiveDaemonKillDefaultEnabled() {
        // Default value in plugin.xml is true
        assertTrue(
            "Proactive daemon kill should be enabled by default",
            manager.isProactiveDaemonKillEnabled()
        )
    }

    fun testProactiveDaemonKillCanBeDisabled() {
        setRegistryPropertyForTest("mcp.steroids.daemon.kill.before.compile", "false")
        assertFalse(
            "Proactive daemon kill should be disabled when registry is false",
            manager.isProactiveDaemonKillEnabled()
        )
    }

    fun testReactiveDaemonRecoveryDefaultEnabled() {
        // Default value in plugin.xml is true
        assertTrue(
            "Reactive daemon recovery should be enabled by default",
            manager.isReactiveDaemonRecoveryEnabled()
        )
    }

    fun testReactiveDaemonRecoveryCanBeDisabled() {
        setRegistryPropertyForTest("mcp.steroids.daemon.recovery", "false")
        assertFalse(
            "Reactive daemon recovery should be disabled when registry is false",
            manager.isReactiveDaemonRecoveryEnabled()
        )
    }

    // ========== isDaemonDyingError Tests ==========

    fun testIsDaemonDyingErrorWithServiceIsDying() {
        val error = IllegalStateException("Service is dying")
        assertTrue(
            "Should detect 'Service is dying' error",
            manager.isDaemonDyingError(error)
        )
    }

    fun testIsDaemonDyingErrorWithCouldNotConnect() {
        val error = RuntimeException("Could not connect to Kotlin compile daemon")
        assertTrue(
            "Should detect 'Could not connect' error",
            manager.isDaemonDyingError(error)
        )
    }

    fun testIsDaemonDyingErrorWithNestedCause() {
        // Simulate the real error chain: IdeScriptException -> ScriptException -> IllegalStateException
        val rootCause = IllegalStateException("Service is dying")
        val scriptException = ScriptException("Compilation failed").apply { initCause(rootCause) }
        val wrapperException = RuntimeException("Script execution failed", scriptException)

        assertTrue(
            "Should detect 'Service is dying' in nested cause chain",
            manager.isDaemonDyingError(wrapperException)
        )
    }

    fun testIsDaemonDyingErrorWithUnrelatedError() {
        val error = IllegalArgumentException("Invalid argument")
        assertFalse(
            "Should not detect unrelated errors as daemon dying",
            manager.isDaemonDyingError(error)
        )
    }

    fun testIsDaemonDyingErrorWithNullMessage() {
        val error = RuntimeException(null as String?)
        assertFalse(
            "Should handle null message gracefully",
            manager.isDaemonDyingError(error)
        )
    }

    fun testIsDaemonDyingErrorWithPartialMatch() {
        // Should match substring
        val error = RuntimeException("Error: Service is dying unexpectedly")
        assertTrue(
            "Should match partial 'Service is dying' message",
            manager.isDaemonDyingError(error)
        )
    }

    // ========== getKotlinDaemonDir Tests ==========

    fun testGetKotlinDaemonDirReturnsNonNull() {
        val daemonDir = manager.getKotlinDaemonDir()
        assertNotNull("Daemon directory should not be null", daemonDir)
    }

    fun testGetKotlinDaemonDirContainsKotlinDaemon() {
        val daemonDir = manager.getKotlinDaemonDir()
        assertNotNull(daemonDir)
        assertTrue(
            "Daemon dir path should contain 'kotlin' and 'daemon'",
            daemonDir!!.path.contains("kotlin") && daemonDir.path.contains("daemon")
        )
    }

    // ========== File Operation Tests with Temp Directory ==========

    fun testCleanupClientMarkersWithEmptyDir() {
        val tempDir = createTempDirectory("daemon-test")
        try {
            val cleaned = manager.cleanupClientMarkers(tempDir)
            assertEquals("Should return 0 for empty directory", 0, cleaned)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    fun testCleanupClientMarkersDeletesMarkerFiles() {
        val tempDir = createTempDirectory("daemon-test")
        try {
            // Create marker files
            File(tempDir, "client1-is-running").createNewFile()
            File(tempDir, "client2-is-running").createNewFile()
            // Create non-marker file (should not be deleted)
            File(tempDir, "daemon.run").createNewFile()

            val cleaned = manager.cleanupClientMarkers(tempDir)

            assertEquals("Should delete 2 marker files", 2, cleaned)
            assertFalse("Marker file 1 should be deleted", File(tempDir, "client1-is-running").exists())
            assertFalse("Marker file 2 should be deleted", File(tempDir, "client2-is-running").exists())
            assertTrue("Non-marker file should still exist", File(tempDir, "daemon.run").exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    fun testCleanupClientMarkersWithNullDir() {
        val cleaned = manager.cleanupClientMarkers(null)
        assertEquals("Should return 0 for null directory", 0, cleaned)
    }

    fun testCleanupClientMarkersWithNonExistentDir() {
        val nonExistentDir = File("/non/existent/path/daemon")
        val cleaned = manager.cleanupClientMarkers(nonExistentDir)
        assertEquals("Should return 0 for non-existent directory", 0, cleaned)
    }

    // ========== getDaemonDiagnostics Tests ==========

    fun testGetDaemonDiagnosticsReturnsValidInfo() {
        val diagnostics = manager.getDaemonDiagnostics()

        assertNotNull("Diagnostics should not be null", diagnostics)
        assertNotNull("Directory path should not be null", diagnostics.directoryPath)
        assertTrue("Run file count should be non-negative", diagnostics.runFileCount >= 0)
        assertTrue("Client marker count should be non-negative", diagnostics.clientMarkerCount >= 0)
    }

    fun testGetDaemonDiagnosticsListsCorrectly() {
        val diagnostics = manager.getDaemonDiagnostics()

        // Lists should match counts
        assertEquals(
            "Run files list size should match count",
            diagnostics.runFileCount,
            diagnostics.runFiles.size
        )
        assertEquals(
            "Client markers list size should match count",
            diagnostics.clientMarkerCount,
            diagnostics.clientMarkers.size
        )
    }

    // ========== getRunningDaemonCount Tests ==========

    fun testGetRunningDaemonCountNonNegative() {
        val count = manager.getRunningDaemonCount()
        assertTrue("Running daemon count should be non-negative", count >= 0)
    }

    // ========== Delay Constants Tests ==========

    fun testDaemonDelayConstants() {
        assertTrue(
            "Daemon dying retry delay should be positive",
            manager.DAEMON_DYING_RETRY_DELAY_MS > 0
        )
        assertTrue(
            "Daemon kill retry delay should be positive",
            manager.DAEMON_KILL_RETRY_DELAY_MS > 0
        )
        assertTrue(
            "Kill delay should be >= dying delay for proper recovery",
            manager.DAEMON_KILL_RETRY_DELAY_MS >= manager.DAEMON_DYING_RETRY_DELAY_MS
        )
    }

    // ========== Helper Methods ==========

    private fun createTempDirectory(prefix: String): File {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "$prefix-${System.currentTimeMillis()}")
        tempDir.mkdirs()
        return tempDir
    }
}
