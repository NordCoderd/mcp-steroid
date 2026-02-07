/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration

import java.io.File
import java.nio.file.Files


private fun readFilePathFromSystemProperties(key: String): File {
    val path = System.getProperty(key)
        ?: error("$key system property not set — run via Gradle")
    val file = File(path)
    require(file.exists()) { "Path nor not found: $file, from system properties: $key" }
    return file
}

object IdeTestFolders {
    val pluginZip = readFilePathFromSystemProperties("test.integration.plugin.zip")
    val intelliJTarGz = readFilePathFromSystemProperties("test.integration.idea.archive")
    val dockerDir = readFilePathFromSystemProperties("test.integration.docker")
    val projectDir = dockerDir
    val testOutputDir = readFilePathFromSystemProperties("test.integration.testOutput")

    fun copyDockerFiles(containerName: String, destinationDir: File) {
        val sourcePath = dockerDir.resolve(containerName)
        require(sourcePath.exists()) { "Directory $containerName already exists" }
        copyRecursively(sourcePath, destinationDir)
    }

    fun copyProjectFiles(containerName: String, destinationDir: File) {
        val sourcePath = dockerDir.resolve(containerName)
        require(sourcePath.exists()) { "Directory $containerName already exists" }
        copyRecursively(sourcePath, destinationDir)
    }
}

fun copyRecursively(source: File, destination: File) {
    if (source.isFile) {
        destination.parentFile.mkdirs()
        Files.copy(source.toPath(), destination.toPath())
        destination.setLastModified(source.lastModified())
        return
    }

    destination.mkdirs()

    val sourceFiles = source.listFiles() ?: error("Failed to list directory $source")

    sourceFiles.forEach { sourceFile ->
        copyRecursively(sourceFile, destination.resolve(sourceFile.name))
    }
}

fun waitFor(timeoutMillie: Long, condition: String = "condition", action: () -> Boolean) {
    println("Waiting $condition for $timeoutMillie ms...")
    val now = System.currentTimeMillis()
    Thread.sleep(50)
    while (System.currentTimeMillis() - now < timeoutMillie) {
        if (runCatching { action() }.getOrNull() == true) {
            return
        }
        Thread.sleep(50)
    }
    throw RuntimeException("Failed waiting for $condition!")
}
