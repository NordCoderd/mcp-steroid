/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.docker

import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessResult
import com.jonnyzzz.mcpSteroid.testHelper.process.StartedProcess
import java.time.Duration

interface ContainerProcessRunner {
    fun startProcessInContainer(
        request: ExecContainerProcessRequest
    ): StartedProcess

    fun runInContainer(
        args: List<String>,
        workingDir: String? = null,
        timeoutSeconds: Long = 30,
        extraEnvVars: Map<String, String> = emptyMap(),
        quietly: Boolean = false,
    ): ProcessResult {
        val req = ExecContainerProcessRequest()
            .args(args)
            .workingDirInContainer(workingDir)
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .quietly(quietly)
            .extraEnv(extraEnvVars)
            .description("In container: ${args.joinToString(" ")}")

        return startProcessInContainer(req).awaitForProcessFinish()
    }
}
