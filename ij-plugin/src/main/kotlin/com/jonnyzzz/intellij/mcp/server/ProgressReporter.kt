/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.server

/**
 * Interface for reporting progress during script execution.
 */
interface ProgressReporter {
    /**
     * Report progress. Implementations may throttle or batch messages.
     */
    fun report(message: String, total: Long? = null)

    companion object {
        /**
         * Create a no-op reporter that doesn't send any notifications.
         */
        fun noOp(): ProgressReporter = NoOpProgressReporter
    }
}

/**
 * No-op implementation that discards all progress messages.
 */
private object NoOpProgressReporter : ProgressReporter {
    override fun report(message: String, total: Long?) = Unit
}
