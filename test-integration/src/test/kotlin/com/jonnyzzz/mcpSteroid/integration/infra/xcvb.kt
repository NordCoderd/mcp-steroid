/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStack
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerPort
import com.jonnyzzz.mcpSteroid.testHelper.docker.RunningContainerProcess
import kotlin.concurrent.thread

class XcvbDriver(
    private val lifetime: CloseableStack,
    driver: ContainerDriver,
    private val videoDirInContainer: String,
    private val runId: String = "unknown",
    private val layoutManager: LayoutManager,
) {
    val displayWidth get() = layoutManager.displayWidth
    val displayHeight get() = layoutManager.displayHeight
    private val DISPLAY = ":99"
    private val driver = driver.withEnv("DISPLAY", DISPLAY)

    /**
     * Container-local path for the video recording, NOT on the mounted volume.
     *
     * Docker Desktop virtiofs does not flush file data to the host while
     * the writing process (ffmpeg) keeps the file open. Screenshots work
     * because scrot opens-writes-closes each file, but ffmpeg writes
     * continuously. Writing to a non-mounted path avoids the issue;
     * we copy the file out during cleanup after ffmpeg exits.
     */
    private val videoInternalDir = "/tmp/xcvb-video"
    private val videoInternalPath = "$videoInternalDir/recording.mp4"
    private val wallpaperImagePath = "/usr/share/images/mcp-steroid-wallpaper.jpg"

    fun withDisplay(container: ContainerDriver): ContainerDriver {
        return container.withEnv("DISPLAY", DISPLAY)
    }

    fun startAllServices() {
        driver.mkdirs(videoDirInContainer)
        driver.mkdirs(videoInternalDir)
        startDisplayServer()

        startWindowManager()
        startVideoRecording()
        startVideoStreamingServer()
        startScreenshotCapture()
    }

    fun startDisplayServer(): RunningContainerProcess {
        println("[xcvb] Starting Xvfb...")
        val proc = driver.runInContainerDetached(
            listOf("Xvfb", DISPLAY, "-screen", "0", "${displayWidth}x${displayHeight}x24", "-ac"),
        )

        println("[xcvb] Waiting for display $DISPLAY to be ready...")
        val result = driver.runInContainer(
            listOf(
            "bash", "-c",
            "for i in \$(seq 1 150); do xdpyinfo -display $DISPLAY >/dev/null 2>&1 && exit 0; sleep 0.1; done; exit 1",
            ),
            timeoutSeconds = 20,
        )
        if (result.exitCode != 0) {
            error("[xcvb] Display $DISPLAY did not become ready within 15s")
        }
        println("[xcvb] Display $DISPLAY is ready")

        return proc
    }

    val videoGuestPath = "$videoDirInContainer/recording.mp4"
    val videoFile get() = driver.mapGuestPathToHostPath(videoGuestPath)

    fun startVideoRecording(): RunningContainerProcess {
        val hostVideoFile = driver.mapGuestPathToHostPath(videoGuestPath)
        println("[xcvb] Starting video recording (container-local: $videoInternalPath, host: $hostVideoFile)...")
        val proc = driver.runInContainerDetached(
            buildFfmpegLiveRecordingCommand(
                display = DISPLAY,
                outputPath = videoInternalPath,
                videoWidth = displayWidth,
                videoHeight = displayHeight,
            ),
        )

        val rsyncProc = startVideoRsync()

        lifetime.registerCleanupAction {
            rsyncProc.kill("TERM")
        }
        lifetime.registerCleanupAction {
            stopVideoRecordingAndCopyOut(proc)
        }

        return proc
    }

    /**
     * Periodically rsync the growing video file from the container-local path
     * to the mounted volume so the host has a reasonably current copy at all
     * times. Because the file uses fragmented MP4 (frag_keyframe+empty_moov),
     * each synced copy is a valid (if truncated) MP4.
     */
    private fun startVideoRsync(): RunningContainerProcess {
        println("[xcvb] Starting periodic video rsync to $videoGuestPath...")
        val rsyncScript = buildString {
            appendLine("while true; do")
            appendLine("  rsync --inplace $videoInternalPath $videoGuestPath 2>/dev/null")
            appendLine("  sleep 1")
            appendLine("done")
        }
        return driver.runInContainerDetached(
            listOf("bash", "-c", rsyncScript),
        )
    }

    /**
     * Gracefully stop ffmpeg, wait for it to finalize the MP4, then copy
     * the recording from the container-local path to the mounted volume
     * so it is available on the host.
     */
    private fun stopVideoRecordingAndCopyOut(proc: RunningContainerProcess) {
        val hostVideoFile = driver.mapGuestPathToHostPath(videoGuestPath)

        // Send SIGINT so ffmpeg writes the final trailer
        proc.kill("INT")

        // Wait for ffmpeg to exit (up to 10 seconds)
        val deadline = System.currentTimeMillis() + 10_000
        while (proc.isRunning() && System.currentTimeMillis() < deadline) {
            Thread.sleep(200)
        }
        if (proc.isRunning()) {
            println("[xcvb] ffmpeg did not exit after SIGINT, sending SIGKILL")
            proc.kill("KILL")
            Thread.sleep(500)
        }

        // Copy the finalized video from the container-local path to the mounted volume.
        // Using cp (open-write-close) instead of letting ffmpeg write directly to the
        // mount avoids the virtiofs stale-data issue.
        val copyResult = driver.runInContainer(
            listOf("bash", "-c", "cp $videoInternalPath $videoGuestPath && sync"),
            timeoutSeconds = 30,
        )
        if (copyResult.exitCode == 0) {
            println("[xcvb] Video recording copied to $hostVideoFile")
        } else {
            println("[xcvb] WARNING: failed to copy video recording: ${copyResult.output}")
        }

        println("Check out screen recording at $hostVideoFile")
    }

    /**
     * Start the Node.js HTTP server that streams the growing MP4 file.
     * The server is baked into the Docker image at /usr/local/bin/video-server.js.
     *
     * The server reads from the container-local video path (not the mounted
     * volume) so it sees ffmpeg's writes in real-time.
     */
    fun startVideoStreamingServer(): RunningContainerProcess {
        val hostPort = driver.mapContainerPortToHostPort(VIDEO_STREAMING_PORT)
        println("[xcvb] Starting video streaming server at http://localhost:$hostPort/")
        return driver.runInContainerDetached(
            listOf(
                "node",
                "/usr/local/bin/video-server.js",
                videoInternalPath,
                VIDEO_STREAMING_PORT.containerPort.toString(),
                runId,
                wallpaperImagePath,
            ),
        )
    }

    /**
     * Start periodic screenshot capture (one PNG per second).
     * Screenshots are saved to the mounted volume directory for live inspection.
     * (scrot opens-writes-closes each file, so virtiofs flushes correctly.)
     */
    private fun startScreenshotCapture(): RunningContainerProcess {
        println("[xcvb] Starting periodic screenshot capture to ${driver.mapGuestPathToHostPath(videoDirInContainer)}/...")
        val captureScript = buildString {
            appendLine("while true; do ")
            appendLine($$"  scrot $$videoDirInContainer/screen-$(date +%Y%m%d-%H%M%S).png")
            appendLine("  sleep 1")
            appendLine("done")
        }
        return driver.runInContainerDetached(
            listOf("bash", "-c", captureScript),
        )
    }

    fun startWindowManager(): RunningContainerProcess {
        // Override the Debian fluxbox style wallpaper (which references an
        // image from desktop-base that isn't installed) with our own.
        driver.writeFileInContainer(
            "/home/agent/.fluxbox/overlay",
            "background: fullscreen\nbackground.pixmap: /usr/share/images/mcp-steroid-wallpaper.jpg\n",
        )

        println("[xcvb] Starting fluxbox...")
        val proc = driver.runInContainerDetached(
            listOf("fluxbox"),
        )
        return proc
    }

    fun listWindows(): List<WindowInfo> {
        // Run a shell script to list all windows with their geometry and title efficiently
        // Output format: ID|X|Y|WIDTH|HEIGHT|PID|TITLE
        val d = '$'
        val script = """
            for id in ${d}(xdotool search --name "" 2>/dev/null); do
              unset X Y WIDTH HEIGHT PID
              name=${d}(xdotool getwindowname "${d}id" 2>/dev/null)
              eval ${d}(xdotool getwindowgeometry --shell "${d}id" 2>/dev/null)
              pid=${d}(xdotool getwindowpid "${d}id" 2>/dev/null)
              if [ -n "${d}X" ] && [ -n "${d}Y" ] && [ -n "${d}WIDTH" ] && [ -n "${d}HEIGHT" ]; then
                echo "${d}id|${d}X|${d}Y|${d}WIDTH|${d}HEIGHT|${d}pid|${d}name"
              fi
            done
        """.trimIndent()

        val result = driver.runInContainer(
            listOf("bash", "-c", script),
            timeoutSeconds = 5,
        )
        if (result.exitCode != 0) return emptyList()

        return result.output.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split('|', limit = 7)
                if (parts.size < 7) return@mapNotNull null
                val id = parts[0]
                val x = parts[1].toIntOrNull() ?: return@mapNotNull null
                val y = parts[2].toIntOrNull() ?: return@mapNotNull null
                val width = parts[3].toIntOrNull() ?: return@mapNotNull null
                val height = parts[4].toIntOrNull() ?: return@mapNotNull null
                val pid = parts[5]
                val title = parts[6]
                WindowInfo(id, title, WindowRect(x, y, width, height), pid)
            }
            .toList()
    }

    fun updateLayout(window: WindowInfo, rect: WindowRect): Boolean {
        return updateLayout(window.id, rect)
    }

    fun updateLayout(windowId: String, rect: WindowRect): Boolean {
        val move = runDriverCommand(
            listOf("xdotool", "windowmove", "--sync", windowId, rect.x.toString(), rect.y.toString()),
            timeoutSeconds = 5,
        )
        if (move.exitCode != 0) return false

        val resize = runDriverCommand(
            listOf("xdotool", "windowsize", "--sync", windowId, rect.width.toString(), rect.height.toString()),
            timeoutSeconds = 5,
        )
        if (resize.exitCode != 0) return false

        runDriverCommand(
            listOf("xdotool", "windowraise", windowId),
            timeoutSeconds = 5,
        )
        return true
    }

    /**
     * Query the usable screen area from the window manager.
     * Uses `xprop` to read the `_NET_WORKAREA` property from the X root window,
     * which excludes taskbars, panels, and other reserved areas.
     *
     * Falls back to the full display size if the property is not available.
     */
    fun getWorkArea(): WindowRect {
        val result = runDriverCommand(
            listOf("xprop", "-root", "_NET_WORKAREA"),
            timeoutSeconds = 5,
        )
        if (result.exitCode == 0) {
            // Output format: _NET_WORKAREA(CARDINAL) = 0, 0, 3840, 2140
            val match = Regex("""(\d+),\s*(\d+),\s*(\d+),\s*(\d+)""").find(result.output)
            if (match != null) {
                val (x, y, w, h) = match.destructured
                return WindowRect(x.toInt(), y.toInt(), w.toInt(), h.toInt())
            }
        }
        // Fallback to full display
        return WindowRect(0, 0, displayWidth, displayHeight)
    }

    // ── Visible console ────────────────────────────────────────────────

    /**
     * Run a command inside a visible xterm window on the X11 display.
     * The terminal output appears in the video recording, making agent
     * interactions observable during test playback.
     *
     * @param args command and arguments to run inside xterm
     * @param title window title for the xterm window
     * @param geometry xterm geometry string (columns x rows + position)
     * @return a detached process handle for the xterm window
     */
    fun runInVisibleConsole(
        args: List<String>,
        title: String = "Agent Console",
        geometry: String = "200x50+0+0",
        windowRect: WindowRect? = null,
        workingDir: String? = null,
        extraEnvVars: Map<String, String> = emptyMap(),
    ): RunningContainerProcess {
        val xtermArgs = mutableListOf(
            "xterm",
            "-u8",
            "-title", title,
            "-geometry", geometry,
            "-fa", "JetBrains Mono:style=Regular",
            "-fs", "16",
            "-bg", "black",
            "-fg", "white",
            "-e",
        ) + args

        println("[xcvb] Starting visible console '$title': ${args.joinToString(" ")}")
        val process = driver.runInContainerDetached(
            xtermArgs,
            workingDir = workingDir,
            extraEnvVars = extraEnvVars,
        )

        if (windowRect != null) {
            thread(start = true, isDaemon = true, name = "xcvb-layout-${title.take(16)}") {
                runCatching {
                    val positioned = waitForWindowAndPlace(
                        titlePatterns = listOf(title),
                        rect = windowRect,
                        timeoutMs = 10_000L,
                    )
                    if (!positioned) {
                        println("[xcvb] WARNING: failed to place visible console '$title'")
                    }
                }
            }
        }

        return process
    }

    /**
     * Create a [ContainerDriver] wrapper that runs commands in a visible
     * xterm on the X11 display. Output appears in the video AND is captured
     * for programmatic assertions.
     *
     * When the returned driver's [ContainerDriver.runInContainer] is called,
     * the command runs inside an xterm window. A background `tail -f` of the
     * detached log files mirrors output to the xterm display.
     *
     * Useful for wrapping agent sessions so their CLI interactions are visible
     * in test recordings.
     */
    fun withVisibleConsole(
        delegate: ContainerDriver,
        title: String = "Agent",
        geometry: String = "200x50+0+0",
        windowRect: WindowRect? = null,
    ): ContainerDriver {
        return VisibleConsoleContainerDriver(delegate, this, title, geometry, windowRect)
    }

    /**
     * Wrap a container driver so commands can be shown in the right-side
     * demo console area for a specific agent.
     */
    fun wrapForAgentConsole(
        delegate: ContainerDriver,
        title: String,
    ): ContainerDriver {
        val agentRect = WindowRect(
            x = displayWidth / 2,
            y = displayHeight / 4,
            width = displayWidth / 2,
            height = displayHeight / 2,
        )
        val agentGeometry = "220x58+${displayWidth / 2}+${displayHeight / 4}"
        return withVisibleConsole(
            delegate = delegate,
            title = title,
            geometry = agentGeometry,
            windowRect = agentRect,
        )
    }

    // ── Input control via xdotool ──────────────────────────────────────

    /** Move the mouse cursor to the given display coordinates. */
    fun mouseMove(x: Int, y: Int) {
        xdotool("mousemove", "--sync", x.toString(), y.toString())
    }

    /** Move the mouse to (x, y) and click the given button (1=left, 2=middle, 3=right). */
    fun mouseClick(x: Int, y: Int, button: Int = 1) {
        xdotool("mousemove", "--sync", x.toString(), y.toString())
        xdotool("click", button.toString())
    }

    /** Move the mouse to (x, y) and double-click. */
    fun mouseDoubleClick(x: Int, y: Int) {
        xdotool("mousemove", "--sync", x.toString(), y.toString())
        xdotool("click", "--repeat", "2", "1")
    }

    /**
     * Press a key or key combination.
     * Examples: `"Return"`, `"Tab"`, `"ctrl+s"`, `"alt+F4"`, `"shift+ctrl+p"`.
     */
    fun keyPress(key: String) {
        xdotool("key", key)
    }

    /** Type a text string character by character with a small inter-key delay. */
    fun typeText(text: String) {
        xdotool("type", "--delay", "50", "--", text)
    }

    /** Return the window ID of the currently active (focused) window. */
    fun getActiveWindowId(): String {
        return xdotool("getactivewindow").trim()
    }

    /** Activate (focus + raise) a window found by name pattern. */
    fun activateWindow(namePattern: String) {
        xdotool("search", "--name", namePattern, "windowactivate")
    }

    /** List all window IDs visible on the display. */
    fun listWindowIds(): List<String> {
        val output = xdotool("search", "--name", "")
        return output.lines().filter { it.isNotBlank() }
    }

    /** Capture a rectangular region of the screen to a file inside the container. */
    fun screenshotRegion(x: Int, y: Int, width: Int, height: Int, filename: String) {
        require(filename.matches(Regex("[a-zA-Z0-9._-]+"))) {
            "filename must be alphanumeric with dots/dashes/underscores only, got: $filename"
        }
        val destPath = "$videoDirInContainer/$filename"
        val result = driver.runInContainer(
            listOf(
                "import", "-window", "root",
                "-crop", "${width}x${height}+${x}+${y}",
                destPath,
            ),
            timeoutSeconds = 10,
        )
        if (result.exitCode != 0) {
            error("[xcvb] screenshotRegion failed (exit ${result.exitCode}): ${result.output}${result.stderr}")
        }
    }

    /** Copy text to the X11 clipboard. */
    fun clipboardCopy(text: String) {
        val result = driver.runInContainer(
            listOf("bash", "-c", "echo -n ${shellEscape(text)} | xclip -selection clipboard"),
            timeoutSeconds = 5,
        )
        if (result.exitCode != 0) {
            error("[xcvb] clipboardCopy failed (exit ${result.exitCode}): ${result.output}${result.stderr}")
        }
    }

    /** Read text from the X11 clipboard. */
    fun clipboardPaste(): String {
        val result = driver.runInContainer(
            listOf("xclip", "-selection", "clipboard", "-o"),
            timeoutSeconds = 5,
        )
        if (result.exitCode != 0) {
            error("[xcvb] clipboardPaste failed (exit ${result.exitCode}): ${result.output}${result.stderr}")
        }
        return result.output
    }

    private fun xdotool(vararg args: String): String {
        val result = driver.runInContainer(
            listOf("xdotool") + args.toList(),
            timeoutSeconds = 10,
        )
        if (result.exitCode != 0) {
            error("[xcvb] xdotool ${args.joinToString(" ")} failed (exit ${result.exitCode}): ${result.output}${result.stderr}")
        }
        return result.output
    }

    private fun shellEscape(s: String): String {
        return "'" + s.replace("'", "'\\''") + "'"
    }

    fun waitForWindowAndPlace(
        titlePattern: String,
        rect: WindowRect,
        timeoutMs: Long,
    ): Boolean = waitForWindowAndPlace(listOf(titlePattern), rect, timeoutMs)

    private fun waitForWindowAndPlace(
        titlePatterns: List<String>,
        rect: WindowRect,
        timeoutMs: Long,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            for (pattern in titlePatterns) {
                val windowId = findFirstWindowIdByName(pattern) ?: continue
                if (updateLayout(windowId, rect)) {
                    println("[xcvb] Positioned window '$pattern' ($windowId) to ${rect.width}x${rect.height}+${rect.x}+${rect.y}")
                    return true
                }
            }
            Thread.sleep(250)
        }
        return false
    }

    private fun findFirstWindowIdByName(namePattern: String): String? {
        val result = runDriverCommand(
            listOf("bash", "-lc", "xdotool search --name ${shellEscape(namePattern)} 2>/dev/null | head -n 1"),
            timeoutSeconds = 5,
        )
        if (result.exitCode != 0) return null
        return result.output.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
    }

    private fun runDriverCommand(args: List<String>, timeoutSeconds: Long) =
        driver.runInContainer(args, timeoutSeconds = timeoutSeconds)

    /**
     * Wait for the video streaming server to become ready, log its URL,
     * and open the dashboard in the default browser on macOS.
     *
     * The server always starts (inside Docker) and its address is always
     * logged. Only the browser `open` command is macOS-specific.
     */
    fun startLiveVideoPreview() {
        val hostPort = driver.mapContainerPortToHostPort(VIDEO_STREAMING_PORT)
        val dashboardUrl = "http://localhost:$hostPort/"

        val thread = thread(start = true) {
            runCatching {
                waitFor(35_000L, "Video streaming server ready") {
                    val process = ProcessBuilder("curl", "-s", "-o", "/dev/null", "-w", "%{http_code}", "${dashboardUrl}status")
                        .start()
                    process.waitFor()
                    process.inputStream.bufferedReader().readText().trim() == "200"
                }

                println("[VIDEO] Dashboard ready: $dashboardUrl")
                println("[VIDEO] Stream URL:     ${dashboardUrl}video.mp4")

                // Open browser on macOS only
                if (System.getProperty("os.name")?.contains("Mac", ignoreCase = true) == true) {
                    println("[VIDEO] Opening dashboard in browser...")
                    ProcessBuilder("open", dashboardUrl).start()
                }
            }
        }

        lifetime.registerCleanupAction {
            thread.interrupt()
        }
    }

    /**
     * Deploy the xcvb display control skill file into the container.
     * Agents can read this file to learn how to use xdotool, screenshots, etc.
     *
     * @return the guest path where the skill file was written
     */
    fun deploySkill(skillGuestPath: String = SKILL_GUEST_PATH): String {
        val skillContent = XcvbDriver::class.java.getResource("/skills/xcvb-display-control.md")
            ?.readText()
            ?: error("xcvb-display-control.md skill resource not found on classpath")

        driver.writeFileInContainer(skillGuestPath, skillContent)
        println("[xcvb] Deployed display control skill to $skillGuestPath")
        return skillGuestPath
    }

    companion object {
        val VIDEO_STREAMING_PORT = ContainerPort(8765)

        /** Default path where the xcvb skill file is deployed inside containers. */
        const val SKILL_GUEST_PATH = "/home/agent/.skills/xcvb-display-control.md"
    }
}

