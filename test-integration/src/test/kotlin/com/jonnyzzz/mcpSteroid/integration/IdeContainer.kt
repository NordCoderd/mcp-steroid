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
) {
    //TODO: we need an option to start MCP Steroid connection to agents or not
    val aiAgentDriver = AiAgentDriver(
        container = scope,
        intellijDriver = intellijDriver,
        xcvbDriver = xcvbContainer,
    )

    /** Convenience accessor for mouse/keyboard control inside the Xvfb session. */
    val input: XcvbDriver get() = xcvbContainer

    companion object
}

fun IdeContainer.Companion.create(
    lifetime: CloseableStack,
    dockerFileBase: String,
    projectName: String = "test-project",
): IdeContainer {
    val runDir = run {
        val file = File(IdeTestFolders.testOutputDir, "run-$dockerFileBase")
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

    val session = IdeContainer(lifetime, container, ijDriver, xcvb, ijContainer)

    // Write info file with all ports and URLs for external tools
    val videoPort = container.mapContainerPortToHostPort(XcvbDriver.VIDEO_STREAMING_PORT)
    val mcpUrl = session.aiAgentDriver.mcpSteroidHostUrl
    val infoFile = File(runDir, "session-info.txt")
    infoFile.writeText(buildString {
        appendLine("RUN_DIR=$runDir")
        appendLine("VIDEO_DASHBOARD=http://localhost:$videoPort/")
        appendLine("VIDEO_STREAM=http://localhost:$videoPort/video.mp4")
        appendLine("MCP_STEROID=$mcpUrl")
    })
    println()
    println("=".repeat(60))
    println("  VIDEO DASHBOARD: http://localhost:$videoPort/")
    println("  MCP STEROID:     $mcpUrl")
    println("  SESSION INFO:    $infoFile")
    println("=".repeat(60))
    println()

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
    gitRepoUrl: String,
    cloneTimeoutSeconds: Long = 300,
): IdeContainer {
    val runDir = run {
        val file = File(IdeTestFolders.testOutputDir, "run-$dockerFileBase")
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

    val session = IdeContainer(lifetime, container, ijDriver, xcvb, ijContainer)


    val videoPort = container.mapContainerPortToHostPort(XcvbDriver.VIDEO_STREAMING_PORT)
    val mcpUrl = session.aiAgentDriver.mcpSteroidHostUrl
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
    println("  VIDEO DASHBOARD: http://localhost:$videoPort/")
    println("  MCP STEROID:     $mcpUrl")
    println("  GIT REPO:        $gitRepoUrl")
    println("  SESSION INFO:    $infoFile")
    println("=".repeat(60))
    println()

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

    val filesList = contextDir
        .walkTopDown()
        .joinToString("") { "\n - ${it.relativeTo(contextDir)}" }

    println("[IDE-AGENT] Prepared context: $filesList")

    val scope = DockerDriver(contextDir, "IDE-AGENT")

    scope.buildDockerImage(
        imageName = imageName,
        dockerfilePath = File(contextDir, "Dockerfile"),
        timeoutSeconds = 900,
    )

    return scope
}
