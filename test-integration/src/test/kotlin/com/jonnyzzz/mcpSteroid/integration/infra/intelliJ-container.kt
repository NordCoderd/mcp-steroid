/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStack
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.RunningContainerProcess
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

        // Set up project JDK (if missing) and wait for Maven/Gradle sync to finish.
        // No-op when JDK is already set and no import is pending.
        console.writeStep(0, "Configuring project JDK and waiting for build tool sync...")
        mcpSteroid.mcpSetupJdkAndWaitForImport(guestProjectDir)
        console.writeSuccess("Build tool sync complete")

        // Open README.md (or fallback source file) and show build tool window so agents can orient
        // themselves from the IDE view immediately.
        console.writeStep(0, "Opening README.md and build tool window...")
        mcpSteroid.mcpOpenReadmeAndBuildToolWindow(guestProjectDir)
        console.writeSuccess("Project UX ready")

        return this
    }

    companion object
}
