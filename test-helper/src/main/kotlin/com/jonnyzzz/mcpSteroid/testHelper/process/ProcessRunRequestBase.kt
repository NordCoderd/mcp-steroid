/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.process

import java.io.ByteArrayInputStream
import java.io.InputStream

open class ProcessRunRequestBase(
    val command: List<String>,
    val description: String,
    val timeoutSeconds: Long,
    val quietly: Boolean,
    val stdin: InputStream,
) {
    constructor(parent: ProcessRunRequestBase) : this(
        command = parent.command,
        description = parent.description,
        timeoutSeconds = parent.timeoutSeconds,
        quietly = parent.quietly,
        stdin = parent.stdin,
    )

    companion object
}

fun ProcessRunRequestBase.Companion.builder() = ProcessRunRequestBuilderBase()

open class ProcessRunRequestBuilderBase<R : ProcessRunRequestBuilderBase<R>> {
    protected var command: List<String>? = null
    protected var description: String? = null
    protected var timeoutSeconds: Long = 30
    protected var quietly: Boolean = false
    protected var stdin: InputStream = ByteArrayInputStream(ByteArray(0))

    protected fun apply(action: R.() -> Unit): R {
        @Suppress("UNCHECKED_CAST")
        val r = this as R
        action(r)
        return r
    }

    open fun command(command: List<String>) = apply { this.command = command }
    open fun command(builder: MutableList<String>.() -> Unit) = command(buildList(builder))
    open fun command(vararg command: String) = command(command.toList())

    open fun description(description: String) = apply { this.description = description }

    open fun timeoutSeconds(timeoutSeconds: Long) = apply { this.timeoutSeconds = timeoutSeconds }

    open fun quietly(quietly: Boolean) = apply { this.quietly = quietly }
    open fun quietly() = quietly(true)

    open fun stdin(stdin: InputStream) = apply { this.stdin = stdin }
    open fun stdin(stdin: ByteArray) = stdin(stdin.inputStream())
    open fun stdin(stdin: String) = stdin(stdin.toByteArray())

    open fun build() = ProcessRunRequestBase(
        command = command ?: error("command is required"),
        description = description ?: error("description is required"),
        timeoutSeconds = timeoutSeconds,
        quietly = quietly,
        stdin = stdin,
    )
}
