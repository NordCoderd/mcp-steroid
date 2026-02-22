/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.docker

import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessResult

interface ContainerProcessRunner {

    fun runInContainer(request: ContainerProcessRunRequest): ProcessResult = TODO("")

    fun runInContainer(
        args: List<String>,
        workingDir: String? = null,
        timeoutSeconds: Long = 30,
        extraEnvVars: Map<String, String> = emptyMap(),
        quietly: Boolean = false,
    ): ProcessResult = runInContainer(
        ContainerProcessRunRequest
            .builder()
            .command(args)
            .workingDirInContainer(workingDir)
            .timeoutSeconds(timeoutSeconds)
            .quietly(quietly)
            .build()
    )

    fun withSecretPattern(secretPattern: String): ContainerProcessRunner
}
