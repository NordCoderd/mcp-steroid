/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStack
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.ImageDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.RunningContainerProcess
import com.jonnyzzz.mcpSteroid.testHelper.docker.commitContainerToImage
import com.jonnyzzz.mcpSteroid.testHelper.docker.startProcessInContainer
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import java.io.File

/**
 * Controls how AI agents connect to MCP Steroid inside the test container.
 *
 * [IntelliJContainer.aiAgents] ([AiAgentDriver]) is always created regardless of mode.
 * This enum only determines which MCP transport (if any) is registered with each agent.
 *
 * Pass as [IntelliJContainer.create]'s `aiMode` parameter.
 */
enum class AiMode {
    /**
     * Agents are available but MCP Steroid is NOT registered with them.
     * Use for pure IDE/infrastructure tests that don't need MCP Steroid tools.
     */
    NONE,

    /**
     * Agents connect to MCP Steroid via HTTP (default).
     * Each agent has [AiAgentSession.registerHttpMcp] called with the guest-side URL.
     */
    AI_MCP,

    /**
     * Agents connect to MCP Steroid via an NPX stdio proxy.
     * [NpxSteroidDriver] is deployed before agents are initialized; each agent
     * has [AiAgentSession.registerNpxMcp] called with the resulting command.
     */
    AI_NPX,
}

/**
 * Manages a Docker container running IntelliJ IDEA with MCP Steroid plugin.
 * Assembles the Docker build context from separate artifacts and starts a named container.
 *
 * The container is NOT removed after the test — it stays around for debugging.
 * It IS removed before the next test run (by name).
 *
 * All IDE directories, video, and screenshots are mounted to a timestamped
 * run directory under testOutputDir for easy inspection and debugging.
 */
