/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.docker

import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessResult
import com.jonnyzzz.mcpSteroid.testHelper.process.RunProcessRequest
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.process.startProcess
import java.io.File

fun ContainerDriver.mkdirs(guestPath: String): ProcessResult {
    return runInContainer(ContainerProcessRunRequest
        .builder()
        .command("mkdir", "-p", guestPath)
        .description("Create directory $guestPath in the container")
        .quietly()
        .build())
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


fun ContainerDriver.writeFileInContainer(
    containerPath: String,
    content: String,
    executable: Boolean = false,
) {
    val parentDir = containerPath.substringBeforeLast('/')
    if (parentDir.isNotEmpty()) {

        runInContainer(
            ContainerProcessRunRequest.builder()
                .command("mkdir", "-p", parentDir)
                .description("mkdir $parentDir")
                .timeoutSeconds(5)
                .quietly()
                .build()
        )
            .assertExitCode(0)
    }

    runInContainer(
        ContainerProcessRunRequest.builder()
            .command("bash", "-c", "cat > $containerPath << 'FILE_EOF'\n$content\nFILE_EOF")
            .description("Write content to $containerPath")
            .timeoutSeconds(5)
            .quietly()
            .build()
    )
        .assertExitCode(0)

    if (executable) {
        runInContainer(
            ContainerProcessRunRequest.builder()
                .command("chmod", "+x", containerPath)
                .description("chmod +x $containerPath")
                .timeoutSeconds(5)
                .quietly()
                .build()
        )
            .assertExitCode(0)
    }
}
