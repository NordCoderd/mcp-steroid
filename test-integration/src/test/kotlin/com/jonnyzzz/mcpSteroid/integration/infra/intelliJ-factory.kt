/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStack
import com.jonnyzzz.mcpSteroid.testHelper.docker.BareRepoCache
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerVolume
import com.jonnyzzz.mcpSteroid.testHelper.docker.startContainerDriver
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

//TODO: refactor parameters to a builder object, so we can update easily in the future, add "registerMcpSteroidToAgents" to the builder
fun IntelliJContainer.Companion.create(
    lifetime: CloseableStack,
    dockerFileBase: String = "ide-agent",
    consoleTitle: String,
    project : IntelliJProject = IntelliJProject.TestProject,
    layoutManager : LayoutManager = HorizontalLayoutManager(),
    distribution: IdeDistribution = IdeDistribution.fromSystemProperties(),
    aiMode: AiMode = AiMode.AI_MCP,
    repoCacheDir: File? = IdeTestFolders.repoCacheDirOrNull,
): IntelliJContainer {
    val ideArchive = distribution.resolveAndDownload()
    val ideProduct = distribution.product
    val selectedDockerBase = if (dockerFileBase == "ide-agent") ideProduct.dockerImageBase else dockerFileBase
    val selectedProject = when {
        project != IntelliJProject.TestProject -> project
        ideProduct == IdeProduct.PyCharm -> IntelliJProject.PyCharmTestProject
        ideProduct == IdeProduct.GoLand -> IntelliJProject.GoLandTestProject
        ideProduct == IdeProduct.WebStorm -> IntelliJProject.WebStormTestProject
        else -> project
    }

    val (runDir, realConsoleTitle) = run {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val runIdName = consoleTitle.split(" ").joinToString("-") { it.lowercase() }
        val file = File(IdeTestFolders.testOutputDir, "run-${timestamp}-${runIdName}")
        file.mkdirs()
        file to "$consoleTitle $timestamp"
    }

    println("[IDE-AGENT] Run directory: $runDir")
    // Unique suffix ensures parallel test runs each build their own image and context dir,
    // preventing races in buildIdeImage when multiple tests start concurrently.
    val uniqueSuffix = UUID.randomUUID().toString().take(8)
    val imageName = "$selectedDockerBase-test-$uniqueSuffix"
    val (scope, imageId) = buildIdeImage(selectedDockerBase, imageName, ideArchive)

    val containerMountedPath = "/mcp-run-dir"

    val volumes = buildList {
        add(ContainerVolume(runDir, containerMountedPath, "rw"))
        if (repoCacheDir != null) add(ContainerVolume(repoCacheDir, "/repo-cache", "ro"))
    }

    var container = startContainerDriver(
        lifetime, scope, imageId,
        extraEnvVars = emptyMap(),
        volumes = volumes,
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

    console.writeInfo("Preparing ${ideProduct.displayName}...")

    val inputDriver = XcvbInputDriver(container)
    val skillDriver = XcvbSkillDriver(lifetime, container)

    val ijDriver = IntelliJDriver(
        lifetime,
        container,
        "$containerMountedPath/intellij",
        ideProduct,
    )
    console.writeInfo("Deploying MCP Steroid plugin...")
    ijDriver.deployPluginToContainer(IdeTestFolders.pluginZip)


    // Warm the bare repo cache on the host before deploying, so the container can clone
    // from /repo-cache (fast, local) instead of hitting the remote (slow, network).
    val repoUrlForCache = selectedProject.getRepoUrlForCache()
    if (repoUrlForCache != null && repoCacheDir != null) {
        println("[IDE-AGENT] Warming bare repo cache for $repoUrlForCache ...")
        try {
            BareRepoCache.ensureRepo(repoUrlForCache, repoCacheDir)
        } catch (e: Exception) {
            println("[IDE-AGENT] WARNING: Failed to warm repo cache for $repoUrlForCache: ${e.message}")
        }
    }

    val ijProjectDriver = IntelliJProjectDriver(lifetime, container, ijDriver, console)
    ijProjectDriver.deployProject(selectedProject)

    console.writeInfo("Starting ${ideProduct.displayName}...")
    val ijProcess = ijDriver.startIde()
    console.writeSuccess("${ideProduct.displayName} process started")

    require(ijProcess.isRunning()) { "${ideProduct.displayName} process finished" }

    var trackedPids = setOf(ijProcess.pid)
    var lastPidRefreshAt = 0L
    var lastWindows = emptyList<WindowInfo>()
    val ijWindowInfo = try {
        waitForValue(30_000, "Waiting for ${ideProduct.displayName} window") {
            val now = System.currentTimeMillis()
            if (now - lastPidRefreshAt >= 1_000) {
                trackedPids = discoverProcessFamilyPids(container, ijProcess.pid)
                lastPidRefreshAt = now
            }

            lastWindows = windowsDriver.listWindows()
            pickIdeWindow(lastWindows, trackedPids, realConsoleTitle)
        }
    } catch (t: RuntimeException) {
        val windowsSnapshot = lastWindows.joinToString(separator = "\n") { info ->
            "id=${info.id} pid=${info.pid} rect=${info.rect.width}x${info.rect.height}+${info.rect.x}+${info.rect.y} title='${info.title}'"
        }
        throw RuntimeException(
            buildString {
                append("Failed waiting for ${ideProduct.displayName} window.")
                append(" trackedPids=${trackedPids.sorted()}")
                if (windowsSnapshot.isNotEmpty()) {
                    appendLine()
                    append("Visible windows:")
                    appendLine()
                    append(windowsSnapshot)
                }
            },
            t,
        )
    }

    windowsDriver.updateLayout(ijWindowInfo, windowsLayout.layoutIntelliJWindow())

    // Wait for MCP server readiness
    val mcpSteroidDriver = McpSteroidDriver(container, ijDriver)
    console.writeInfo("Waiting for MCP Steroid server...")
    mcpSteroidDriver.waitForMcpReady()

    val mcpConnectionMode = when (aiMode) {
        AiMode.NONE -> McpConnectionMode.None
        AiMode.AI_MCP -> McpConnectionMode.Http
        AiMode.AI_NPX -> McpConnectionMode.Npx(NpxSteroidDriver.deploy(container, mcpSteroidDriver))
    }

    val aiAgentDriver = AiAgentDriver(
        container = container,
        intellijDriver = ijDriver,
        console = console,
        mcp = mcpSteroidDriver,
        agentsGuestDir = "$containerMountedPath/agents",
        mcpConnection = mcpConnectionMode,
    )

    console.writeSuccess("MCP Steroid server ready")

    // Write info file with all ports and URLs for external tools
    val videoPort = container.mapGuestPortToHostPort(XcvbVideoDriver.VIDEO_STREAMING_PORT)
    val mcpUrl = mcpSteroidDriver.hostMcpUrl
    val infoFile = File(runDir, "session-info.txt")
    infoFile.writeText(buildString {
        appendLine("RUN_DIR=$runDir")
        appendLine("CONTAINER_ID=${container.containerId}")
        appendLine("DISPLAY=${xcvb.DISPLAY}")
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

private fun pickIdeWindow(
    windows: List<WindowInfo>,
    candidatePids: Set<Long>,
    consoleTitle: String,
): WindowInfo? {
    val sizableWindows = windows
        .asSequence()
        .filter { it.rect.width > 300 && it.rect.height > 300 }
        .filter { it.pid != null }
        .filter { it.title.isNotBlank() }
        .filterNot { it.title.equals("Desktop", ignoreCase = true) }
        .toList()
    if (sizableWindows.isEmpty()) return null

    val byProcessFamily = sizableWindows.filter { window ->
        val pid = window.pid ?: return@filter false
        pid in candidatePids
    }
    if (byProcessFamily.isNotEmpty()) {
        return byProcessFamily.maxByOrNull { it.rect.width * it.rect.height }
    }

    return sizableWindows
        .asSequence()
        .filterNot { it.title.contains(consoleTitle, ignoreCase = true) }
        .maxByOrNull { it.rect.width * it.rect.height }
}

private fun discoverProcessFamilyPids(container: ContainerDriver, rootPid: Long): Set<Long> {
    val processMap = container.runInContainer(
        listOf("bash", "-c", "ps -eo pid=,ppid="),
        timeoutSeconds = 5,
        quietly = true,
    )
    if (processMap.exitCode != 0) return setOf(rootPid)

    val childrenByParent = mutableMapOf<Long, MutableList<Long>>()
    processMap.output.lineSequence().forEach { line ->
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size != 2) return@forEach
        val pid = parts[0].toLongOrNull() ?: return@forEach
        val ppid = parts[1].toLongOrNull() ?: return@forEach
        childrenByParent.getOrPut(ppid) { mutableListOf() }.add(pid)
    }

    val discovered = linkedSetOf(rootPid)
    val queue = ArrayDeque<Long>()
    queue.add(rootPid)

    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        for (child in childrenByParent[current].orEmpty()) {
            if (discovered.add(child)) {
                queue.add(child)
            }
        }
    }

    return discovered
}
