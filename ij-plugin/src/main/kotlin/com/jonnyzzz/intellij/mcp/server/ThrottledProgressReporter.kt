/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.server

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ProgressToken
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

/**
 * Throttled progress reporter that sends progress notifications at most once per second.
 * Uses Kotlin Flow to throttle messages and avoid overwhelming the MCP connection.
 *
 * Note: Progress notifications require MCP client support for progressToken in _meta.
 * Currently, the Server API for sending notifications is under exploration.
 */
class ThrottledProgressReporter(
    private val server: Server,
    private val progressToken: ProgressToken
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

    private suspend fun sendProgressNotification(message: ProgressMessage) {
        // Progress notifications are a protocol feature that requires the Server
        // to have an active notification channel. For now, we just track progress internally.
        // The MCP SDK Server class needs an exposed notification API to fully implement this.
        // TODO: Implement when SDK provides server.notification() or similar API
    }

    fun close() {
        messageChannel.close()
    }

    private data class ProgressMessage(
        val step: Long,
        val total: Long?,
        val message: String
    )
}
