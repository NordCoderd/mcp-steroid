/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.server

import com.jonnyzzz.intellij.mcp.mcp.McpServerCore
import com.jonnyzzz.intellij.mcp.mcp.McpSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

/**
 * Progress reporter that sends MCP progress notifications.
 * Throttles messages to at most once per second.
 */
class McpProgressReporter(
    private val server: McpServerCore,
    private val session: McpSession,
    private val progressToken: JsonElement
) : ProgressReporter {
    private val messageChannel = Channel<ProgressMessage>(Channel.CONFLATED)
    private val stepCounter = AtomicLong(0)

    fun CoroutineScope.startThrottledSender() {
        launch {
            messageChannel.consumeAsFlow()
                .sample(1.seconds)
                .collect { message ->
                    sendProgressNotification(message)
                }
        }
    }

    override fun report(message: String, total: Long?) {
        val step = stepCounter.incrementAndGet()
        val progressMessage = ProgressMessage(
            step = step,
            total = total,
            message = message
        )

        // Non-blocking send - will drop oldest if channel is full (CONFLATED)
        messageChannel.trySend(progressMessage)
    }

    private fun sendProgressNotification(message: ProgressMessage) {
        server.sendProgress(
            session = session,
            progressToken = progressToken,
            progress = message.step.toDouble(),
            total = message.total?.toDouble(),
            message = message.message
        )
    }

    internal fun close() {
        messageChannel.close()
    }

    private data class ProgressMessage(
        val step: Long,
        val total: Long?,
        val message: String
    )
}
