/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStack
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.DockerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerVolume
import com.jonnyzzz.mcpSteroid.testHelper.docker.RunningContainerProcess
import com.jonnyzzz.mcpSteroid.testHelper.docker.startContainerDriver
import java.io.File
import java.nio.file.Files.createLink
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.exists

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
class IdeContainer(
    val lifetime: CloseableStack,
    val scope: ContainerDriver,
    val intellijDriver: IntelliJDriver,
    val xcvbContainer: XcvbDriver,
    val intellij: RunningContainerProcess,
    val console: ConsoleDriver,
    val layoutManager: LayoutManager,
) {
    fun listWindows(): List<WindowInfo> {
        return xcvbContainer.listWindows()
    }

    /**
     * Move the IDE project window to the left 2/3 of the screen via xdotool.
     * Runs asynchronously in a background thread.
     */
    fun positionProjectWindow() {
        positionProjectWindowAsync(xcvbContainer, layoutManager, console = console)
    }

    /**
     * Wait for the IDE project to finish import and indexing.
     * Polls via MCP execute_code until DumbService reports smart mode.
     * Writes progress to the console.
     */
    fun waitForProjectReady() {
        console.writeStep(0, "Waiting for project import and indexing...")
        waitFor(300_000, "Project import and indexing") {
            val result = intellijDriver.mcpExecuteCode(
                projectName = "demo-project",
                code = """
                    import com.intellij.openapi.project.DumbService

                    val isDumb = DumbService.getInstance(project).isDumb
                    println("dumb=${'$'}isDumb")
                    if (isDumb) error("Still indexing")
                """.trimIndent(),
                taskId = "wait-project-ready",
                reason = "Wait for project import and indexing to complete",
            )
            result.exitCode == 0
        }
        console.writeSuccess("Project import and indexing complete")
    }

    //TODO: we need an option to start MCP Steroid connection to agents or not
    val aiAgentDriver = AiAgentDriver(
        container = scope,
        intellijDriver = intellijDriver,
        xcvbDriver = xcvbContainer,
        console = console,
    )

    /** Convenience accessor for mouse/keyboard control inside the Xvfb session. */
    val input: XcvbDriver get() = xcvbContainer

    companion object
}

/**
 * Position the IDE project window using the [LayoutManager] via xdotool.
 *
 * Runs in a **background thread** and polls for the window by title pattern.
 * This avoids the dependency on MCP server readiness that the old AWT-based
 * approach had, allowing the window to be positioned as soon as it appears.
 *
 * @param windowTitlePattern xdotool name pattern to find the IDE window
 */
private fun positionProjectWindowAsync(
    xcvb: XcvbDriver,
    layoutManager: LayoutManager,
    windowTitlePattern: String = "demo-project",
    console: ConsoleDriver? = null,
) {
    val rect = layoutManager.layoutIdeWindows()

    println("[IDE] Will position project window to left 2/3 (${rect.width}x${rect.height}+${rect.x}+${rect.y})...")

    kotlin.concurrent.thread(start = true, isDaemon = true, name = "ide-window-position") {
        runCatching {
            val positioned = xcvb.waitForWindowAndPlace(
                titlePattern = windowTitlePattern,
                rect = rect,
                timeoutMs = 120_000L,
            )
            if (positioned) {
                println("[IDE] Project window positioned via xdotool")
                console?.writeSuccess("IDE window positioned")
            } else {
                println("[IDE] WARNING: failed to position project window via xdotool")
                console?.writeError("Failed to position IDE window")
            }
        }
    }
}

