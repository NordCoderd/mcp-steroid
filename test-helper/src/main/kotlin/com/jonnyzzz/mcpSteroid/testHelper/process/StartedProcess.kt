/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.process

import kotlinx.coroutines.flow.Flow

data class PID(val pid: String)

fun Process.PID() = PID(this.pid().toString())

enum class ProcessStreamType {
    STDOUT, STDERR, INFO
}

data class ProcessStreamLine(
    val type: ProcessStreamType,
    val line: String,
)

interface StartedProcess : ProcessResult {
    /**
     * Returns flow of all messages of process output, each time
     */
    val messagesFlow: Flow<ProcessStreamLine>

    /**
     * Forcibly destroys the underlying process.
     */
    fun destroyForcibly()
}
