/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.process

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
}
