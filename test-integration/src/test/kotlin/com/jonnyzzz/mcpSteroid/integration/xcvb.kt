/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStack
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.RunningContainerProcess

class XcvbContainer(
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
        startScreenshotCapture()
    }

    fun startDisplayServer(): RunningContainerProcess {
        println("[xcvb] Starting Xvfb...")
        val proc = driver.runInContainerDetached(
            listOf("Xvfb", DISPLAY, "-screen", "0", "3840x2160x24", "-ac"),
            name = "xvfb-server",
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
            name = "ffmpeg",
        )
        lifetime.registerCleanupAction {
            println("Check out screen recording at ${driver.mapGuestPathToHostPath(videoGuestPath)}")
            proc.kill("INT")
        }

        while (true) {
            if (videoFile.exists() && videoFile.length() > 1110) {
                println("[VIDEO] Opening live preview: $videoFile")
                break
            }
            Thread.sleep(100)
        }

        return proc
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
            name = "screenshots",
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
            name = "fluxbox",
        )
        return proc
    }

    /**
     * Start a background thread that watches the video file and opens it with QuickTime
     * once it reaches a minimum size (ffmpeg has started writing). This provides
     * live screen output during the test on macOS.
     *
     * No-op on non-macOS platforms or if video file does not appear within timeout.
     */
    fun startLiveVideoPreview() {
        if (System.getProperty("os.name")?.contains("Mac", ignoreCase = true) != true) {
            println("[VIDEO] Not on macOS, skipping live preview")
            return
        }

        val path = videoFile.absolutePath
        println("[VIDEO] Opening live preview: $path")
        ProcessBuilder(
            "osascript",
            "-e", """tell application "QuickTime Player" to open POSIX file "$path"""",
            "-e", """tell application "QuickTime Player" to play document 1""",
        ).start()
    }
}
