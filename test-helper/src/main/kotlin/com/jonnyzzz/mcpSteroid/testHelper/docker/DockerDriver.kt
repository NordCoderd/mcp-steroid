/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.docker

import com.jonnyzzz.mcpSteroid.testHelper.escapeShellArgs
import com.jonnyzzz.mcpSteroid.testHelper.process.*
import java.io.File

class DockerDriver(
    val workDir: File,
    val logPrefix: String,
    val secretPatterns: List<String> = emptyList(),
    val environmentVariables: Map<String, String> = emptyMap(),
) {
    fun withSecretPattern(secretPattern: String): DockerDriver {
        return DockerDriver(
            workDir,
            logPrefix,
            (secretPatterns + secretPattern).distinct(),
            environmentVariables,
        )
    }

    fun withEnv(key: String, value: String): DockerDriver {
        return DockerDriver(
            workDir,
            logPrefix,
            secretPatterns,
            (environmentVariables + (key to value)).toSortedMap(),
        )
    }

    val runProcessTemplate get() = RunProcessRequest()
        .withLogPrefix(logPrefix)
        .withSecretPatterns(secretPatterns)
        .workingDir(workDir)
        .timeoutSeconds(30)

    //TODO: return StartedProcess!
    fun runInContainer(
        request: DockerProcessRunRequest
    ): ProcessResult {
        val shellCommand = escapeShellArgs(request.command)

        val command = buildList {
            add("docker")
            add("exec")
            if (request.detach) add("--detach")
            (environmentVariables + request.extraEnvVars).forEach { (key, value) ->
                add("-e")
                add("$key=$value")
            }
            request.workingDirInContainer?.let {
                add("-w")
                add(it)
            }
            add(request.containerId)
            add("bash")
            add("-c")
            add(shellCommand)
        }

        return runProcessTemplate
            .command(command)
            .description(request.description)
            .timeoutSeconds(request.timeoutSeconds)
            .quietly(request.quietly)
            .startProcess()
            .awaitForProcessFinish()
    }
}
