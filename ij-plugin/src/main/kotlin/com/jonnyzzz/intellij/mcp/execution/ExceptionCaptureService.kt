/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.execution

import com.intellij.diagnostic.AbstractMessage
import com.intellij.diagnostic.MessagePool
import com.intellij.diagnostic.MessagePoolListener
import com.intellij.ide.plugins.PluginUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Disposer
import com.intellij.util.ExceptionUtil
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captured exception information from the IDE's error system.
 */
data class CapturedIdeException(
    val timestamp: Instant,
    val throwable: Throwable,
    val message: String?,
    val stacktrace: String,
    val pluginId: String?
)

/**
 * Application-level service that captures IDE exceptions via MessagePool.
 *
 * This service registers a MessagePoolListener lazily on first use and emits
 * exceptions to a SharedFlow with no buffer - only active subscribers receive
 * exceptions at the moment they occur.
 *
 * ## Usage
 *
 * ```kotlin
 * val service = service<ExceptionCaptureService>()
 *
 * // Subscribe to exceptions flow during execution
 * service.exceptions.collect { exception ->
 *     // Handle exception
 * }
 * ```
 */
@Suppress("UnstableApiUsage")
@Service(Service.Level.APP)
class ExceptionCaptureService : Disposable {
    private val initialized = AtomicBoolean(false)

    /**
     * SharedFlow of captured IDE exceptions.
     * No replay, no buffer - only delivers to currently subscribed collectors.
     */
    private val _exceptions = MutableSharedFlow<CapturedIdeException>(
        replay = 0,
        extraBufferCapacity = 0
    )

    /**
     * Public flow of captured exceptions.
     * Subscribers only receive exceptions that occur while they are subscribed.
     */
    val exceptions: Flow<CapturedIdeException>
        get() {
            ensureInitialized()
            return _exceptions.asSharedFlow()
        }

    private val listener = object : MessagePoolListener {
        override fun beforeEntryAdded(message: AbstractMessage): Boolean {
            captureException(message)
            // Return true to allow normal processing (show in IDE error dialog)
            return true
        }

        override fun newEntryAdded() {
            // Not needed - we capture in beforeEntryAdded
        }
    }

    private fun ensureInitialized() {
        if (initialized.compareAndSet(false, true)) {
            runCatching { registerListener() }
        }
    }

    private fun registerListener() {
        MessagePool.getInstance().addListener(listener)
        Disposer.register(this) {
            MessagePool.getInstance().removeListener(listener)
        }
    }

    private fun captureException(message: AbstractMessage) {
        val throwable = message.throwable
        val stacktrace = ExceptionUtil.getThrowableText(throwable)

        // Try to find the plugin that caused the exception
        val pluginId = runCatching {
            PluginUtil.getInstance().findPluginId(throwable)?.idString
        }.getOrNull()

        var msg = ""
        message.message?.let {
            msg += "\n$it"
        }
        if (msg.isNotEmpty()) {
            msg += ": "
        }
        throwable.message?.let {
            msg += it
        }

        val captured = CapturedIdeException(
            timestamp = Instant.now(),
            throwable = throwable,
            message = msg,
            stacktrace = stacktrace,
            pluginId = pluginId
        )

        // Try to emit - if no subscribers, the exception is dropped (no buffer)
        _exceptions.tryEmit(captured)
    }

    override fun dispose() = Unit
}
