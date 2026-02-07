/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStack
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.DockerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerPort
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerVolume
import com.jonnyzzz.mcpSteroid.testHelper.docker.RunningContainerProcess
import com.jonnyzzz.mcpSteroid.testHelper.docker.startContainerDriver
import java.io.File
import java.lang.Thread.sleep
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
class IdeContainerSession(
    val lifetime: CloseableStack,
    val scope: ContainerDriver,
    val intellijDriver: IntelliJDriver,
    val xcvbContainer: XcvbDriver,
    val intellij: RunningContainerProcess,
) {
    /** Host port mapped to the MCP Steroid server inside the container. */
    val mcpSteroidHostPort: Int?
        get() = scope.hostPorts[IntelliJDriver.MCP_STEROID_PORT]

    companion object
}

fun IdeContainerSession.Companion.create(
    lifetime: CloseableStack,
    dockerFileBase: String,
    projectName: String = "test-project",
): IdeContainerSession {
    val runDir = run {
        val timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd'Z'HH-mm-ss-SSS").format(LocalDateTime.now())
        val file = File(IdeTestFolders.testOutputDir, "run-$timestamp-$dockerFileBase")
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
            ContainerPort(XcvbDriver.VIDEO_STREAMING_PORT),
            ContainerPort(IntelliJDriver.MCP_STEROID_PORT),
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

    container = xcvb.withDisplay(container)

    val ijDriver = IntelliJDriver(
        lifetime,
        container,
        "$containerMountedPath/intellij",
    )

    ijDriver.mountProjectFiles(projectName)
    ijDriver.deployPluginToContainer(IdeTestFolders.pluginZip)
    val ijContainer = ijDriver.startIde()


    return IdeContainerSession(lifetime, container, ijDriver, xcvb, ijContainer)
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
