/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.docker

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.time.Duration

data class ExecContainerProcessRequest(
    val workingDirInContainer: String? = null,
    val user: String? = null,
    val extraEnvVars: Map<String, String> = mapOf(),
    val args: List<String> = listOf(),
    val logPrefix: String? = null,
    val description: String? = null,
    val quietly: Boolean = false,
    val detach: Boolean = false,
    val interactive: Boolean = false,
    val timeout: Duration = Duration.ofSeconds(30),
    val stdin: Flow<ByteArray> = emptyFlow(),
    val secretPatterns: List<String> = listOf(),
) {
    fun workingDirInContainer(workingDirInContainer: String?) = copy(workingDirInContainer = workingDirInContainer)
    fun user(user: String?) = copy(user = user)
    fun extraEnv(extraEnvVars: Map<String, String>) = copy(extraEnvVars = extraEnvVars)
    fun addEnv(key: String, value: String) = extraEnv(this.extraEnvVars + (key to value))
    fun args(args: List<String> = listOf()) = copy(args = args)
    fun args(vararg args: String) = args(args.toList())
    fun logPrefix(logPrefix: String? = null) = copy(logPrefix = logPrefix)
    fun description(description: String? = null) = copy(description = description)
    fun quietly(quietly: Boolean = false) = copy(quietly = quietly)
    fun quietly() = quietly(true)
    fun detach(detach: Boolean = false) = copy(detach = detach)
    fun detached() = detach(true)
    fun interactive() = copy(interactive = true)

    fun timeout(timeout: Duration = Duration.ofSeconds(30)) = copy(timeout = timeout)
    fun timeoutSeconds(timeoutSeconds: Long) = timeout(Duration.ofSeconds(timeoutSeconds))

    fun stdin(stdin: Flow<ByteArray> = emptyFlow()) = copy(stdin = stdin)

    fun secretPatterns(secretPatterns: List<String> = listOf()) = copy(secretPatterns = secretPatterns)
    fun secretPatterns(vararg secretPatterns: String) = secretPatterns(secretPatterns.toList())
}
