/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration

import java.io.File


private fun readFilePathFromSystemProperties(key: String): File {
    val path = System.getProperty(key)
        ?: error("$key system property not set — run via Gradle")
    val file = File(path)
    require(file.exists()) { "Path nor not found: $file, from system properties: $key" }
    return file
}

object IdeTestFolders {
    val pluginZip = readFilePathFromSystemProperties("test.integration.plugin.zip")
    val downloadedIdea = readFilePathFromSystemProperties("test.integration.idea.archive")
    val dockerDir = readFilePathFromSystemProperties("test.integration.docker")
    val testOutputDir = readFilePathFromSystemProperties("test.integration.testOutput")
}

/**
 * Start a background thread that watches the video file and opens it with QuickTime
 * once it reaches a minimum size (ffmpeg has started writing). This provides
 * live screen output during the test on macOS.
 *
 * No-op on non-macOS platforms or if video file does not appear within timeout.
 */
fun startLiveVideoPreview(videoFile: File): Thread? {
    if (System.getProperty("os.name")?.contains("Mac", ignoreCase = true) != true) {
        println("[VIDEO] Not on macOS, skipping live preview")
        return null
    }

    val thread = Thread({
        try {
            val deadline = System.currentTimeMillis() + 60_000 // wait up to 60s for video to start
            while (System.currentTimeMillis() < deadline) {
                if (videoFile.exists() && videoFile.length() > 32_768) { // 32KB = ffmpeg started writing
                    println("[VIDEO] Opening live preview: $videoFile")
                    ProcessBuilder("open", videoFile.absolutePath).start()
                    return@Thread
                }
                Thread.sleep(2000)
            }
            println("[VIDEO] Video file did not reach minimum size within timeout")
        } catch (e: InterruptedException) {
            // Thread interrupted, exit silently
        } catch (e: Exception) {
            println("[VIDEO] Error in live preview thread: ${e.message}")
        }
    }, "video-preview")
    thread.isDaemon = true
    thread.start()
    return thread
}