class IntelliJContainer(
    val lifetime: CloseableStack,
    val runDirInContainer: File,
    val scope: ContainerDriver,

    val intellijDriver: IntelliJDriver,

    val input: XcvbInputDriver,
    private val intellij: RunningContainerProcess,

    val console: ConsoleDriver,
    val mcpSteroid: McpSteroidDriver,

    /**
     * AI agent driver — always present.
     * Whether agents have MCP Steroid registered depends on the [AiMode] used at creation.
     */
    val aiAgents: AiAgentDriver,

    val windows: XcvbWindowDriver,
    private val windowLayout: WindowLayoutManager,

    /**
     * Relative path (from project root) of the file to open when the IDE starts.
     * When null, the default README.md / first source file fallback is used.
     */
    private val openFileOnStart: String? = null,
) {
    val pid by intellij::pid

    /**
     * Wait for the IDE project to finish import and indexing.
     * Polls via MCP execute_code until DumbService reports smart mode.
     * Writes progress to the console.
     *
     * When a modal dialog is detected (e.g. NewUI Onboarding in IntelliJ 2025.3.3+),
     * actively kills it via steroid_execute_code so Gradle import can proceed.
     */
    fun waitForProjectReady() : IntelliJContainer {
        console.writeStep(0, "Waiting for project import and indexing...")
        val guestProjectDir = intellijDriver.getGuestProjectDir()
        var lastDialogKillMs = 0L
        waitFor(600_000, "Project import and indexing") {
            val windows = mcpSteroid.mcpListWindows()
            val projectWindows = windows.filter { it.projectPath == guestProjectDir || it.projectName != null }

            if (projectWindows.isEmpty()) {
                return@waitFor false
            }

            val modalDialogPresent = projectWindows.any { it.modalDialogShowing }
            if (modalDialogPresent) {
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastDialogKillMs > 5_000) {
                    lastDialogKillMs = nowMs
                    mcpSteroid.killStartupDialogs(guestProjectDir)
                }
                return@waitFor false
            }

            projectWindows.any { window ->
                window.projectInitialized == true && window.indexingInProgress == false
            }
        }
        console.writeSuccess("Project import and indexing complete")

        // Re-apply IDE window layout as early as possible: IntelliJ restores its own saved window
        // bounds during project import/indexing, overriding the initial xdotool positioning.
        // Apply immediately after indexing so the IDE is correctly positioned before any further
        // setup steps (plugin install, JDK setup, README open) which can take several minutes.
        console.writeStep(0, "Applying IDE window layout...")
        repositionIdeWindow()
        console.writeSuccess("Window layout applied")

        // Install required IDE plugins (e.g. Kafka) detected from project dependencies.
        // Must happen before JDK/Maven setup so Maven re-sync benefits from fresh plugin support.
        console.writeStep(0, "Installing required IDE plugins...")
        mcpSteroid.mcpInstallRequiredPlugins(guestProjectDir)
        console.writeSuccess("Plugin installation complete")

        // Set up project JDK (if missing) and wait for Maven/Gradle sync to finish.
        // No-op when JDK is already set and no import is pending.
        // For Rider: the JavaSdk import will fail to compile, but the try/catch in
        // mcpSetupJdkAndWaitForImport handles this gracefully. Rider handles NuGet
        // restore during its own indexing phase — no JDK setup is needed.
        console.writeStep(0, "Configuring project JDK and waiting for build tool sync...")
        mcpSteroid.mcpSetupJdkAndWaitForImport(guestProjectDir)
        console.writeSuccess("Build tool sync complete")

        // Open the configured file (or README.md fallback) and show build tool window so agents
        // can orient themselves from the IDE view immediately.
        console.writeStep(0, "Opening project file and build tool window...")
        mcpSteroid.mcpOpenFileAndBuildToolWindow(guestProjectDir, openFileOnStart)
        console.writeSuccess("Project UX ready")

        return this
    }

    /**
     * Wait until indexing/import completes and commit a Docker snapshot image.
     *
     * Snapshot includes IntelliJ system directory because it lives on container-local FS
     * (not a host-mounted volume).
     */
    fun createIndexedSnapshot(imageTag: String): ImageDriver {
        waitForProjectReady()
        val projectDir = intellijDriver.getGuestProjectDir()
        val systemDir = intellijDriver.getGuestSystemDir()
        val configDir = intellijDriver.getGuestConfigDir()
        val pluginsDir = intellijDriver.getGuestPluginsDir()

        scope.startProcessInContainer {
            this
                .args("test", "-d", "$projectDir/.git")
                .timeoutSeconds(10)
                .description("Verify IntelliJ checkout exists at $projectDir")
                .quietly()
        }.assertExitCode(0) {
            "IntelliJ checkout is missing before snapshot: $projectDir"
        }

        scope.startProcessInContainer {
            this
                .args("test", "-d", systemDir)
                .timeoutSeconds(10)
                .description("Verify IntelliJ system directory exists at $systemDir")
                .quietly()
        }.assertExitCode(0) {
            "IntelliJ system directory not found before snapshot: $systemDir"
        }

        scope.startProcessInContainer {
            this
                .args("test", "-d", configDir)
                .timeoutSeconds(10)
                .description("Verify IntelliJ config directory exists at $configDir")
                .quietly()
        }.assertExitCode(0) {
            "IntelliJ config directory not found before snapshot: $configDir"
        }

        scope.startProcessInContainer {
            this
                .args("test", "-d", pluginsDir)
                .timeoutSeconds(10)
                .description("Verify IntelliJ plugins directory exists at $pluginsDir")
                .quietly()
        }.assertExitCode(0) {
            "IntelliJ plugins directory not found before snapshot: $pluginsDir"
        }

        console.writeStep(0, "Creating Docker snapshot: $imageTag ...")
        val snapshot = scope.commitContainerToImage(imageTag)
        console.writeSuccess(
            "Docker snapshot created: ${snapshot.imageIdToLog} " +
                    "(includes checkout + ide-system; ide-config/ide-plugins preserved via mounted volume)"
        )
        return snapshot
    }

    /**
     * Re-apply the IDE window layout by finding the IntelliJ window by PID and calling
     * [XcvbWindowDriver.updateLayout] with the target rect from [windowLayout].
     *
     * IntelliJ restores its own saved window bounds after project load, overriding the
     * initial xdotool positioning. This must be called after project initialization completes.
     */
    private fun repositionIdeWindow() {
        val ideWindow = windows.listWindows().firstOrNull { it.pid == pid }
        if (ideWindow == null) {
            println("[IDE-AGENT] repositionIdeWindow: no window found for PID=$pid, skipping")
            return
        }
        val targetRect = windowLayout.layoutIntelliJWindow()
        windows.updateLayout(ideWindow, targetRect)
        // Nudge the window size by 1px then restore: IntelliJ AWT may not notice the external
        // move and keeps rendering at the old position (50px gap at top, status bar clipped).
        // A second ConfigureNotify with a different size forces AWT to re-layout correctly.
        windows.forceRelayout(ideWindow, targetRect)
    }

    companion object
}
