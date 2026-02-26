/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.LocalFileSystem
import com.jonnyzzz.mcpSteroid.demo.DemoModeService
import com.jonnyzzz.mcpSteroid.updates.analyticsBeacon
import com.jonnyzzz.mcpSteroid.updates.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Startup activity that ensures the MCP server is started when the IDE opens.
 * The server is an application-level service, so we just need to access it
 * to ensure it's initialized.
 */
class SteroidsMcpServerStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        // Server is already started by SteroidsMcpServerAppLifecycleListener at IDE startup.
        // startServerIfNeeded() is idempotent so it's safe to call here too as a fallback.
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        ServerUrlWriter.getInstance().writeServerUrlToUserHome(server.mcpUrl)
        IdeaDescriptionWriter.getInstance().writeDescriptionFile(project, server.mcpUrl)

        UpdateChecker.getInstance().startUpdates()

        DemoModeService.getInstance(project).startDemoNotifications()

        openInitialFile(project)

        analyticsBeacon.runHeartbeat()
        analyticsBeacon.capture("plugin_startup_per_project")
    }

    /**
     * Open README.md on project startup so agents and users can orient themselves immediately.
     * Falls back to the first text file found in the project if README.md is absent.
     *
     * Uses [LocalFileSystem.refreshAndFindFileByPath] so the VFS content is loaded from disk —
     * files created outside IntelliJ's file watcher (e.g. via git clone) may otherwise have
     * an empty content cache, resulting in a blank editor.
     */
    private suspend fun openInitialFile(project: Project) {
        val basePath = project.basePath ?: return

        val readmeFile = LocalFileSystem.getInstance().refreshAndFindFileByPath("$basePath/README.md")
        val fileToOpen = if (readmeFile != null && readmeFile.exists()) {
            readmeFile
        } else {
            val firstTextFile = File(basePath).walkTopDown()
                .filter { it.isFile && isTextFile(it) }
                .firstOrNull()
            firstTextFile?.let { LocalFileSystem.getInstance().refreshAndFindFileByPath(it.absolutePath) }
        }

        if (fileToOpen != null) {
            withContext(Dispatchers.EDT) {
                FileEditorManager.getInstance(project).openFile(fileToOpen, false)
            }
        }
    }

    private fun isTextFile(file: File): Boolean {
        val ext = file.extension.lowercase()
        return ext in TEXT_EXTENSIONS
    }

    companion object {
        private val TEXT_EXTENSIONS = setOf(
            "md", "txt", "rst", "adoc",
            "kt", "kts", "java", "py", "js", "ts", "go", "rs", "rb", "cpp", "c", "h",
            "xml", "json", "yaml", "yml", "toml", "properties", "gradle",
        )
    }
}
