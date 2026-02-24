/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.docker

import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessResult
import com.jonnyzzz.mcpSteroid.testHelper.process.RunProcessRequest
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.process.startProcess
import java.io.File

fun ContainerDriver.mkdirs(guestPath: String): ProcessResult {
    emptyMap<String, String>()
    return ContainerProcessRunRequest
        .builder()
        .command("mkdir", "-p", guestPath)
        .description("Create directory $guestPath in the container")
        .quietly()
        .runInContainer(this)
}

fun ContainerDriver.copyFromContainer(containerPath: String, localPath: File) {
    localPath.parentFile?.mkdirs()
    RunProcessRequest()
        .logPrefix(containerId)
        .command("docker", "cp", "$containerId:$containerPath", localPath.absolutePath)
        .description("Copy container:$containerPath to ${localPath.name}")
        .timeoutSeconds(30L)
        .quietly()
        .startProcess()
        .assertExitCode(0) { "Failed to copy to container: $localPath: $stderr" }
}

fun ContainerDriver.copyToContainer(localPath: File, containerPath: String) {
    require(localPath.exists()) { "Local path does not exist: $localPath" }
    RunProcessRequest()
        .logPrefix(containerId)
        .command("docker", "cp", localPath.absolutePath, "$containerId:$containerPath")
        .description("Copy ${localPath.name} to container:$containerPath")
        .timeoutSeconds(120L)
        .quietly()
        .startProcess()
        .assertExitCode(0) { "Failed to copy to container: $localPath: $stderr" }
}

