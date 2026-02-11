/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStack
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerVolume
import com.jonnyzzz.mcpSteroid.testHelper.docker.startContainerDriver
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

//TODO: refactor parameters to a builder object, so we can update easily in the future, add "registerMcpSteroidToAgents" to the builder
fun IntelliJContainer.Companion.create(
    lifetime: CloseableStack,
    dockerFileBase: String,
    consoleTitle: String,
    project : IntelliJProject = IntelliJProject.TestProject,
    layoutManager : LayoutManager = HorizontalLayoutManager(),
): IntelliJContainer {
    val (runDir, realConsoleTitle) = run {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val runIdName = consoleTitle.split(" ").joinToString("-") { it.lowercase() }
        val file = File(IdeTestFolders.testOutputDir, "run-${timestamp}-${runIdName}")
        file.mkdirs()
        file to "$consoleTitle $timestamp"
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
            XcvbVideoDriver.VIDEO_STREAMING_PORT,
            McpSteroidDriver.MCP_STEROID_PORT,
        ),
    )

    val xcvb = XcvbDriver(
        lifetime,
        container,
        layoutManager
    )

    xcvb.startDisplayServer()
    container = xcvb.withDisplay(container)

    val windowsDriver = XcvbWindowDriver(lifetime, container, xcvb.wholeScreenAreal())
    windowsDriver.startWindowManager()

    val videoDriver = XcvbVideoDriver(lifetime, container, windowsDriver, xcvb, "$containerMountedPath/video", realConsoleTitle)
    videoDriver.startVideoService()

    val screenshotDriver = XcvbScreenshotDriver(lifetime, container, "$containerMountedPath/screenshot")
    screenshotDriver.startScreenshotCapture()

    val windowsLayout = WindowLayoutManager(windowsDriver, layoutManager)

    val consoleDriver = XcvbConsoleDriver(lifetime, container, windowsDriver)
    val console = consoleDriver.createConsoleDriver(container, realConsoleTitle, windowsLayout.layoutStatusConsoleWindow())

    console.writeInfo("Preparing IntelliJ IDEA...")

    val inputDriver = XcvbInputDriver(container)
    val skillDriver = XcvbSkillDriver(lifetime, container)

    val ijDriver = IntelliJDriver(
        lifetime,
        container,
        "$containerMountedPath/intellij",
    )
    console.writeInfo("Deploying MCP Steroid plugin...")
    ijDriver.deployPluginToContainer(IdeTestFolders.pluginZip)


    val ijProjectDriver = IntelliJProjectDriver(lifetime, container, ijDriver, console)
    ijProjectDriver.deployProject(project)

    console.writeInfo("Starting IntelliJ IDEA...")
    val ijProcess = ijDriver.startIde()
    console.writeSuccess("IntelliJ IDEA process started")

    require(ijProcess.isRunning()) { "IntelliJ IDEA process finished" }

    val ijWindowInfo = waitForValue(5_000, "Waiting for IntelliJ IDEA window") {
        windowsDriver
            .listWindows()
            .filter { it.pid == ijProcess.pid }
            //IntelliJ will show multiple windows, and we need to wait for the project window here
            //we need to filter the banner in start
            .singleOrNull { it.rect.height > 800 }
    }

    windowsDriver.updateLayout(ijWindowInfo, windowsLayout.layoutIntelliJWindow())

    // Wait for MCP server readiness
    val mcpSteroidDriver = McpSteroidDriver(container, ijDriver)
    console.writeInfo("Waiting for MCP Steroid server...")
    mcpSteroidDriver.waitForMcpReady()

    val aiAgentDriver = AiAgentDriver(
        container = container,
        intellijDriver = ijDriver,
        console = console,
        mcp = mcpSteroidDriver,

    )

    console.writeSuccess("MCP Steroid server ready")

    // Write info file with all ports and URLs for external tools
    val videoPort = container.mapGuestPortToHostPort(XcvbVideoDriver.VIDEO_STREAMING_PORT)
    val mcpUrl = aiAgentDriver.mcpSteroidHostUrl
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

    val session = IntelliJContainer(
        lifetime = lifetime,
        runDirInContainer = runDir,
        scope = container,
        intellijDriver = ijDriver,
        console = console,
        input = inputDriver,
        mcpSteroid = mcpSteroidDriver,
        aiAgents = aiAgentDriver,
        intellij = ijProcess,
        windows = windowsDriver,
    )

    println("[IDE-AGENT] Session ready: $runDir")
    return session
}
