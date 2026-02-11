/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.buildFfmpegLiveRecordingCommand
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

class XcvbVideoPipelineTest {
    @Test
    fun `ffmpeg live recording command uses frequent keyframes for fast startup`() {
        val args = buildFfmpegLiveRecordingCommand(
            display = ":99",
            outputPath = "/tmp/out.mp4",
            frameRate = 10,
            keyframeIntervalSeconds = 1,
        )

        Assertions.assertEquals("10", argValue(args, "-g"), "GOP should be 1 second at 10fps")
        Assertions.assertEquals("10", argValue(args, "-keyint_min"), "Minimum keyframe interval should match GOP")
        Assertions.assertEquals("zerolatency", argValue(args, "-tune"), "Encoder should favor low-latency delivery")
        Assertions.assertEquals("1000000", argValue(args, "-frag_duration"), "Fragment duration should be 1 second")
        Assertions.assertEquals(
            "keyint=10:min-keyint=10:scenecut=0:rc-lookahead=0",
            argValue(args, "-x264-params"),
            "x264 GOP settings should force 1-second keyframe cadence"
        )
        Assertions.assertEquals(
            "frag_keyframe+empty_moov+default_base_moof",
            argValue(args, "-movflags"),
            "Fragmented MP4 flags should support early browser playback"
        )
    }

    @Test
    fun `ffmpeg live recording command rejects invalid values`() {
        assertThrows<IllegalArgumentException> {
            buildFfmpegLiveRecordingCommand(
                display = ":99",
                outputPath = "/tmp/out.mp4",
                frameRate = 0,
                keyframeIntervalSeconds = 1,
            )
        }
        assertThrows<IllegalArgumentException> {
            buildFfmpegLiveRecordingCommand(
                display = ":99",
                outputPath = "/tmp/out.mp4",
                frameRate = 10,
                keyframeIntervalSeconds = 0,
            )
        }
    }

    @Test
    fun `video viewer script exposes waiting state branding and playback controls`() {
        val script = readVideoServerScript()

        Assertions.assertTrue(
            script.contains("Waiting for video to start..."),
            "Viewer should render waiting text before stream playback"
        )
        Assertions.assertTrue(
            !script.contains("class=\"moving-brand\""),
            "Viewer should not include moving MCP Steroid text overlay"
        )
        Assertions.assertTrue(
            script.contains("<video id=\"video\" autoplay muted controls"),
            "Viewer should expose native video controls"
        )
        Assertions.assertTrue(
            script.contains("id=\"speedSelect\""),
            "Viewer should expose playback speed selector"
        )
        Assertions.assertTrue(
            script.contains("enterServerDownState"),
            "Viewer should enter a stable shutdown state when server becomes unavailable"
        )
        Assertions.assertTrue(
            script.contains("window.close()"),
            "Viewer should attempt to auto-close the browser window when server is down"
        )
        Assertions.assertTrue(
            !script.contains("Watingin for video to start..."),
            "Viewer should not contain the old typo"
        )
    }

    private fun argValue(args: List<String>, option: String): String {
        val index = args.indexOf(option)
        check(index >= 0) { "Option '$option' not found in args: ${args.joinToString(" ")}" }
        check(index + 1 < args.size) { "Option '$option' has no value in args: ${args.joinToString(" ")}" }
        return args[index + 1]
    }

    private fun readVideoServerScript(): String {
        val dockerRoot = System.getProperty("test.integration.docker")?.let(::File)
            ?: File("test-integration/src/test/docker")
        val scriptFile = dockerRoot.resolve("ide-agent/video-server.js")
        check(scriptFile.isFile) { "Video server script not found: ${scriptFile.absolutePath}" }
        return scriptFile.readText()
    }
}