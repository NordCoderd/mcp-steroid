/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

import com.jonnyzzz.mcpSteroid.filter.OutputFilter
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerProcessRunner
import com.jonnyzzz.mcpSteroid.testHelper.docker.startDockerSession

abstract class AIAgentCompanion<T : Any>(val dockerFileBase: String) {
    abstract val displayName: String
    abstract val outputFilter: OutputFilter

    protected abstract fun readApiKey(): String

    fun create(lifetime: CloseableStack): T {
        println("[DOCKER-${dockerFileBase.uppercase()}] Session created in container")
        val session = ContainerDriver.startDockerSession(lifetime, dockerFileBase)
        return create(session)
    }

    fun create(session: ContainerProcessRunner): T {
        println("[DOCKER-${dockerFileBase.uppercase()}] Session created in container")
        val apiKey = readApiKey()
        return createImpl(session.withSecretPattern(apiKey), apiKey)
    }

    protected abstract fun createImpl(session: ContainerProcessRunner, apiKey: String): T
}
