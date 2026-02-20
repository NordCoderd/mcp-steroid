/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import com.jonnyzzz.mcpSteroid.aiAgents.StdioMcpCommand
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.prepareNpxProxyForUrl

class NpxSteroidDriver(
    val npxCommand: StdioMcpCommand,
) {

    companion object
}

fun NpxSteroidDriver.Companion.prepareNpxSteroid(
    session: ContainerDriver,
    mcpSteroidDriver: McpSteroidDriver,
): StdioMcpCommand {
    val userHome = "/home/temp-${System.currentTimeMillis()}"
    return session.prepareNpxProxyForUrl(mcpSteroidDriver.guestMcpUrl, userHome)
}

