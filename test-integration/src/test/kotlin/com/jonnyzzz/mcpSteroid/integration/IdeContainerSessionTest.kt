/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Integration test for IdeContainerSession infrastructure.
 *
 * Verifies that the Docker container can be built and started,
 * all directories are properly mounted, and the IDE starts successfully.
 */
class IdeContainerSessionTest {
    val lifetime by lazy {
        CloseableStackHost()
    }

    @AfterEach
    fun tearDown() {
       lifetime.closeAllStacks()
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `container starts and IDE becomes ready`() {
        val session = IdeContainerSession.create(
            lifetime,
            "ide-agent",
        )

        val videoPort = session.scope.mapContainerPortToHostPort(XcvbDriver.VIDEO_STREAMING_PORT)
        val mcpPort = session.mcpSteroidHostPort
        println()
        println("=".repeat(60))
        println("  VIDEO DASHBOARD: http://localhost:$videoPort/")
        println("  MCP STEROID:     http://localhost:$mcpPort/")
        println("=".repeat(60))
        println()
    }
}