data class WindowRect(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

data class WindowInfo(
    val id: String,
    val title: String,
    val rect: WindowRect,
    val pid: String,
)

/**
 * Build FFmpeg command line for live MP4 recording optimized for early playback.
 *
 * Keeping keyframes at 1-second cadence prevents 20-50s startup delays where
 * fragmented MP4 playback waits for the next keyframe-based fragment.
 *
 * Note: x264 defaults to keyint=250 (25s at 10fps). We must override keyint
 * explicitly via x264 params so fragmented MP4 can emit 1-second fragments.
 */
internal fun buildFfmpegLiveRecordingCommand(
    display: String,
    outputPath: String,
    videoWidth: Int = 3840,
    videoHeight: Int = 2160,
    frameRate: Int = 10,
    keyframeIntervalSeconds: Int = 1,
): List<String> {
    require(frameRate > 0) { "frameRate must be > 0, got: $frameRate" }
    require(keyframeIntervalSeconds > 0) {
        "keyframeIntervalSeconds must be > 0, got: $keyframeIntervalSeconds"
    }
    val gopSize = frameRate * keyframeIntervalSeconds

    return listOf(
        "ffmpeg", "-nostdin", "-y",
        "-f", "x11grab", "-video_size", "${videoWidth}x${videoHeight}",
        "-framerate", frameRate.toString(), "-i", display,
        "-c:v", "libx264",
        "-preset", "ultrafast",
        "-tune", "zerolatency",
        "-crf", "28",
        "-pix_fmt", "yuv420p",
        "-r", frameRate.toString(),
        "-g", gopSize.toString(),
        "-keyint_min", gopSize.toString(),
        "-x264-params", "keyint=$gopSize:min-keyint=$gopSize:scenecut=0:rc-lookahead=0",
        "-movflags", "frag_keyframe+empty_moov+default_base_moof",
        "-frag_duration", "1000000",
        "-flush_packets", "1",
        "-fflags", "+flush_packets",
        outputPath,
    )
}
