/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.startDockerSession

abstract class AIAgentCompanion<T : Any>(val dockerFileBase: String) {
    protected abstract fun readApiKey(): String

    fun create(lifetime: CloseableStack): T {
        println("[DOCKER-${dockerFileBase.uppercase()}] Session created in container")
        val session = ContainerDriver.startDockerSession(lifetime, dockerFileBase)
        return create(session)
    }

    fun create(session: ContainerDriver): T {
        println("[DOCKER-${dockerFileBase.uppercase()}] Session created in container")
        val apiKey = readApiKey()
        return createImpl(session.withSecretPattern(apiKey), apiKey)
    }

    protected abstract fun createImpl(session: ContainerDriver, apiKey: String): T
}
