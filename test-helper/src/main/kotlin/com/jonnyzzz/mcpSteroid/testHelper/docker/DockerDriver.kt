/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.docker

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStack
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

    /**
     * Tag an existing Docker image with a new name.
     *
     * @param imageId Source image reference (e.g. `sha256:<hex>` or existing tag)
     * @param tag Target tag (e.g. `mcp-steroid-ide-base-test:latest`)
     */
    fun tagDockerImage(imageId: String, tag: String) {
        runProcessTemplate
            .command("docker", "tag", imageId, tag)
            .description("Tag Docker image as $tag")
            .quietly()
            .startProcess()
            .assertExitCode(0) { "Failed to tag Docker image $imageId as $tag: $stderr" }

        println("[$logPrefix] Tagged image $imageId → $tag")
    }


    //TODO: this should either return container driver, or move outside of here
    fun startContainer(
        lifetime: CloseableStack,
        request: StartContainerRequest,
    ): String {
        val containerId = startContainer2(request)

        // Register normal cleanup action
        lifetime.registerCleanupAction {
            println("[$logPrefix] Stopping and removing container: $containerId")
            killContainer(containerId)
        }


        return containerId
    }

    companion object {
        internal fun parseContainerRunningState(stdout: String): Boolean? {
            return when (stdout.trim().lowercase()) {
                "true" -> true
                "false" -> false
                else -> null
            }
        }
    }

    /**
     * Query bridge-network container IP (for example 172.17.x.x).
     * Returns null when inspect output does not contain an address.
     */
    fun queryContainerIp(containerId: String): String? {
        val result = runProcessTemplate
            .command(
                "docker",
                "inspect",
                "-f",
                "{{range .NetworkSettings.Networks}}{{.IPAddress}} {{end}}",
                containerId
            )
            .description("Query container IP")
            .timeoutSeconds(5)
            .quietly()
            .startProcess()
            .assertExitCode(0) { "Failed to query container IP: $stderr" }

        return result.stdout
            .trim()
            .split(Regex("\\s+"))
            .firstOrNull { it.isNotBlank() }
    }

    fun killContainer(containerId: String) {
        runProcessTemplate
            .command("docker", "kill", containerId)
            .description("kill container")
            .timeoutSeconds(10)
            .startProcess()
            .awaitForProcessFinish()

        runProcessTemplate
            .command("docker", "rm", "-f", containerId)
            .description("Remove container")
            .timeoutSeconds(timeoutSeconds = 5)
            .startProcess()
            .awaitForProcessFinish()

        println("[$logPrefix] Container removed successfully")
    }

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