fun IdeContainer.Companion.create(
    lifetime: CloseableStack,
    dockerFileBase: String,
    runId: String,
    projectName: String = "test-project",
    consoleTitle: String = "Test Console",
    waitForProjectReady: Boolean = false,
): IdeContainer {
    val runDir = run {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val file = File(IdeTestFolders.testOutputDir, "run-${timestamp}-${runId}")
        file.mkdirs()
        file
    }

    println("[IDE-AGENT] Run directory: $runDir")
    val imageName = "$dockerFileBase-test"
    val scope = buildIdeImage(dockerFileBase, imageName)

    val containerMountedPath = "/mcp-run-dir"

    var container = startContainerDriver(
        lifetime, scope, imageName,
        extraEnvVars = emptyMap(),
        volumes = listOf(
            ContainerVolume(runDir, containerMountedPath, "rw"),
        ),
        ports = listOf(
            XcvbDriver.VIDEO_STREAMING_PORT,
            IntelliJDriver.MCP_STEROID_PORT,
        ),
    )

    val layoutManager = DefaultLayoutManager()

    val xcvb = XcvbDriver(
        lifetime,
        container,
        "$containerMountedPath/video",
        runId = runDir.name,
        layoutManager = layoutManager,
    )
    layoutManager.xcvb = xcvb

    xcvb.startAllServices()
    xcvb.startLiveVideoPreview()
    xcvb.deploySkill()

    container = xcvb.withDisplay(container)

    // Create console early — visible during IDE startup and warmup
    val console = ConsoleDriver.create(lifetime, xcvb, container, consoleTitle, layoutManager)
    console.writeInfo("Preparing IntelliJ IDEA...")

    val ijDriver = IntelliJDriver(
        lifetime,
        container,
        "$containerMountedPath/intellij",
    )

    ijDriver.mountProjectFiles(projectName)
    ijDriver.deployPluginToContainer(IdeTestFolders.pluginZip)

    console.writeInfo("Starting IntelliJ IDEA...")
    val ijContainer = ijDriver.startIde()
    console.writeSuccess("IntelliJ IDEA process started")

    // Position IDE window as soon as it appears (async, via xdotool)
    positionProjectWindowAsync(xcvb, layoutManager, console = console)

    // Wait for MCP server readiness
    console.writeInfo("Waiting for MCP Steroid server...")
    val tempAgentDriver = AiAgentDriver(
        container = container,
        intellijDriver = ijDriver,
        xcvbDriver = xcvb,
        console = null,
    )
    tempAgentDriver.waitForMcpReady()
    console.writeSuccess("MCP Steroid server ready")

    // Write info file with all ports and URLs for external tools
    val videoPort = container.mapContainerPortToHostPort(XcvbDriver.VIDEO_STREAMING_PORT)
    val mcpUrl = tempAgentDriver.mcpSteroidHostUrl
    val infoFile = File(runDir, "session-info.txt")
    infoFile.writeText(buildString {
        appendLine("RUN_DIR=$runDir")
        appendLine("VIDEO_DASHBOARD=http://localhost:$videoPort/")
        appendLine("VIDEO_STREAM=http://localhost:$videoPort/video.mp4")
        appendLine("MCP_STEROID=$mcpUrl")
    })
    println()
    println("=".repeat(60))
    println("  RUN DIR:         $runDir")
    println("  VIDEO DASHBOARD: http://localhost:$videoPort/")
    println("  MCP STEROID:     $mcpUrl")
    println("  SESSION INFO:    $infoFile")
    println("=".repeat(60))
    println()

    val session = IdeContainer(lifetime, container, ijDriver, xcvb, ijContainer, console, layoutManager)

    // Wait for indexing (shown in console)
    if (waitForProjectReady) {
        session.waitForProjectReady()
    }

    println("[IDE-AGENT] Session ready: $runDir")
    return session
}

/**
 * Creates an IdeContainer that clones an external git repository as the project.
 * Useful for architecture investigation tests against real-world open source projects.
 *
 * @param gitRepoUrl URL of the git repository to clone
 * @param cloneTimeoutSeconds timeout for `git clone` (large repos may need more time)
 */
