/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.execution

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.jonnyzzz.intellij.mcp.storage.ExecutionId
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Implementation of McpScriptContext.
 *
 * Key features:
 * - Has a Disposable that scripts can use to register cleanup
 * - Rejects output operations after disposed
 * - Supports progress reporting via MCP notifications (throttled to 1/sec)
 * - No coroutineScope property - suspend functions get scope implicitly
 */
class McpScriptContextImpl(
    override val project: Project,
    override val params: JsonElement,
    val executionId: ExecutionId,
    override val disposable: Disposable,
    private val resultBuilder: ExecutionResultBuilder,
) : McpScriptContext {
    private val log = Logger.getInstance(McpScriptContextImpl::class.java)

    private val objectMapper = ObjectMapper().apply {
        enable(SerializationFeature.INDENT_OUTPUT)
        // Don't fail on empty beans
        disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
    }.writerWithDefaultPrettyPrinter()

    private val disposed = AtomicBoolean(false).also {
        Disposer.register(disposable) { it.set(true) }
    }

    override val isDisposed: Boolean
        get() = disposed.get()

    private fun checkDisposed() {
        if (disposed.get()) {
            throw IllegalStateException("Context has been disposed - cannot perform output operations")
        }
    }

    override fun println(vararg values: Any?) {
        checkDisposed()
        resultBuilder.logMessage(values.joinToString(" ") { it?.toString() ?: "null" })
    }

    override fun printException(message: String, throwable: Throwable) {
        checkDisposed()
        resultBuilder.logException(message, throwable)
    }

    override fun printJson(obj: Any?) {
        checkDisposed()
        try {
            val jsonString = when (obj) {
                null -> "null"
                is String -> obj
                else -> objectMapper.writeValueAsString(obj)
            }
            resultBuilder.logMessage(jsonString)
        } catch (e: Exception) {
            resultBuilder.logMessage("Failed to serialize to JSON: ${e.message}")
        }
    }

    override fun progress(message: String) {
        checkDisposed()
        log.info("[$executionId] progress: $message")
        // Report progress directly without storing in output
        resultBuilder.logProgress(message)
    }

    override suspend fun waitForSmartMode() {
        checkDisposed()
        if (!DumbService.isDumb(project)) return

        log.info("[$executionId] Waiting for indexing to complete...")
        resultBuilder.logProgress("Waiting for indexing to complete...")

        suspendCancellableCoroutine { cont ->
            fun waitForSmart() {
                if (disposed.get()) {
                    cont.cancel()
                    return
                }
                DumbService.getInstance(project).smartInvokeLater {
                    if (disposed.get()) {
                        cont.cancel()
                    } else if (DumbService.isDumb(project)) {
                        waitForSmart()
                    } else {
                        cont.resume(Unit)
                    }
                }
            }
            waitForSmart()
        }
    }
}
