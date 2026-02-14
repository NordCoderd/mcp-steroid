/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import java.io.File

data class NpxProxyCommand(
    val command: String,
    val args: List<String>,
)

fun ContainerDriver.prepareNpxProxyForUrl(
    ideMcpUrl: String,
    userHome: String,
): NpxProxyCommand {
    val hostDistFile = File("npx/dist/index.js")
    require(hostDistFile.isFile) {
        "NPX proxy dist is missing at ${hostDistFile.path}. Run: npm --prefix npx run build"
    }

    val guestDir = "/tmp/mcp-steroid-npx"
    val guestIndex = "$guestDir/index.js"
    val guestConfig = "$guestDir/proxy.json"
    val markerPath = "$userHome/.1.mcp-steroid"

    mkdirs(guestDir)
    copyToContainer(hostDistFile, guestIndex)

    writeFileInContainer(
        guestConfig,
        """
        {
          "homeDir": "$userHome",
          "scanIntervalMs": 1000,
          "allowHosts": ["host.docker.internal", "localhost", "127.0.0.1"],
          "upstreamTimeoutMs": 15000
        }
        """.trimIndent()
    )

    writeFileInContainer(
        markerPath,
        """
        $ideMcpUrl

        IntelliJ MCP Steroid Server
        URL: $ideMcpUrl
        """.trimIndent() + "\n"
    )

    return NpxProxyCommand(
        command = "node",
        args = listOf(guestIndex, "--config", guestConfig)
    )
}
