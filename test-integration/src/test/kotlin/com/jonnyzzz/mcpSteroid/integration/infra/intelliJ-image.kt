/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import com.jonnyzzz.mcpSteroid.testHelper.docker.DockerDriver
import java.io.File
import java.nio.file.Files.createLink
import kotlin.io.path.exists

private const val BASE_DOCKER_CONTEXT = "ide-base"
private const val BASE_IMAGE_NAME = "mcp-steroid-ide-base-test"

// JVM-level guard: the shared base image content never changes within a test run,
// so build it only once regardless of how many parallel test containers are started.
@Volatile private var baseImageBuilt = false
private val baseImageLock = Any()

/**
 * Builds the IDE Docker image for [dockerFileBase] and returns a [DockerDriver]
 * scoped to its build context.
 *
 * The build context directory is derived from [imageName], so parallel calls
 * with different image names (which include a unique suffix from the caller)
 * each get their own isolated context directory — no races, no `deleteRecursively`
 * stomping on a concurrent build.
 */
fun buildIdeImage(dockerFileBase: String, imageName: String, ideArchive: File): DockerDriver {
    buildSharedBaseImage()
    // Derive a per-build context dir from the full image name.
    // Since imageName already carries a unique suffix (e.g. "ide-agent-test-a1b2c3d4"),
    // this guarantees each concurrent build gets its own isolated directory.
    val contextDir = prepareContext("docker-$imageName", BASE_DOCKER_CONTEXT, dockerFileBase)
    linkIdeArchive(contextDir, ideArchive)
    val scope = DockerDriver(contextDir, "IDE-AGENT")
    scope.buildDockerImage(
        imageName = imageName,
        dockerfilePath = File(contextDir, "Dockerfile"),
        timeoutSeconds = 900,
    )

    return scope
}

private fun buildSharedBaseImage() {
    if (baseImageBuilt) return  // fast path — no locking needed after first build
    synchronized(baseImageLock) {
        if (baseImageBuilt) return  // double-checked locking
        val baseContext = prepareContext("docker-$BASE_DOCKER_CONTEXT", BASE_DOCKER_CONTEXT)
        val baseScope = DockerDriver(baseContext, "IDE-AGENT")
        baseScope.buildDockerImage(
            imageName = BASE_IMAGE_NAME,
            dockerfilePath = File(baseContext, "Dockerfile"),
            timeoutSeconds = 900,
        )
        baseImageBuilt = true
    }
}

private fun prepareContext(contextName: String, vararg dockerContexts: String): File {
    val contextDir = File(IdeTestFolders.testOutputDir, contextName)
    contextDir.deleteRecursively()
    contextDir.mkdirs()
    println("[IDE-AGENT] Build context: $contextDir")
    dockerContexts.forEach { IdeTestFolders.copyDockerFiles(it, contextDir) }

    val topLevelFiles = contextDir.listFiles()
        ?.sortedBy { it.name }
        ?.joinToString("") { "\n - ${it.name}" + if (it.isDirectory) "/" else "" }
        ?: ""
    println("[IDE-AGENT] Prepared context:$topLevelFiles")
    return contextDir
}

private fun linkIdeArchive(contextDir: File, ideArchive: File) {
    // Hard-link large IDE archive to avoid copying ~1GB file.
    // Falls back to copy if hard link fails (e.g. cross-filesystem).
    val ideDest = File(contextDir, "ide.tar.gz").toPath()
    if (ideDest.exists()) return

    try {
        createLink(ideDest, ideArchive.toPath())
    } catch (_: Exception) {
        println("[IDE-AGENT] Hard link failed, copying IDE archive...")
        ideArchive.copyTo(File(contextDir, "ide.tar.gz"), overwrite = true)
    }
}
