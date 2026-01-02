/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.execution

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.jonnyzzz.intellij.mcp.mcp.ContentItem
import com.jonnyzzz.intellij.mcp.mcp.ToolCallResult
import com.jonnyzzz.intellij.mcp.mcp.builder
import com.jonnyzzz.intellij.mcp.review.ReviewManager
import com.jonnyzzz.intellij.mcp.server.ExecCodeParams
import com.jonnyzzz.intellij.mcp.server.McpProgressReporter
import com.jonnyzzz.intellij.mcp.server.NoOpProgressReporter
import com.jonnyzzz.intellij.mcp.server.SkillReference
import com.jonnyzzz.intellij.mcp.storage.ExecutionId
import com.jonnyzzz.intellij.mcp.storage.ImageMessage
import com.jonnyzzz.intellij.mcp.storage.executionStorage
import kotlinx.coroutines.*

interface ExecutionResultBuilder {
    val isFailed: Boolean
    fun logMessage(message: String)
    fun logProgress(message: String)
    fun logImage(mimeType: String, data: String, fileName: String)
    fun logException(message: String, throwable: Throwable)
    fun reportFailed(message: String)
}

/**
 * Manages script executions for a project.
 * Executions run sequentially in a dedicated coroutine scope.
 */
@Service(Service.Level.PROJECT)
class ExecutionManager(
    private val project: Project,
) : Disposable {
    private val log = thisLogger()

    override fun dispose() = Unit

    suspend fun executeWithProgress(
        exec: ExecCodeParams,
        mcpProgressReporter: McpProgressReporter = NoOpProgressReporter,
    ): ToolCallResult {
        return coroutineScope {
            val executionId = project.executionStorage.writeNewExecution(exec)
            withContext(CoroutineName("mcp-steroid-$executionId")) {
                log.info("Starting execution $executionId-${exec.taskId}-${exec.reason}...")

                val builder = responseBuilder(this, executionId, mcpProgressReporter)
                try {
                    builder.logMessage("execution_id: ${executionId.executionId}\n use it to report feedback: steroid_execute_feedback")

                    val finalResult = project.service<ReviewManager>().requestReview(executionId, exec, builder)
                    if (!finalResult) {
                        yield()
                    }

                    builder.logProgress("Review Result: $finalResult")
                    yield()

                    // Run execution with progress reporting
                    project.scriptExecutor.executeWithProgress(
                        executionId,
                        exec,
                        builder
                    )
                    log.info("Execution $executionId completed")
                } catch (t: Throwable) {
                    log.warn("Unexpected error: ${t.message}", t)
                    builder.logException("Unexpected error", t)
                    builder.reportFailed("Unexpected error")
                }

                if (!builder.isFailed) {
                    project.executionStorage.writeCodeExecutionData(executionId, "success.txt", "Execution successful")
                }

                builder.logMessage(
                    "PRO Tip: Call the 'steroid_execute_feedback' tool of this MCP server and list pain points!"
                )

                // Add MCP resource hints to help agents find documentation
                val skillRef = SkillReference.getInstance()
                builder.logMessage(skillRef.successFooter)

                builder.build()
            }
        }
    }

    private fun responseBuilder(parentScope: CoroutineScope, executionId: ExecutionId, mcpProgress: McpProgressReporter) = object : ExecutionResultBuilder {
        private val responseBuilder = ToolCallResult.builder().setExecutionId(executionId)
        private val innerScope = CoroutineScope(parentScope.coroutineContext + Dispatchers.IO.limitedParallelism(1))
        private var failed = false

        override val isFailed: Boolean
            get() = failed

        fun build() = responseBuilder.build()

        override fun logMessage(message: String) {
            val text = "LOG: $message"
            responseBuilder.addTextContent(text)
            mcpProgress.report(text)
            innerScope.launch {
                project.executionStorage.appendExecutionEvent(executionId, text)
            }
        }

        override fun logProgress(message: String) {
            val text = "PROGRESS: $message"
            responseBuilder.addTextContent(text)
            mcpProgress.report(text)
            innerScope.launch {
                project.executionStorage.appendExecutionEvent(executionId, text)
            }
        }

        override fun logImage(mimeType: String, data: String, fileName: String) {
            responseBuilder.addContent(ContentItem.Image(data = data, mimeType = mimeType))
            innerScope.launch {
                project.executionStorage.appendExecutionEvent(
                    executionId,
                    ImageMessage(fileName = fileName, mimeType = mimeType)
                )
            }
        }

        override fun logException(message: String, throwable: Throwable) {
            val text = "EXCEPTION: $message: ${throwable.message}\n${throwable.stackTraceToString()}"
            responseBuilder.addTextContent(text)
            mcpProgress.report(text)

            // Add error-specific hint with MCP resource hints
            val hint = SkillReference.getInstance().errorHint(throwable.message ?: message)
            responseBuilder.addTextContent("HINT: $hint")

            innerScope.launch {
                project.executionStorage.appendExecutionEvent(executionId, text)
            }
        }

        override fun reportFailed(message: String) {
            val text = "FAILED: $message"
            responseBuilder.addTextContent(text)
            mcpProgress.report(text)
            responseBuilder.markAsError()
            failed = true
            innerScope.launch {
                project.executionStorage.appendExecutionEvent(executionId, text)
                project.executionStorage.writeCodeErrorEvent(executionId, text)
            }
        }
    }
}
