/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.vfs

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.time.Duration.Companion.seconds

class VfsRefreshServiceTest : BasePlatformTestCase() {
    override fun runInDispatchThread(): Boolean = false

    fun testRefreshInvalidatesDeletedFile(): Unit = timeoutRunBlocking(30.seconds) {
        val basePath = project.basePath ?: error("Project basePath is missing")
        val dirPath = Paths.get(basePath, "build", "vfs-refresh-test")
        Files.createDirectories(dirPath)

        val filePath = dirPath.resolve("stale.txt")
        Files.writeString(filePath, "stale")
        val vFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(filePath.toString())
            ?: error("Failed to locate test file in VFS")
        assertTrue("File should exist before deletion", vFile.exists())

        Files.deleteIfExists(filePath)

        project.vfsRefreshService.refresh("VfsRefreshServiceTest")

        val refreshed = LocalFileSystem.getInstance().refreshAndFindFileByPath(filePath.toString())
        assertTrue(
            "Deleted file should be invalidated after refresh",
            refreshed == null || !refreshed.exists() || !refreshed.isValid
        )
    }
}
