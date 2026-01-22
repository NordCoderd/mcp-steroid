/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp

import com.intellij.openapi.application.readAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.intellij.mcp.vfs.vfsRefreshService
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.time.Duration.Companion.seconds

class NoTestModeBranchingTest : BasePlatformTestCase() {
    override fun runInDispatchThread(): Boolean = false

    fun testNoIsUnitTestModeUsageInProject(): Unit = timeoutRunBlocking(30.seconds) {
        project.vfsRefreshService.refresh("NoTestModeBranchingTest")
        val repoPath = System.getProperty("user.dir") ?: error("Working directory is missing")
        val srcPath = Paths.get(repoPath, "src").toString()
        val srcRoot = readAction { LocalFileSystem.getInstance().refreshAndFindFileByPath(srcPath) }
            ?: error("Project src directory is missing: $srcPath")
        val kotlinFiles = readAction { collectKotlinFiles(srcRoot) }
        val forbiddenToken = "is" + "UnitTestMode"
        val matches = mutableListOf<String>()

        for (file in kotlinFiles) {
            if (!file.isValid || !file.exists()) continue
            if (!Files.exists(Paths.get(file.path))) continue
            val text = readAction { VfsUtilCore.loadText(file) }
            if (!text.contains(forbiddenToken)) continue
            text.lineSequence().forEachIndexed { index, line ->
                if (line.contains(forbiddenToken)) {
                    matches.add("${file.path}:${index + 1}")
                }
            }
        }

        assertTrue(
            "Forbidden test-only branching found in:\n${matches.joinToString("\n")}",
            matches.isEmpty()
        )
    }

    private fun collectKotlinFiles(root: VirtualFile): List<VirtualFile> {
        val files = mutableListOf<VirtualFile>()
        VfsUtilCore.iterateChildrenRecursively(root, null) { file ->
            if (!file.isDirectory && file.isValid && file.exists()) {
                val ext = file.extension
                if (ext == "kt" || ext == "kts") {
                    files.add(file)
                }
            }
            true
        }
        return files
    }
}
