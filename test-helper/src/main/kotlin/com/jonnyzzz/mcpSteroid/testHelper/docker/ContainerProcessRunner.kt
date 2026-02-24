/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.docker

import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessResult

interface ContainerProcessRunner {

    fun runInContainer(request: ContainerProcessRunRequest): ProcessResult {
        return runInContainer(
            request.command,
            request.workingDirInContainer,
            request.timeoutSeconds,
            request.extraEnvVars,
            request.quietly
        )
    }

    fun runInContainer(
        args: List<String>,
        workingDir: String? = null,
        timeoutSeconds: Long = 30,
        extraEnvVars: Map<String, String> = emptyMap(),
        quietly: Boolean = false,
    ): ProcessResult {
        val req = ContainerProcessRunRequest
            .builder()
            .command(args)
            .workingDirInContainer(workingDir)
            .timeoutSeconds(timeoutSeconds)
            .quietly(quietly)
            .description(args.joinToString(" ").take(80))
            .build()

        return runInContainer(req)
    }
}
