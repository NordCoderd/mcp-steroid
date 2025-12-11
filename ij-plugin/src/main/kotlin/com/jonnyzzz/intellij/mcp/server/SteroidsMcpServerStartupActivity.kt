/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.server

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

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

        // Write the server URL to this project's .idea folder
        server.writeServerUrlToProject(project)
    }
}
