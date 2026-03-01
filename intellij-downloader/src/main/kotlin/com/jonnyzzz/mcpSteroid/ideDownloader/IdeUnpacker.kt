/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ideDownloader

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream

/**
 * Unpacks a .tar.gz archive into the specified directory using Apache Commons Compress.
 *
 * Idempotent: if [unpackDir] already contains a child directory, unpacking is skipped.
 * Only .tar.gz archives are supported; .dmg and .exe formats fail with a clear error.
 */
fun unpackTarGz(archiveFile: File, unpackDir: File) {
    require(archiveFile.exists()) { "Archive file does not exist: $archiveFile" }

    val name = archiveFile.name.lowercase()
    require(name.endsWith(".tar.gz") || name.endsWith(".tgz")) {
        "Unsupported archive format: ${archiveFile.name}. Only .tar.gz archives can be unpacked. " +
                ".dmg (macOS) and .exe (Windows) archives are not supported."
    }

    val existingChild = unpackDir.listFiles()?.firstOrNull { it.isDirectory }
    if (existingChild != null) {
        println("[IDE-DOWNLOAD] Already unpacked: $existingChild")
        return
    }

    unpackDir.mkdirs()
    println("[IDE-DOWNLOAD] Unpacking ${archiveFile.name} -> $unpackDir")

    var entryCount = 0
    var lastPrinted = System.currentTimeMillis()

    TarArchiveInputStream(
        GzipCompressorInputStream(
            BufferedInputStream(FileInputStream(archiveFile))
        )
    ).use { tar ->
        var entry = tar.nextEntry
        while (entry != null) {
            val outputFile = File(unpackDir, entry.name)

            // Prevent zip-slip
            require(outputFile.canonicalPath.startsWith(unpackDir.canonicalPath)) {
                "Archive entry escapes target directory: ${entry.name}"
            }

            if (entry.isDirectory) {
                outputFile.mkdirs()
            } else {
                outputFile.parentFile?.mkdirs()
                outputFile.outputStream().use { out ->
                    tar.copyTo(out)
                }
                // Preserve executable permission
                if (entry.mode and 0b001_000_000 != 0) {
                    outputFile.setExecutable(true, false)
                }
            }

            // Handle symlinks
            if (entry.isSymbolicLink) {
                outputFile.delete()
                val linkTarget = File(outputFile.parentFile, entry.linkName)
                java.nio.file.Files.createSymbolicLink(outputFile.toPath(), linkTarget.toPath())
            }

            entryCount++
            val now = System.currentTimeMillis()
            if (now - lastPrinted >= 5_000) {
                println("[IDE-DOWNLOAD] Unpacking: $entryCount entries extracted...")
                lastPrinted = now
            }

            entry = tar.nextEntry
        }
    }

    println("[IDE-DOWNLOAD] Unpacked $entryCount entries to $unpackDir")
}
