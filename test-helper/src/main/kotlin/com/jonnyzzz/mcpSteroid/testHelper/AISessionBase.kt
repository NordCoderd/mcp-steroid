/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

import com.jonnyzzz.mcpSteroid.filter.OutputFilter
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerProcessRunner
import com.jonnyzzz.mcpSteroid.testHelper.docker.DockerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.StartContainerRequest
import com.jonnyzzz.mcpSteroid.testHelper.docker.buildDockerImage
import com.jonnyzzz.mcpSteroid.testHelper.docker.startContainerDriver
import java.io.File

abstract class AIAgentCompanion<T : Any>(val dockerFileBase: String) {
    abstract val displayName: String
    abstract val outputFilter: OutputFilter

    protected abstract fun readApiKey(): String

    fun create(lifetime: CloseableStack): T {
        println("[DOCKER-${dockerFileBase.uppercase()}] Session created in container")
        val dockerfilePath = File("src/test/docker/$dockerFileBase/Dockerfile")

        require(dockerfilePath.isFile) { "Docker file $dockerfilePath must exist" }

        val logPrefix = dockerFileBase.uppercase()
        val workDir = createTempDirectory(logPrefix.lowercase())
        lifetime.registerCleanupAction {
            workDir.deleteRecursively()
        }

        val scope = DockerDriver(workDir, logPrefix, listOf<String>())
        val imageId = buildDockerImage(
            logPrefix = logPrefix,
            dockerfilePath,
            timeoutSeconds = 600,
        )

        val session = startContainerDriver(lifetime, scope, StartContainerRequest().image(imageId))
        return create(session)
    }

    fun create(session: ContainerProcessRunner): T {
        println("[DOCKER-${dockerFileBase.uppercase()}] Session created in container")
        val apiKey = readApiKey()
        return createImpl(session, apiKey)
    }

    protected abstract fun createImpl(session: ContainerProcessRunner, apiKey: String): T
}
