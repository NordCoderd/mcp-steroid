/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

import com.jonnyzzz.mcpSteroid.filter.AgentProgressOutputFilter
import com.jonnyzzz.mcpSteroid.filter.filterText
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.StartContainerRequest
import com.jonnyzzz.mcpSteroid.testHelper.docker.buildDockerImage
import com.jonnyzzz.mcpSteroid.testHelper.docker.startDockerContainerAndDispose
import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessResult
import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessResultValue
import com.jonnyzzz.mcpSteroid.testHelper.process.StartedProcess

abstract class AIContainerBase(
    private val session: ContainerDriver,
    private val apiKey: String,
    private val debug: Boolean = false,
    private val workdirInContainer: String,
    override val displayName: String
) : AiAgentSession {
//     = this.ComCompanion.displayName


}

abstract class AIAgentCompanion<T : Any>(val dockerFileBase: String) {
    abstract val displayName: String
    abstract val outputFilter: AgentProgressOutputFilter

    val workdirInContainerDefault = "/home/agent" //TODO

    protected abstract fun readApiKey(): String

    fun create(lifetime: CloseableStack): T {
        val dockerfilePath = ProjectHomeDirectory.requireProjectHomeDirectory()
            .resolve("test-helper/src/main/docker/$dockerFileBase/Dockerfile")
            .toFile()
        require(dockerfilePath.isFile) { "Docker file $dockerfilePath must exist" }

        val imageId = buildDockerImage(
            logPrefix = dockerFileBase.uppercase(),
            dockerfilePath,
            timeoutSeconds = 600,
        )

        val session = startDockerContainerAndDispose(lifetime,
            StartContainerRequest()
                .image(imageId)
        )

        return create(session)
    }

    fun create(session: ContainerDriver): T {
        println("[DOCKER-${dockerFileBase.uppercase()}] Session created in container")
        val apiKey = readApiKey()
        return createImpl(session, apiKey)
    }

    fun StartedProcess.toAiStartedProcess(): AiStartedProcess {
        return object: AiStartedProcess, StartedProcess by this@toAiStartedProcess {
            override val outputFilter: AgentProgressOutputFilter
                get() = this@AIAgentCompanion.outputFilter

            override fun awaitForProcessFinishRaw(): ProcessResult {
                return this@toAiStartedProcess.awaitForProcessFinish()
            }

            override fun awaitForProcessFinish(): ProcessResult {
                val rawResult = awaitForProcessFinishRaw()

                return ProcessResultValue(
                    exitCode = rawResult.exitCode ?: error("Process ${this@toAiStartedProcess} finished with exit code ${rawResult.exitCode}"),
                    stdout = this.outputFilter.filterText(rawResult.stdout),
                    stderr = rawResult.stderr,
                )
            }

            override fun toString(): String {
                return "$displayName-${this@toAiStartedProcess}"
            }
        }
    }


    protected abstract fun createImpl(session: ContainerDriver, apiKey: String): T
}
