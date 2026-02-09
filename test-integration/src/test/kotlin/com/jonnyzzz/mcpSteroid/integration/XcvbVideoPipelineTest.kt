/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

class XcvbVideoPipelineTest {
    @Test
    fun `ffmpeg live recording command uses frequent keyframes for fast startup`() {
        val args = buildFfmpegLiveRecordingCommand(
            display = ":99",
            outputPath = "/tmp/out.mp4",
            frameRate = 10,
            keyframeIntervalSeconds = 1,
        )

        assertEquals("10", argValue(args, "-g"), "GOP should be 1 second at 10fps")
        assertEquals("10", argValue(args, "-keyint_min"), "Minimum keyframe interval should match GOP")
        assertEquals("0", argValue(args, "-sc_threshold"), "Scene-cut keyframes should be disabled for deterministic fragments")
        assertEquals("expr:gte(t,n_forced*1)", argValue(args, "-force_key_frames"))
        assertEquals(
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

        assertTrue(
            script.contains("Watingin for video to start..."),
            "Viewer should render waiting text before stream playback"
        )
        assertTrue(
            script.contains("class=\"moving-brand\""),
            "Viewer should include moving MCP Steroid branding"
        )
        assertTrue(
            script.contains("<video id=\"video\" autoplay muted controls"),
            "Viewer should expose native video controls"
        )
        assertTrue(
            script.contains("id=\"speedSelect\""),
            "Viewer should expose playback speed selector"
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
