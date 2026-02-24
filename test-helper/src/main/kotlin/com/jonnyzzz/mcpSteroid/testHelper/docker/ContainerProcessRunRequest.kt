/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.docker

import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessRunRequestBase
import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessRunRequestBuilderBase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.time.Duration

open class ContainerProcessRunRequest(
    parent: ProcessRunRequestBase,
    val detach: Boolean,
    val workingDirInContainer: String?,
    //TODO: push it up to generic process, allow removal
    val extraEnvVars: Map<String, String>,
) : ProcessRunRequestBase(parent) {

    constructor(parent: ContainerProcessRunRequest) : this(
        parent,
        detach = parent.detach,
        workingDirInContainer = parent.workingDirInContainer,
        extraEnvVars = parent.extraEnvVars.toMap(),
    )

    companion object
}

fun ContainerProcessRunRequest.Companion.builder() = ContainerProcessRunRequestBuilder()

open class ContainerProcessRunRequestBuilder<R : ContainerProcessRunRequestBuilder<R>> : ProcessRunRequestBuilderBase<R>() {
    var detach: Boolean = false
    var workingDirInContainer: String? = null
    var extraEnvVars: Map<String, String> = mutableMapOf()

    open fun workingDirInContainer(workingDirInContainer: String?) = apply { this.workingDirInContainer = workingDirInContainer }

    open fun extraEnv(env: Map<String, String>) = apply { this.extraEnvVars = env }
    open fun extraEnv(key: String, value: String) = apply { this.extraEnvVars += key to value }

    open fun detached() = detach(true)
    open fun detach(detach: Boolean) = apply { this.detach = detach }

    override fun build(): ContainerProcessRunRequest {
        val parent = super.build()
        return ContainerProcessRunRequest(parent, detach, workingDirInContainer, extraEnvVars)
    }
}


@Suppress("DATA_CLASS_COPY_VISIBILITY_WILL_BE_CHANGED_WARNING", "DataClassPrivateConstructor")
data class RunContainerProcessRequest private constructor(
    val workingDirInContainer: String?,
    val extraEnvVars: Map<String, String>,
    val args: List<String> = listOf(),
    val logPrefix: String? = null,
    val description: String? = null,
    val quietly: Boolean = false,
    val detach: Boolean = false,
    val timeout: Duration = Duration.ofSeconds(30),
    val stdin: Flow<ByteArray> = emptyFlow(),
    val secretPatterns: List<String> = listOf(),
) {
    companion object {
        operator fun invoke() : RunContainerProcessRequest = RunContainerProcessRequest()
    }

    fun workingDirInContainer(workingDirInContainer: String?) = copy(workingDirInContainer = workingDirInContainer)
    fun extraEnvVars(extraEnvVars: Map<String, String>) = copy(extraEnvVars = extraEnvVars)
    fun args(args: List<String> = listOf()) = copy(args = args)
    fun args(vararg args: String) = args(args.toList())
    fun logPrefix(logPrefix: String? = null) = copy(logPrefix = logPrefix)
    fun description(description: String? = null) = copy(description = description)
    fun quietly(quietly: Boolean = false) = copy(quietly = quietly)
    fun quietly() = quietly(true)
    fun detach(detach: Boolean = false) = copy(detach = detach)
    fun detached() = detach(true)

    fun timeout(timeout: Duration = Duration.ofSeconds(30)) = copy(timeout = timeout)

    fun stdin(stdin: Flow<ByteArray> = emptyFlow()) = copy(stdin = stdin)

    fun secretPatterns(secretPatterns: List<String> = listOf()) = copy(secretPatterns = secretPatterns)
}
