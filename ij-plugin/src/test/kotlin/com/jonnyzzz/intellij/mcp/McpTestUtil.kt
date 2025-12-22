/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.intellij.mcp.execution.ExecutionResultBuilder
import com.jonnyzzz.intellij.mcp.mcp.ContentItem
import com.jonnyzzz.intellij.mcp.mcp.ToolCallResult
import com.jonnyzzz.intellij.mcp.server.ExecCodeParams
import com.jonnyzzz.intellij.mcp.server.SteroidsMcpServer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path

/**
 * Test utilities for MCP server tests.
 * Provides access to the MCP server service in tests.
 */
object McpTestUtil {
    /**
     * Get the SSE URL if the server is running.
     */
    fun getSseUrlIfRunning(): String {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()
        assert(server.port > 0)
        return server.mcpUrl
    }
}

/**
 * Common test implementation of ExecutionResultBuilder.
 * Collects all output for assertions in tests.
 */
class TestResultBuilder : ExecutionResultBuilder {
    val messages = mutableListOf<String>()
    val progressMessages = mutableListOf<String>()
    val exceptions = mutableListOf<Pair<String, Throwable>>()
    private var failed = false
    var failureMessage: String? = null

    override val isFailed: Boolean get() = failed

    override fun logMessage(message: String) {
        messages += message
    }

    override fun logProgress(message: String) {
        progressMessages += message
    }

    override fun logException(message: String, throwable: Throwable) {
        exceptions += message to throwable
    }

    override fun reportFailed(message: String) {
        failed = true
        failureMessage = message
    }

    fun hasAnyOutput(): Boolean {
        return failed || messages.isNotEmpty() || exceptions.isNotEmpty() || progressMessages.isNotEmpty()
    }

    fun hasDaemonDyingError(): Boolean {
        val msg = failureMessage ?: ""
        return msg.contains("Service is dying") || msg.contains("Could not connect to Kotlin compile daemon")
    }

    override fun toString() = buildString {
        appendLine("TestResultBuilder")
        appendLine("failed=$failed")
        messages.forEach { appendLine("MESSAGE: $it") }
        progressMessages.forEach { appendLine("PROGRESS: $it") }
        exceptions.forEach {
            appendLine("EXCEPTION: ${it.first}")
            appendLine(it.second.toString())
            appendLine(it.second.stackTraceToString())
        }
        appendLine("Failure message: $failureMessage")
    }
}

/**
 * Creates ExecCodeParams for tests with sensible defaults.
 */
fun testExecParams(
    code: String,
    taskId: String = "test-task",
    reason: String = "test",
    timeout: Int = 60
) = ExecCodeParams(
    taskId = taskId,
    code = code,
    reason = reason,
    timeout = timeout,
    rawParams = buildJsonObject { }
)


/**
 * Extracts the execution ID from the ToolCallResult's structuredContent.
 * The structuredContent is a JSON object with executionId as a key.
 */
fun getExecutionIdFromResult(result: ToolCallResult): String {
    val executionId = result.content.mapNotNull {
        if (it is ContentItem.Text) {
            val prefix = "Execution ID:"
            if (it.text.startsWith(prefix, ignoreCase = true)) {
                return@mapNotNull it.text.removePrefix(prefix).trim()
            }
        }
        null
    }.distinct().singleOrNull() ?: error("No execution Id in result")
    println("Result: $executionId")
    return executionId
}

