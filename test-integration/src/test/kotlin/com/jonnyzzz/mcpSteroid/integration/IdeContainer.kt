/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStack
import com.jonnyzzz.mcpSteroid.testHelper.assertExitCode
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
) {
    fun listWindows(): List<WindowInfo> {
        return xcvbContainer.listWindows()
    }

    /**
     * Move the IDE project window to the left 2/3 of the screen via MCP Steroid execute_code.
     * Delegates to the standalone [positionProjectWindow] function.
     */
    fun positionProjectWindow() {
        positionProjectWindow(xcvbContainer, intellijDriver)
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
 * Move the IDE project window to the left 2/3 of the screen via MCP Steroid execute_code.
 * Uses IntelliJ's AWT frame API to reposition and resize the window.
 * The remaining 1/3 on the right is reserved for agent consoles.
 *
 * Queries the actual usable screen area from the window manager (excluding taskbars)
 * instead of using raw display dimensions.
 *
 * Must be called after [AiAgentDriver.waitForMcpReady].
 */
private fun positionProjectWindow(xcvb: XcvbDriver, ijDriver: IntelliJDriver) {
    val workArea = xcvb.getWorkArea()
    val width = workArea.width * 2 / 3
    val height = workArea.height
    val x = workArea.x
    val y = workArea.y

    println("[IDE] Work area: $workArea")
    println("[IDE] Positioning project window to left 2/3 (${width}x${height}+${x}+${y})...")

    // Retry: the project may not be registered yet right after MCP server readiness.
    waitFor(60_000, "Position project window") {
        val result = ijDriver.mcpExecuteCode(
            projectName = "demo-project",
            code = """
                import java.awt.Rectangle
                import com.intellij.openapi.wm.WindowManager

                val frame = WindowManager.getInstance().getFrame(project)
                    ?: error("No frame found for project")

                frame.bounds = Rectangle($x, $y, $width, $height)
                println("Window positioned: ${'$'}{frame.bounds}")
            """.trimIndent(),
            taskId = "position-window",
            reason = "Position project window to left 2/3 of screen",
        )
        result.exitCode == 0
    }
    println("[IDE] Project window positioned")
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

    // Create all mount-point subdirectories
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

    val xcvb = XcvbDriver(
        lifetime,
        container,
        "$containerMountedPath/video",
        runId = runDir.name,
    )

    xcvb.startAllServices()
    xcvb.startLiveVideoPreview()
    xcvb.deploySkill()

    container = xcvb.withDisplay(container)

    val ijDriver = IntelliJDriver(
        lifetime,
        container,
        "$containerMountedPath/intellij",
    )

    ijDriver.mountProjectFiles(projectName)
    ijDriver.deployPluginToContainer(IdeTestFolders.pluginZip)
    val ijContainer = ijDriver.startIde()

    // Wait for MCP server readiness before creating console
    // (this uses a temporary AiAgentDriver just for waiting + url computation)
    val tempAgentDriver = AiAgentDriver(
        container = container,
        intellijDriver = ijDriver,
        xcvbDriver = xcvb,
        console = null,
    )
    tempAgentDriver.waitForMcpReady()

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

    // Create console first so all subsequent steps are visible in it
    val console = ConsoleDriver.create(lifetime, xcvb, container, consoleTitle)

    // Position IDE window (console shows the step)
    console.writeStep(0, "Positioning IDE window...")
    positionProjectWindow(xcvb, ijDriver)
    console.writeSuccess("IDE window positioned")

    val session = IdeContainer(lifetime, container, ijDriver, xcvb, ijContainer, console)

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

    val xcvb = XcvbDriver(
        lifetime,
        container,
        "$containerMountedPath/video",
        runId = runDir.name,
    )

    xcvb.startAllServices()
    xcvb.startLiveVideoPreview()
    xcvb.deploySkill()

    container = xcvb.withDisplay(container)

    val ijDriver = IntelliJDriver(
        lifetime,
        container,
        "$containerMountedPath/intellij",
    )

    ijDriver.cloneGitRepo(gitRepoUrl, timeoutSeconds = cloneTimeoutSeconds)
    ijDriver.deployPluginToContainer(IdeTestFolders.pluginZip)
    val ijContainer = ijDriver.startIde()

    // Wait for MCP server readiness
    val tempAgentDriver = AiAgentDriver(
        container = container,
        intellijDriver = ijDriver,
        xcvbDriver = xcvb,
        console = null,
    )
    tempAgentDriver.waitForMcpReady()

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

    // Create console first so all subsequent steps are visible in it
    val console = ConsoleDriver.create(lifetime, xcvb, container, consoleTitle)

    // Position IDE window (console shows the step)
    console.writeStep(0, "Positioning IDE window...")
    positionProjectWindow(xcvb, ijDriver)
    console.writeSuccess("IDE window positioned")

    val session = IdeContainer(lifetime, container, ijDriver, xcvb, ijContainer, console)

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
