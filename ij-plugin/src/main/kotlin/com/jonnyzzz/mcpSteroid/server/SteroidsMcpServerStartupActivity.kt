/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.jonnyzzz.mcpSteroid.demo.DemoModeService
import com.jonnyzzz.mcpSteroid.updates.analyticsBeacon
import com.jonnyzzz.mcpSteroid.updates.UpdateChecker

/**
 * Startup activity that ensures the MCP server is started when the IDE opens.
 * The server is an application-level service, so we just need to access it
 * to ensure it's initialized.
 */
class SteroidsMcpServerStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        // Accessing the service triggers initialization if not already done
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        ServerUrlWriter.getInstance().writeServerUrlToUserHome(server.mcpUrl)
        IdeaDescriptionWriter.getInstance().writeDescriptionFile(project, server.mcpUrl)

        UpdateChecker.getInstance().startUpdates()

        DemoModeService.getInstance(project).startDemoNotifications()

        analyticsBeacon.runHeartbeat()
        analyticsBeacon.capture("plugin_startup_per_project")
    }
}
