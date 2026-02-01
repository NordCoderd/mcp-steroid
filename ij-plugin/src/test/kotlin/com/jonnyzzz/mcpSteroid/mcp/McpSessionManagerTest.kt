/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.mcp

import com.intellij.testFramework.common.timeoutRunBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class McpSessionManagerTest {
    @Test
    fun `test forgetAllSessionsForTest keeps sessions open`() = timeoutRunBlocking(10.seconds) {
        val manager = McpSessionManager()
        val session = manager.createSession()
        manager.createSession()

        val forgotten = manager.forgetAllSessionsForTest()

        assertEquals("Should forget all sessions", 2, forgotten)
        assertEquals("Manager should be empty after forget", 0, manager.getSessionCount())

        val notification = JsonRpcNotification(method = "notifications/test")
        session.sendNotification(notification)
        val received = withTimeoutOrNull(2.seconds) { session.notifications().first() }

        assertNotNull("Session should remain open after forgetAllSessionsForTest", received)
    }
}
