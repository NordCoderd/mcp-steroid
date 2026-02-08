/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.mcp

import com.intellij.testFramework.common.timeoutRunBlocking
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class McpSessionManagerTest {
    @Test
    fun `test forgetAllSessionsForTest closes and removes all sessions`() = timeoutRunBlocking(10.seconds) {
        val manager = McpSessionManager()
        val session = manager.createSession()
        manager.createSession()

        val forgotten = manager.forgetAllSessionsForTest()

        assertEquals("Should forget all sessions", 2, forgotten)
        assertEquals("Manager should be empty after forget", 0, manager.getSessionCount())

        // Session notification channel should be closed after forgetAllSessionsForTest.
        // Sending a notification to a closed session and trying to receive should
        // yield null (flow completes immediately because the channel is closed).
        session.sendNotification(JsonRpcNotification(method = "notifications/test"))
        val received = withTimeoutOrNull(1.seconds) { session.notifications().firstOrNull() }
        assertNull("Session should be closed after forgetAllSessionsForTest", received)
    }
}
