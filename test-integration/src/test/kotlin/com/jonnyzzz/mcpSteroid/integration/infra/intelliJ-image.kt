/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import com.jonnyzzz.mcpSteroid.testHelper.docker.DockerDriver
import java.io.File
import java.nio.file.Files.createLink
import kotlin.io.path.exists

private const val BASE_DOCKER_CONTEXT = "ide-base"
private const val BASE_IMAGE_NAME = "mcp-steroid-ide-base-test"

fun buildIdeImage(dockerFileBase: String, imageName: String): DockerDriver {
    buildSharedBaseImage()
    val contextDir = prepareContext("docker-$dockerFileBase", BASE_DOCKER_CONTEXT, dockerFileBase)
    linkIdeArchive(contextDir)
    val scope = DockerDriver(contextDir, "IDE-AGENT")
    scope.buildDockerImage(
        imageName = imageName,
        dockerfilePath = File(contextDir, "Dockerfile"),
        timeoutSeconds = 900,
    )

    return scope
}

private fun buildSharedBaseImage() {
    val baseContext = prepareContext("docker-$BASE_DOCKER_CONTEXT", BASE_DOCKER_CONTEXT)
    val baseScope = DockerDriver(baseContext, "IDE-AGENT")
    baseScope.buildDockerImage(
        imageName = BASE_IMAGE_NAME,
        dockerfilePath = File(baseContext, "Dockerfile"),
        timeoutSeconds = 900,
    )
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

private fun linkIdeArchive(contextDir: File) {
    // Hard-link large IDE archive to avoid copying ~1GB file.
    // Falls back to copy if hard link fails (e.g. cross-filesystem).
    val ideArchivePath = IdeTestFolders.ideTarGz
    val ideDest = File(contextDir, "ide.tar.gz").toPath()
    if (ideDest.exists()) return

    try {
        createLink(ideDest, ideArchivePath.toPath())
    } catch (_: Exception) {
        println("[IDE-AGENT] Hard link failed, copying IDE archive...")
        ideArchivePath.copyTo(File(contextDir, "ide.tar.gz"), overwrite = true)
    }
}
