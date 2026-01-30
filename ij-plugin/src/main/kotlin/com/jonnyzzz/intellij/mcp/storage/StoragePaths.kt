/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.storage

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.registry.Registry
import java.nio.file.Path

/**
 * Central service for managing MCP storage paths.
 *
 * Default storage location: {project}/.idea/mcp-steroid/
 * Execution folders: {storage}/{execution-id}/
 *
 * Registry keys for customization:
 * - mcp.steroid.storage.path - Override storage folder path (empty = .idea/mcp-steroid)
 */
@Service(Service.Level.PROJECT)
class StoragePaths(private val project: Project) {

    /**
     * The base directory for MCP execution storage.
     * Default: {project}/.idea/mcp-steroid/
     *
     * Can be overridden via registry key "mcp.steroid.storage.path".
     */
    val baseDir: Path
        get() {
            val customPath = Registry.stringValue("mcp.steroid.storage.path")
            return if (customPath.isNotBlank()) {
                Path.of(customPath)
            } else {
                val basePath = project.basePath
                    ?: throw IllegalStateException("Project has no base path")
                Path.of(basePath, ".idea", "mcp-steroid")
            }
        }

    companion object {
        fun getInstance(project: Project): StoragePaths = project.service()
    }
}

inline val Project.storagePaths: StoragePaths get() = service()
