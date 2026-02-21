/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.process.assertNoErrorsInOutput
import com.jonnyzzz.mcpSteroid.testHelper.process.assertOutputContains
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.startDockerSession
import kotlin.time.Duration.Companion.seconds

class CliIntegrationCommonTest : BasePlatformTestCase() {
    val lifetime by lazy {
        CloseableStackHost().apply {
            Disposer.register(testRootDisposable, this::closeAllStacks)
        }
    }
    override fun setUp() {
        setServerPortProperties()
        super.setUp()
    }

    private fun llmSession(): ContainerDriver = ContainerDriver.startDockerSession(lifetime, "ubuntu-cli")

    fun testHostAvailability(): Unit = timeoutRunBlocking(180.seconds) {
        val session = llmSession()

        session.runInContainer(
            listOf(
            "curl",
            "-v", "-X", "POST",
            "-H", "Content-Type: application/json",
            "-H", "Accept: application/json",
            "-d",
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","clientInfo":{"name":"test","version":"1.0"},"capabilities":{}}}""",
            resolveDockerUrl()
            )
        )
            .assertExitCode(0, "curl to MCP")
            .assertNoErrorsInOutput("curl to MCP")
            .assertOutputContains(
                "jsonrpc",
                "\"protocolVersion\":\"2025-11-25\"",
                message = "curl to MCP")
    }
}
