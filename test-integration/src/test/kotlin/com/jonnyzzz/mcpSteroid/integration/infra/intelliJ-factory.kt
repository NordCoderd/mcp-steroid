/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStack
import com.jonnyzzz.mcpSteroid.testHelper.docker.*
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.collections.ArrayDeque
import kotlin.collections.List
import kotlin.collections.MutableList
import kotlin.collections.Set
import kotlin.collections.asSequence
import kotlin.collections.buildMap
import kotlin.collections.buildList
import kotlin.collections.emptyList
import kotlin.collections.filter
import kotlin.collections.firstOrNull
import kotlin.collections.forEach
import kotlin.collections.getOrPut
import kotlin.collections.isNotEmpty
import kotlin.collections.joinToString
import kotlin.collections.linkedSetOf
import kotlin.collections.maxByOrNull
import kotlin.collections.mutableListOf
import kotlin.collections.mutableMapOf
import kotlin.collections.orEmpty
import kotlin.collections.setOf
import kotlin.collections.sorted

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
    /**
     * When true, mounts the host Docker socket (`/var/run/docker.sock`) into the container
     * at the same path so Testcontainers-based tests can start sibling Docker containers.
     *
     * Requirements:
     * - The host Docker socket must exist at `/var/run/docker.sock`
     * - The `ide-base` Docker image must have `docker-ce-cli` installed (already done) and
     *   the `agent` user must be in the `docker` group (already done in the Dockerfile)
     *
     * Default: `false` (Docker socket not mounted — arena tests that use Testcontainers
     * will fail with "Could not find a valid Docker environment" unless this is enabled).
     */
    mountDockerSocket: Boolean = false,
    /**
     * When true, forwards the host SSH agent socket into the container and sets SSH_AUTH_SOCK.
     * Required for git operations that use SSH remotes/private keys from inside the container.
     */
    mountSshAgent: Boolean = true,
): IntelliJContainer {
    val ideArchive = distribution.resolveAndDownload()
    val ideProduct = distribution.product
    val selectedDockerBase = if (dockerFileBase == "ide-agent") ideProduct.dockerImageBase else dockerFileBase
    val selectedProject = when {
        project != IntelliJProject.TestProject -> project
        ideProduct == IdeProduct.PyCharm -> IntelliJProject.PyCharmTestProject
        ideProduct == IdeProduct.GoLand -> IntelliJProject.GoLandTestProject
        ideProduct == IdeProduct.WebStorm -> IntelliJProject.WebStormTestProject
        ideProduct == IdeProduct.Rider -> IntelliJProject.RiderTestProject
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
    val imageId = buildIdeImage(selectedDockerBase, imageName, ideArchive)

    val containerMountedPath = "/mcp-run-dir"

    val dockerSocketFile = File("/var/run/docker.sock")
    val sshAgentGuestPath = "/tmp/ssh-agent.sock"
    val sshAgentSocketFile = if (mountSshAgent) {
        val sshAuthSock = System.getenv("SSH_AUTH_SOCK")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: error(
                "mountSshAgent=true but SSH_AUTH_SOCK is not set. " +
                        "Start ssh-agent on host and export SSH_AUTH_SOCK."
            )
        File(sshAuthSock).also { socket ->
            require(socket.exists()) {
                "mountSshAgent=true but SSH_AUTH_SOCK does not exist: ${socket.absolutePath}"
            }
        }
    } else {
        null
    }

    if (mountDockerSocket) {
        require(dockerSocketFile.exists()) {
            "mountDockerSocket=true but Docker socket not found at ${dockerSocketFile.absolutePath}. " +
            "Ensure Docker is running on the host."
        }
        println("[IDE-AGENT] Docker socket mount enabled: ${dockerSocketFile.absolutePath}")
    }
    if (sshAgentSocketFile != null) {
        println("[IDE-AGENT] SSH agent mount enabled: ${sshAgentSocketFile.absolutePath} -> $sshAgentGuestPath")
    }

    val volumes = buildList {
        add(ContainerVolume(runDir, containerMountedPath, "rw"))
        if (repoCacheDir != null) add(ContainerVolume(repoCacheDir, "/repo-cache", "ro"))
        if (mountDockerSocket) add(ContainerVolume(dockerSocketFile, "/var/run/docker.sock", "rw"))
        if (sshAgentSocketFile != null) add(ContainerVolume(sshAgentSocketFile, sshAgentGuestPath, "rw"))
    }
    val containerEnv = buildMap {
        if (sshAgentSocketFile != null) {
            put("SSH_AUTH_SOCK", sshAgentGuestPath)
        }
    }

    var container = startDockerContainerAndDispose(
        lifetime,
        StartContainerRequest()
            .image(imageId)
            .extraEnvVars(containerEnv)
            .volumes(volumes)
            .ports(
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


    // Warm project cache artifacts on host before deploying:
    // bare repos, IntelliJ clone ZIPs, etc. mounted at /repo-cache.
    if (repoCacheDir != null) {
        println("[IDE-AGENT] Warming project cache artifacts in ${repoCacheDir.absolutePath} ...")
        try {
            selectedProject.warmRepoCache(repoCacheDir)
        } catch (e: Exception) {
            println("[IDE-AGENT] WARNING: Failed to warm project cache artifacts: ${e.message}")
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
    var lastWindowDiagnosticsAt = 0L
    val ijWindowInfo = try {
        // 60s is safe: X11 frame appears before most startup work completes; the only
        // confirmed long blocker (AIPromoWindowAdvisor, 480s) is suppressed by our 4-layer fix.
        // Research confirmed no other pre-frame blocking network calls in the startup path.
        waitForValue(60_000, "Waiting for ${ideProduct.displayName} window") {
            val now = System.currentTimeMillis()
            if (now - lastPidRefreshAt >= 1_000) {
                trackedPids = discoverProcessFamilyPids(container, ijProcess.pid)
                lastPidRefreshAt = now
            }

            lastWindows = windowsDriver.listWindows()

            if (now - lastWindowDiagnosticsAt >= 5_000) {
                lastWindowDiagnosticsAt = now
                println("[IDE-AGENT] Waiting for ${ideProduct.displayName} window: PIDs=${trackedPids.sorted()}, visible=${lastWindows.size}")
                lastWindows.forEach { w ->
                    println("[IDE-AGENT]   id=${w.id} pid=${w.pid} ${w.rect.width}x${w.rect.height} title='${w.title}'")
                }
            }

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

    // Re-layout the console window now that fluxbox is fully settled (decorations applied).
    // The first updateLayout call in createConsoleDriver races with fluxbox applying the
    // apps file {NONE} decorations — by this point IntelliJ is up, so fluxbox has had
    // 30+ seconds to settle and the console position is corrected.
    val consoleWindow = windowsDriver.listWindows()
        .firstOrNull { it.title.contains(realConsoleTitle, ignoreCase = true) }
    if (consoleWindow != null) {
        windowsDriver.updateLayout(consoleWindow, windowsLayout.layoutStatusConsoleWindow())
    }

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
        mcpConnection = mcpConnectionMode,
        logDir = runDir,
    )

    console.writeSuccess("MCP Steroid server ready")

    // Write info file with all ports and URLs for external tools
    val videoPort = container.mapGuestPortToHostPort(XcvbVideoDriver.VIDEO_STREAMING_PORT)
    val mcpUrl = mcpSteroidDriver.hostMcpUrl
    val infoString = buildString {
        appendLine("=".repeat(20))
        appendLine("Use these parameters to debug the test")
        appendLine("RUN_DIR=$runDir")
        appendLine("CONTAINER_ID=${container.containerId}")
        appendLine("DISPLAY=${xcvb.DISPLAY}")
        appendLine("VIDEO_DASHBOARD=http://localhost:$videoPort/")
        appendLine("VIDEO_STREAM=http://localhost:$videoPort/video.mp4")
        appendLine("MCP_STEROID=$mcpUrl")
        appendLine("=".repeat(20))
    }
    val infoFile = File(runDir, "session-info.txt")
    infoFile.writeText(infoString)

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
        windowLayout = windowsLayout,
        openFileOnStart = selectedProject.openFileOnStart,
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
        .filter { it.title.isNotBlank() }
        .filterNot { it.title.equals("Desktop", ignoreCase = true) }
        .toList()
    if (sizableWindows.isEmpty()) return null

    // First preference: match by known process family PID
    val byProcessFamily = sizableWindows.filter { window ->
        val pid = window.pid ?: return@filter false
        pid in candidatePids
    }
    if (byProcessFamily.isNotEmpty()) {
        return byProcessFamily.maxByOrNull { it.rect.width * it.rect.height }
    }

    // Fallback: largest sizable non-console window (covers windows without exposed PID)
    return sizableWindows
        .asSequence()
        .filterNot { it.title.contains(consoleTitle, ignoreCase = true) }
        .maxByOrNull { it.rect.width * it.rect.height }
}

private fun discoverProcessFamilyPids(container: ContainerDriver, rootPid: Long): Set<Long> {
    val processMap = container.startProcessInContainer {
        this
            .args("bash", "-c", "ps -eo pid=,ppid=")
            .timeoutSeconds(5)
            .quietly()
            .description("ps -eo pid=,ppid=")
    }.awaitForProcessFinish()
    if (processMap.exitCode != 0) return setOf(rootPid)

    val childrenByParent = mutableMapOf<Long, MutableList<Long>>()
    processMap.stdout.lineSequence().forEach { line ->
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
