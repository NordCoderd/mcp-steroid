/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.demo

import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import com.jonnyzzz.mcpSteroid.storage.ExecutionId
import java.time.Instant

/**
 * Topic for execution events. Listeners can subscribe to receive
 * notifications about MCP execution lifecycle.
 */
val EXECUTION_EVENTS_TOPIC: Topic<ExecutionEventListener> = Topic.create(
    "MCP Steroid Execution Events",
    ExecutionEventListener::class.java
)

/**
 * Listener interface for execution events.
 * Subscribe via application message bus to receive notifications.
 */
interface ExecutionEventListener {
    fun onExecutionStarted(event: ExecutionEvent.Started) {}
    fun onExecutionProgress(event: ExecutionEvent.Progress) {}
    fun onExecutionOutput(event: ExecutionEvent.Output) {}
    fun onExecutionCompleted(event: ExecutionEvent.Completed) {}
}

/**
 * Sealed class representing execution lifecycle events.
 */
sealed class ExecutionEvent {
    abstract val executionId: ExecutionId
    abstract val timestamp: Instant

    data class Started(
        override val executionId: ExecutionId,
        override val timestamp: Instant,
        val taskId: String,
        val reason: String,
        val project: Project
    ) : ExecutionEvent()

    data class Progress(
        override val executionId: ExecutionId,
        override val timestamp: Instant,
        val message: String
    ) : ExecutionEvent()

    data class Output(
        override val executionId: ExecutionId,
        override val timestamp: Instant,
        val message: String
    ) : ExecutionEvent()

    data class Completed(
        override val executionId: ExecutionId,
        override val timestamp: Instant,
        val success: Boolean,
        val errorMessage: String? = null
    ) : ExecutionEvent()
}

/**
 * State of an active execution for tracking in the overlay.
 */
data class ExecutionState(
    val executionId: ExecutionId,
    val taskId: String,
    val reason: String,
    val project: Project,
    val startTime: Instant,
    val status: ExecutionStatus,
    val recentLines: List<String>
)

enum class ExecutionStatus { RUNNING, COMPLETED, FAILED }