fun IdeContainer.Companion.createWithGitRepo(
    lifetime: CloseableStack,
    dockerFileBase: String,
    runId: String,
    gitRepoUrl: String,
    cloneTimeoutSeconds: Long = 300,
    consoleTitle: String = "Test Console",
    waitForProjectReady: Boolean = false,
): IdeContainer {
    val runDir = run {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val file = File(IdeTestFolders.testOutputDir, "run-${timestamp}-${runId}")
        file.mkdirs()
        file
    }

    println("[IDE-AGENT] Run directory: $runDir")
    val imageName = "$dockerFileBase-test"
    val scope = buildIdeImage(dockerFileBase, imageName)

    val containerMountedPath = "/mcp-run-dir"

    var container = startContainerDriver(
        lifetime, scope, imageName,
        extraEnvVars = emptyMap(),
        volumes = listOf(
            ContainerVolume(runDir, containerMountedPath, "rw"),
        ),
        ports = listOf(
            XcvbDriver.VIDEO_STREAMING_PORT,
            IntelliJDriver.MCP_STEROID_PORT,
        ),
    )

    val layoutManager = DefaultLayoutManager()

    val xcvb = XcvbDriver(
        lifetime,
        container,
        "$containerMountedPath/video",
        runId = runDir.name,
        layoutManager = layoutManager,
    )
    layoutManager.xcvb = xcvb

    xcvb.startAllServices()
    xcvb.startLiveVideoPreview()
    xcvb.deploySkill()

    container = xcvb.withDisplay(container)

    // Create console early — visible during git clone, IDE startup, and warmup
    val console = ConsoleDriver.create(lifetime, xcvb, container, consoleTitle, layoutManager)
    console.writeInfo("Cloning git repository...")

    val ijDriver = IntelliJDriver(
        lifetime,
        container,
        "$containerMountedPath/intellij",
    )

    ijDriver.cloneGitRepo(gitRepoUrl, timeoutSeconds = cloneTimeoutSeconds)
    console.writeSuccess("Repository cloned")

    ijDriver.deployPluginToContainer(IdeTestFolders.pluginZip)

    console.writeInfo("Starting IntelliJ IDEA...")
    val ijContainer = ijDriver.startIde()
    console.writeSuccess("IntelliJ IDEA process started")

    // Position IDE window as soon as it appears (async, via xdotool)
    positionProjectWindowAsync(xcvb, layoutManager, console = console)

    // Wait for MCP server readiness
    console.writeInfo("Waiting for MCP Steroid server...")
    val tempAgentDriver = AiAgentDriver(
        container = container,
        intellijDriver = ijDriver,
        xcvbDriver = xcvb,
        console = null,
    )
    tempAgentDriver.waitForMcpReady()
    console.writeSuccess("MCP Steroid server ready")

    val videoPort = container.mapContainerPortToHostPort(XcvbDriver.VIDEO_STREAMING_PORT)
    val mcpUrl = tempAgentDriver.mcpSteroidHostUrl
    val infoFile = File(runDir, "session-info.txt")
    infoFile.writeText(buildString {
        appendLine("RUN_DIR=$runDir")
        appendLine("VIDEO_DASHBOARD=http://localhost:$videoPort/")
        appendLine("VIDEO_STREAM=http://localhost:$videoPort/video.mp4")
        appendLine("MCP_STEROID=$mcpUrl")
        appendLine("GIT_REPO=$gitRepoUrl")
    })
    println()
    println("=".repeat(60))
    println("  RUN DIR:         $runDir")
    println("  VIDEO DASHBOARD: http://localhost:$videoPort/")
    println("  MCP STEROID:     $mcpUrl")
    println("  GIT REPO:        $gitRepoUrl")
    println("  SESSION INFO:    $infoFile")
    println("=".repeat(60))
    println()

    val session = IdeContainer(lifetime, container, ijDriver, xcvb, ijContainer, console, layoutManager)

    // Wait for indexing (shown in console)
    if (waitForProjectReady) {
        session.waitForProjectReady()
    }

    println("[IDE-AGENT] Session ready: $runDir")
    return session
}

private fun buildIdeImage(dockerFileBase: String, imageName: String): DockerDriver {
    val contextDir = File(IdeTestFolders.testOutputDir, "docker-$dockerFileBase")
    contextDir.mkdirs()
    println("[IDE-AGENT] Build context: $contextDir")
    IdeTestFolders.copyDockerFiles(dockerFileBase, contextDir)

    // Hard-link large IDEA archive to avoid copying ~1GB file.
    // Falls back to copy if hard link fails (e.g. cross-filesystem).
    val ideaArchivePath = IdeTestFolders.intelliJTarGz
    val ideaDest = File(contextDir, "idea.tar.gz").toPath()
    //optimization to make sure Docker will not rebuild files
    if (!ideaDest.exists()) {
        try {
            createLink(ideaDest, ideaArchivePath.toPath())
        } catch (_: Exception) {
            println("[IDE-AGENT] Hard link failed, copying IDEA archive...")
            ideaArchivePath.copyTo(File(contextDir, "idea.tar.gz"), overwrite = true)
        }
    }

    val topLevelFiles = contextDir.listFiles()
        ?.sortedBy { it.name }
        ?.joinToString("") { "\n - ${it.name}" + if (it.isDirectory) "/" else "" }
        ?: ""
    println("[IDE-AGENT] Prepared context:$topLevelFiles")

    val scope = DockerDriver(contextDir, "IDE-AGENT")

    scope.buildDockerImage(
        imageName = imageName,
        dockerfilePath = File(contextDir, "Dockerfile"),
        timeoutSeconds = 900,
    )

    return scope
}
