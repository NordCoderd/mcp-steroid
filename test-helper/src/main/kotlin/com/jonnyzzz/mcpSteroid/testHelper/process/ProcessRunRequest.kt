/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.process

import java.io.File

/**
 * Request configuration for running a process.
 * Use the builder pattern to construct instances.
 */
open class ProcessRunRequest(
    base: ProcessRunRequestBase,
    val workingDir: File,
) : ProcessRunRequestBase(base) {
    companion object
}

fun <R : ProcessRunRequestBuilder<R>> ProcessRunRequestBuilder<R>.startProcess(processRunner: ProcessRunner) =
    processRunner.startProcess(build())

fun <R : ProcessRunRequestBuilder<R>> ProcessRunRequestBuilder<R>.runProcess(processRunner: ProcessRunner) =
    startProcess(processRunner).awaitForProcessFinish()

fun ProcessRunRequest.Companion.builder() = ProcessRunRequestBuilder()

open class ProcessRunRequestBuilder<R : ProcessRunRequestBuilder<R>> : ProcessRunRequestBuilderBase<R>() {
    protected var workingDir: File? = null

    open fun workingDir(workingDir: File?) = apply { this.workingDir = workingDir }

    override fun build(): ProcessRunRequest {
        val base = super.build()
        return ProcessRunRequest(
            base = base,
            workingDir = workingDir ?: error("workingDir is required"),
        )
    }
}
