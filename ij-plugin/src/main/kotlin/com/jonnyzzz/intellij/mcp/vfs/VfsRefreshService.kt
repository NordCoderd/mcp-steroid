/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.vfs

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Service(Service.Level.PROJECT)
class VfsRefreshService(
    private val project: Project,
) {
    private val log = thisLogger()

    suspend fun refresh(reason: String) {
        withContext(Dispatchers.IO) {
            val targets = buildRefreshTargets()
            log.debug("Refreshing VFS for $reason (${targets.size} root(s))")
            if (targets.isNotEmpty()) {
                VfsUtil.markDirtyAndRefresh(false, true, true, *targets.toTypedArray())
            } else {
                VirtualFileManager.getInstance().syncRefresh()
            }
        }
    }

    private fun buildRefreshTargets(): List<VirtualFile> {
        val roots = ProjectRootManager.getInstance(project).contentRoots.toList()
        val basePath = project.basePath ?: return roots
        val baseDir = LocalFileSystem.getInstance().refreshAndFindFileByPath(basePath)
        return (roots + listOfNotNull(baseDir)).distinct()
    }
}

inline val Project.vfsRefreshService: VfsRefreshService get() = service()
