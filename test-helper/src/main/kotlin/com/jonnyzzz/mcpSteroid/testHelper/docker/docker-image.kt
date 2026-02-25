/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.docker

import com.jonnyzzz.mcpSteroid.testHelper.process.RunProcessRequest
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.process.startProcess
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.createTempFile

data class ImageDriver(
    val imageId: String,
    val logPrefix: String,
) {
    val imageIdToLog get() = imageId.take(10)

    val imageSha256 get() = "sha256:$imageId"

    override fun toString(): String {
        return "ImageDriver(imageId='$imageIdToLog', logPrefix='$logPrefix')"
    }

    fun newRunProcessRequest() = RunProcessRequest()
        .logPrefix(logPrefix)
}

/**
 * Build a Docker image and return its content-addressable image ID (sha256:...).
 *
 * @param buildArgs Extra `--build-arg KEY=VALUE` entries (e.g. `BASE_IMAGE` for derived images)
 * @return The image ID in `sha256:<hex>` format
 */
fun buildDockerImage(
    logPrefix: String,
    dockerfilePath: File,
    timeoutSeconds: Long,
    buildArgs: Map<String, String> = emptyMap(),
    quietly: Boolean = false,
): ImageDriver {
    require(dockerfilePath.exists() && dockerfilePath.isFile) {
        "File does not exist: $dockerfilePath"
    }

    val nowDate = DateTimeFormatter.ISO_DATE.format(LocalDateTime.now())
    val iidFile = createTempFile("docker-iid", ".txt").toFile()
    try {
        val command = buildList {
            add("docker")
            add("build")

            @Suppress("SpellCheckingInspection")
            add("--iidfile")
            add(iidFile.absolutePath)

            for ((k, v) in buildArgs + ("CACHE_BUST" to nowDate)) {
                add("--build-arg")
                add("$k=$v")
            }

            add(".")
        }

        RunProcessRequest()
            .logPrefix(logPrefix)
            .command(command)
            .description("Build Docker image $dockerfilePath")
            .workingDir(dockerfilePath.parentFile)
            .timeoutSeconds(timeoutSeconds)
            .quietly(quietly)
            .startProcess()
            .assertExitCode(0) { "Failed to build Docker image.\n$stderr" }

        val imageId = iidFile.readText().trim()
        require(imageId.startsWith("sha256:")) {
            @Suppress("SpellCheckingInspection")
            "Unexpected image ID format from --iidfile: $imageId"
        }

        println("[$logPrefix] Docker image built $imageId")
        return ImageDriver(
            imageId = imageId.removePrefix("sha256:").trim(),
            logPrefix = logPrefix,
        )
    } finally {
        iidFile.delete()
    }
}


/**
 * Tag an existing Docker image with a new name.
 *
 * @param tag Target tag (e.g. `mcp-steroid-ide-base-test:latest`)
 */
fun ImageDriver.tagDockerImage(tag: String) : ImageDriver {
    newRunProcessRequest()
        .command("docker", "tag", imageSha256, tag)
        .description("Tag Docker image $imageIdToLog as $tag")
        .quietly()
        .startProcess()
        .assertExitCode(0) { "Failed to tag Docker image $imageIdToLog as $tag: $stderr" }

    println("Tagged image $imageId → $tag")
    return copy(imageId = tag)
}
