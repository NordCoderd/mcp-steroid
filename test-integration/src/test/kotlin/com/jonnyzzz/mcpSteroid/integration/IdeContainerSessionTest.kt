/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Integration test for IdeContainerSession infrastructure.
 *
 * Verifies that the Docker container can be built and started,
 * all directories are properly mounted, and the IDE starts successfully.
 */
class IdeContainerSessionTest {

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `container starts and IDE becomes ready`() {
        val dockerDir = File(IdeTestFolders.dockerDir, "ide-agent")

        val session = IdeContainerSession.start(
            containerName = "mcp-steroid-session-test",
            pluginZipPath = IdeTestFolders.pluginZip,
            ideaArchivePath = IdeTestFolders.downloadedIdea,
            testProjectDir = File(IdeTestFolders.dockerDir, "test-project"),
            dockerDir = dockerDir,
            testOutputDir = IdeTestFolders.testOutputDir,
        )

        // Verify run directory structure
        check(session.runDir.isDirectory) { "Run directory does not exist: ${session.runDir}" }
        check(File(session.runDir, "containerId").isFile) { "containerId file not created" }

        val containerId = File(session.runDir, "containerId").readText().trim()
        check(containerId.isNotEmpty()) { "containerId file is empty" }
        println("[TEST] Container ID: $containerId")

        // Verify all mount directories exist on host
        check(session.videoDir.isDirectory) { "Video dir not mounted: ${session.videoDir}" }
        check(session.screenshotDir.isDirectory) { "Screenshot dir not mounted: ${session.screenshotDir}" }
        check(session.configDir.isDirectory) { "Config dir not mounted: ${session.configDir}" }
        check(session.systemDir.isDirectory) { "System dir not mounted: ${session.systemDir}" }
        check(session.logDir.isDirectory) { "Log dir not mounted: ${session.logDir}" }
        check(session.pluginsDir.isDirectory) { "Plugins dir not mounted: ${session.pluginsDir}" }

        // Verify plugin was deployed (plugins dir should contain the extracted plugin)
        val pluginFiles = session.pluginsDir.listFiles()
        check(pluginFiles != null && pluginFiles.isNotEmpty()) {
            "Plugin directory is empty — plugin was not deployed"
        }
        println("[TEST] Plugin files: ${pluginFiles!!.map { it.name }}")

        // Start live video preview on macOS
        val previewThread = startLiveVideoPreview(session.videoFile)

        // Wait for IDE to be ready (MCP server responding)
        println("[TEST] Waiting for IDE to be ready...")
        session.waitForIdeReady(timeoutSeconds = 300)
        println("[TEST] IDE is ready")

        // Take a screenshot to verify display is working
        val screenshotFile = File(session.screenshotDir, "test-screenshot.png")
        session.takeScreenshot(screenshotFile)
        check(screenshotFile.isFile && screenshotFile.length() > 0) {
            "Screenshot was not captured: $screenshotFile"
        }
        println("[TEST] Screenshot captured: ${screenshotFile.length()} bytes")

        // Verify video recording is active (file should exist and be growing)
        check(session.videoFile.exists()) { "Video file does not exist: ${session.videoFile}" }
        println("[TEST] Video file size: ${session.videoFile.length()} bytes")

        // Stop video recording
        try {
            session.stopVideoRecording()
            println("[TEST] Video saved: ${session.videoFile} (${session.videoFile.length()} bytes)")
        } catch (e: Exception) {
            println("[TEST] Failed to stop video: ${e.message}")
        }

        previewThread?.interrupt()

        println("[TEST] IdeContainerSession infrastructure test passed")
        println("[TEST] Run directory: ${session.runDir}")
    }
}
