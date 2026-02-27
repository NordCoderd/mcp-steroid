/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import java.io.File
import java.net.HttpURLConnection
import java.net.URI

const val INTELLIJ_MASTER_GIT_CLONE_LINUX_ZIP_URL =
    "https://buildserver.labs.intellij.net/repository/download/ijplatform_master_Service_PrepareGitClonePerOS_Linux/.lastSuccessful/ultimate-git-clone-linux.zip"
const val INTELLIJ_MASTER_REPO_URL = "https://github.com/JetBrains/intellij-community.git"
const val INTELLIJ_MASTER_BRANCH = "master"

private const val INTELLIJ_REPO_CACHE_SUBDIR = "intellij-master-git-clone"
private const val INTELLIJ_GIT_CLONE_ZIP_NAME = "ultimate-git-clone-linux.zip"

fun intelliJGitCloneZipInCache(cacheDir: File): File =
    File(File(cacheDir, INTELLIJ_REPO_CACHE_SUBDIR), INTELLIJ_GIT_CLONE_ZIP_NAME)

/**
 * Ensure the IntelliJ master git-clone archive exists under repo-cache.
 *
 * Cache layout:
 * - {cacheDir}/intellij-master-git-clone/ultimate-git-clone-linux.zip
 *
 * We intentionally do not re-download when the ZIP already exists: updating in-container via
 * `git fetch` against configured remotes is much cheaper than refetching the full archive.
 */
fun ensureIntelliJGitCloneZipInCache(
    cacheDir: File,
    zipUrl: String = INTELLIJ_MASTER_GIT_CLONE_LINUX_ZIP_URL,
): File {
    val zipFile = intelliJGitCloneZipInCache(cacheDir)
    if (zipFile.isFile) {
        println("[INTELLIJ-GIT] Using cached archive: $zipFile")
        return zipFile
    }

    zipFile.parentFile.mkdirs()
    println("[INTELLIJ-GIT] Downloading IntelliJ git clone ZIP: $zipUrl -> $zipFile")
    downloadFile(zipUrl, zipFile)
    return zipFile
}

private fun downloadFile(url: String, destination: File) {
    val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 30_000
        readTimeout = 30 * 60_000
        instanceFollowRedirects = true
    }

    try {
        val statusCode = connection.responseCode
        if (statusCode !in 200..299) {
            error("Failed downloading $url. HTTP $statusCode")
        }

        val tempFile = File(destination.parentFile, "${destination.name}.tmp")
        try {
            connection.inputStream.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            if (!tempFile.renameTo(destination)) {
                tempFile.copyTo(destination, overwrite = true)
                tempFile.delete()
            }
        } catch (e: Exception) {
            tempFile.delete()
            throw e
        }
    } finally {
        connection.disconnect()
    }
}
