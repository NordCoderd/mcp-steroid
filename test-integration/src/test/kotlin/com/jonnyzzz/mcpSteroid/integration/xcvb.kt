/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration

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
) {
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
            listOf("Xvfb", DISPLAY, "-screen", "0", "3840x2160x24", "-ac"),
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
            listOf(
                "ffmpeg", "-nostdin", "-y",
                "-f", "x11grab", "-video_size", "3840x2160",
                "-framerate", "10", "-i", DISPLAY,
                "-c:v", "libx264", "-preset", "ultrafast", "-crf", "28",
                "-pix_fmt", "yuv420p",
                "-movflags", "frag_keyframe+empty_moov",
                "-flush_packets", "1",
                videoInternalPath,
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
            listOf("node", "/usr/local/bin/video-server.js", videoInternalPath, VIDEO_STREAMING_PORT.containerPort.toString(), runId),
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

    /**
     * Start a background thread that opens the live video stream in the default browser
     * once the streaming server is ready. This provides live screen output during the test
     * on macOS.
     *
     * The video is served by the Node.js streaming server inside the container,
     * exposed to the host via Docker port mapping. This avoids the Docker Desktop
     * virtiofs flush issue where mounted volume files are not readable until closed.
     *
     * No-op on non-macOS platforms or if the streaming port is not mapped.
     */
    fun startLiveVideoPreview() {
        if (System.getProperty("os.name")?.contains("Mac", ignoreCase = true) != true) {
            println("[VIDEO] Not on macOS, skipping live preview")
            return
        }

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

                println("[TEST VIDEO] Opening dashboard: $dashboardUrl")
                ProcessBuilder("open", dashboardUrl).start()
            }
        }

        lifetime.registerCleanupAction {
            thread.interrupt()
        }
    }

    companion object {
        val VIDEO_STREAMING_PORT = ContainerPort(8765)
    }
}
