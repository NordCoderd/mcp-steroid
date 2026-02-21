/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.docker

import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessRunRequest
import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessRunRequestBuilder
import java.io.File
import java.io.InputStream

open class ContainerProcessRunRequest(
    parent: ProcessRunRequest
) : ProcessRunRequest(parent) {

    companion object
}

class ContainerProcessRunRequestBuilder : ProcessRunRequestBuilder() {
    override fun build(): ContainerProcessRunRequest {
        val parent = super.build()
        return ContainerProcessRunRequest(parent)
    }
}
