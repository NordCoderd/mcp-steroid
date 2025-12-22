/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase

fun BasePlatformTestCase.setSystemPropertyForTest(name: String, value: String) {
    val oldValue = System.setProperty(name, value)
    Disposer.register(testRootDisposable, Disposable {
        if (oldValue != null) {
            System.setProperty(name, oldValue)
        } else {
            System.clearProperty(name)
        }
    })
}

fun BasePlatformTestCase.setServerPortProperties() {
    // Bind MCP server to 0.0.0.0 so Docker containers can reach it via host.docker.internal
    setSystemPropertyForTest("mcp.steroids.server.host", "0.0.0.0")
    // Use fixed port for tests
    setSystemPropertyForTest("mcp.steroids.server.port", "17820")
    // Disable review mode for tests
    setSystemPropertyForTest("mcp.steroids.review.mode", "NEVER")
}

@Suppress("UnusedReceiverParameter")
fun BasePlatformTestCase.resolveDockerUrl(): String {
    // Docker on macOS runs in a VM, so localhost inside container != host's localhost
    // Use host.docker.internal to access the host from inside Docker
    val mcpUrl = McpTestUtil.getSseUrlIfRunning()
    val dockerUrl = mcpUrl.replace("localhost", "host.docker.internal")
        .replace("127.0.0.1", "host.docker.internal")
    println("[TEST] MCP URL: $mcpUrl -> Docker URL: $dockerUrl")
    return dockerUrl
}

