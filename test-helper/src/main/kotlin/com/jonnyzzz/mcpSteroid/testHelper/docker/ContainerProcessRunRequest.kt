/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.docker

import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessRunRequestBase
import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessRunRequestBuilderBase

open class ContainerProcessRunRequest(
    parent: ProcessRunRequestBase,
    val workingDirInContainer: String?,
    //TODO: push it up to generic process, allow removal
    val extraEnvVars: Map<String, String>,
) : ProcessRunRequestBase(parent) {
    companion object
}

fun ContainerProcessRunRequest.Companion.builder() = ContainerProcessRunRequestBuilder()

open class ContainerProcessRunRequestBuilder<R : ContainerProcessRunRequestBuilder<R>> : ProcessRunRequestBuilderBase<R>() {
    var workingDirInContainer: String? = null
    var extraEnvVars: Map<String, String> = mutableMapOf()

    open fun workingDirInContainer(workingDirInContainer: String?) = apply { this.workingDirInContainer = workingDirInContainer }

    open fun extraEnv(env: Map<String, String>) = apply { this.extraEnvVars = env }
    open fun extraEnv(key: String, value: String) = apply { this.extraEnvVars += key to value }

    override fun build(): ContainerProcessRunRequest {
        val parent = super.build()
        return ContainerProcessRunRequest(parent, workingDirInContainer, extraEnvVars)
    }
}
