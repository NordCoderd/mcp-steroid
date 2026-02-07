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
        //TODO: pump logs from container
        //TODO: monitor host is started without timeout.
        return proc
    }

    fun startVideoRecording(): RunningContainerProcess {
        val videoPath = "$videoDirInContainer/recording.mp4"
        println("[xcvb] Starting video recording to ${driver.mapGuestPathToHostPath(videoPath)}...")
        val proc = driver.runInContainerDetached(
            listOf(
                "ffmpeg", "-f", "x11grab", "-video_size", "3840x2160",
                "-framerate", "10", "-i", DISPLAY,
                "-c:v", "libx264", "-preset", "ultrafast", "-crf", "28",
                videoPath,
            ),
            name = "ffmpeg",
        )
        lifetime.registerCleanupAction {
            //TODO: map the path back to the host!
            println("Check out screen recording at ${driver.mapGuestPathToHostPath(videoPath)}")
            proc.kill("TERM")
        }
        return proc
    }

    /**
     * Start periodic screenshot capture (one PNG per second).
     * Screenshots are saved to the mounted screenshots directory for live inspection.
     */
    private fun startScreenshotCapture(): RunningContainerProcess {
        println("[xcvb] Starting periodic screenshot capture...")
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
        println("[xcvb] Starting fluxbox...")
        val proc = driver.runInContainerDetached(
            listOf("fluxbox"),
            name = "fluxbox",
        )
        return proc
    }
}
