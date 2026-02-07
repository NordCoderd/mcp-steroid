/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStack
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.RunningContainerProcess
import kotlin.concurrent.thread

class XcvbDriver(
    private val lifetime: CloseableStack,
    driver: ContainerDriver,
    private val videoDirInContainer: String,
) {
    private val DISPLAY = ":99"
    private val driver = driver.withEnv("DISPLAY", DISPLAY)

    fun withDisplay(container: ContainerDriver): ContainerDriver {
        return container.withEnv("DISPLAY", DISPLAY)
    }

    fun startAllServices() {
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
        println("[xcvb] Starting video recording to ${driver.mapGuestPathToHostPath(videoGuestPath)}...")
        val proc = driver.runInContainerDetached(
            listOf(
                "ffmpeg", "-nostdin", "-y",
                "-f", "x11grab", "-video_size", "3840x2160",
                "-framerate", "10", "-i", DISPLAY,
                "-c:v", "libx264", "-preset", "ultrafast", "-crf", "28",
                "-movflags", "frag_keyframe+empty_moov",
                "-flush_packets", "1",
                videoGuestPath,
            ),
        )
        lifetime.registerCleanupAction {
            println("Check out screen recording at ${driver.mapGuestPathToHostPath(videoGuestPath)}")
            proc.kill("INT")
        }

        return proc
    }

    /**
     * Start the Node.js HTTP server that streams the growing MP4 file.
     * The server is baked into the Docker image at /usr/local/bin/video-server.js.
     */
    fun startVideoStreamingServer(): RunningContainerProcess {
        println("[xcvb] Starting video streaming server on container port $VIDEO_STREAMING_PORT...")
        return driver.runInContainerDetached(
            listOf("node", "/usr/local/bin/video-server.js", videoGuestPath, VIDEO_STREAMING_PORT.toString()),
        )
    }

    /**
     * Start periodic screenshot capture (one PNG per second).
     * Screenshots are saved to the mounted screenshots directory for live inspection.
     */
    private fun startScreenshotCapture(): RunningContainerProcess {
        println("[xcvb] Starting periodic screenshot capture to ${driver.mapGuestPathToHostPath(videoGuestPath)}...")
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

        val hostPort = driver.hostPorts[VIDEO_STREAMING_PORT]
        if (hostPort == null) {
            println("[VIDEO] Streaming port $VIDEO_STREAMING_PORT not mapped, skipping live preview")
            return
        }

        val streamUrl = "http://localhost:$hostPort/video.mp4"

        val thread = thread(start = true) {
            runCatching {
                waitFor(35_000L, "Video streaming server ready") {
                    val process = ProcessBuilder("curl", "-s", "-o", "/dev/null", "-w", "%{http_code}", streamUrl)
                        .start()
                    process.waitFor()
                    process.inputStream.bufferedReader().readText().trim() == "200"
                }

                println("[VIDEO] Opening live stream: $streamUrl")
                ProcessBuilder("open", streamUrl).start()
            }
        }

        lifetime.registerCleanupAction {
            thread.interrupt()
        }
    }

    companion object {
        const val VIDEO_STREAMING_PORT = 8765
    }
}
