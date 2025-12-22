/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp

import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.time.Duration.Companion.seconds

class CliIntegrationCommonTest : BasePlatformTestCase() {
    override fun setUp() {
        setServerPortProperties()
        return super.setUp()
    }

    private fun llmSession(): DockerSession {
        return DockerSession.startDockerSession(
            testRootDisposable,
            "ubuntu-cli"
        )
    }

    fun testHostAvailability(): Unit = timeoutRunBlocking(180.seconds) {
        val session = llmSession()

        session.runInContainer(
            "curl",
            "-v", "-X", "POST",
            "-H", "Content-Type: application/json",
            "-H", "Accept: application/json",
            "-d",
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","clientInfo":{"name":"test","version":"1.0"},"capabilities":{}}}""",

            resolveDockerUrl()
        )
            .assertExitCode(0, "curl to MCP")
            .assertNoErrorsInOutput("curl to MCP")
            .assertOutputContains(
                "jsonrpc",
                "\"protocolVersion\":\"2025-06-18\"",
                message = "curl to MCP")
    }
}
