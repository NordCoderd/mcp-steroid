/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStack
import com.jonnyzzz.mcpSteroid.testHelper.docker.DockerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerVolume
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
    private val scope: DockerDriver,
    private val containerId: String,
) {

    /**
     * Wait for the IDE to be ready (MCP server responding to initialize request).
     * Polls the MCP HTTP endpoint inside the container.
     */
    fun waitForIdeReady(timeoutSeconds: Long = 300) {
        val startTime = System.currentTimeMillis()
        val deadline = startTime + timeoutSeconds * 1000

        val mcpInit =
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}"""

        while (System.currentTimeMillis() < deadline) {
            val result = scope.runInContainer(
                containerId,
                listOf(
                    "curl", "-s", "-f", "-X", "POST",
                    "http://localhost:6315/mcp",
                    "-H", "Content-Type: application/json",
                    "-d", mcpInit,
                ),
                timeoutSeconds = 5,
            )
            if (result.exitCode == 0) {
                println("[IDE-AGENT] IDE is ready (took ${(System.currentTimeMillis() - startTime) / 1000}s)")
                return
            }
            Thread.sleep(3000)
        }
        error("IDE did not become ready within ${timeoutSeconds}s")
    }


    companion object
}

fun IdeContainerSession.Companion.create(
    lifetime: CloseableStack,
    dockerFileBase: String,
    projectName: String = "test-project",
): IdeContainerSession {
    val hostPaths = HostPaths(dockerFileBase)

    // Create all mount-point subdirectories
    println("[IDE-AGENT] Run directory: ${hostPaths.runDir}")

    val contextDir = hostPaths.dockerBuildDir
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
    println(
        "[IDE-AGENT] Prepared context: " + contextDir.walkTopDown()
            .joinToString("") { "\n - ${it.relativeTo(contextDir)}" })
    val scope = DockerDriver(contextDir, "IDE-AGENT")

    val imageName = "$dockerFileBase-test"
    scope.buildDockerImage(
        imageName = imageName,
        dockerfilePath = File(contextDir, "Dockerfile"),
        timeoutSeconds = 900,
    )

    val containerMountedPath = "/mcp-run-dir"

    var container = startContainerDriver(
        lifetime, scope, imageName,
        extraEnvVars = emptyMap(),
        volumes = listOf(
            ContainerVolume(hostPaths.runDir, containerMountedPath, "rw"),
        )
    )

    val xcvb = XcvbContainer(
        lifetime,
        container,
        "$containerMountedPath/video"
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
    sleep(30000)

    println(ijDriver.readLogs())

    TODO()

}


class HostPaths(containerName: String) {
    // Create timestamped run directory
    private val timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd'Z'HH-mm-ss-SSS").format(LocalDateTime.now())
    val runDir = File(IdeTestFolders.testOutputDir, "run-$timestamp-$containerName").apply {
        mkdirs()
    }

    //this must be fixed dir to enable Docker caches
    val dockerBuildDir = File(runDir, "docker").apply { mkdirs() }

    val videoDir = File(runDir, "video").apply { mkdirs() }
    val screenshotDir = File(runDir, "screenshots").apply { mkdirs() }
    val configDir = File(runDir, "ide-config").apply { mkdirs() }
    val systemDir = File(runDir, "ide-system").apply { mkdirs() }
    val logDir = File(runDir, "ide-log").apply { mkdirs() }
    val pluginsDir = File(runDir, "ide-plugins").apply { mkdirs() }

    private val tempDir = File(runDir, "temp").apply { mkdirs() }

    fun createTempDir(prefix: String): File {
        val tempDir = File(tempDir, "docker-$prefix-${System.currentTimeMillis()}")
        tempDir.mkdirs()
        return tempDir
    }
}
