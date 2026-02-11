/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import com.jonnyzzz.mcpSteroid.testHelper.docker.DockerDriver
import java.io.File
import java.nio.file.Files.createLink
import kotlin.io.path.exists


fun buildIdeImage(dockerFileBase: String, imageName: String): DockerDriver {
    val contextDir = File(IdeTestFolders.testOutputDir, "docker-$dockerFileBase")
    contextDir.mkdirs()
    println("[IDE-AGENT] Build context: $contextDir")
    IdeTestFolders.copyDockerFiles(dockerFileBase, contextDir)

    // Hard-link large IDEA archive to avoid copying ~1GB file.
    // Falls back to copy if hard link fails (e.g. cross-filesystem).
    val ideaArchivePath = IdeTestFolders.intelliJTarGz
    val ideaDest = File(contextDir, "idea.tar.gz").toPath()
    //optimization to make sure Docker will not rebuild files
    if (!ideaDest.exists()) {
        try {
            createLink(ideaDest, ideaArchivePath.toPath())
        } catch (_: Exception) {
            println("[IDE-AGENT] Hard link failed, copying IDEA archive...")
            ideaArchivePath.copyTo(File(contextDir, "idea.tar.gz"), overwrite = true)
        }
    }

    val topLevelFiles = contextDir.listFiles()
        ?.sortedBy { it.name }
        ?.joinToString("") { "\n - ${it.name}" + if (it.isDirectory) "/" else "" }
        ?: ""
    println("[IDE-AGENT] Prepared context:$topLevelFiles")

    val scope = DockerDriver(contextDir, "IDE-AGENT")

    scope.buildDockerImage(
        imageName = imageName,
        dockerfilePath = File(contextDir, "Dockerfile"),
        timeoutSeconds = 900,
    )

    return scope
}
