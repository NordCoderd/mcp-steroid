/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.review

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * Per-project settings for MCP Steroid, stored in .idea/mcp-steroid.xml.
 * Commit this file to share settings with your team, or add it to .gitignore to keep them local.
 */
@State(
    name = "McpSteroidProjectSettings",
    storages = [Storage("mcp-steroid.xml")]
)
@Service(Service.Level.PROJECT)
class McpSteroidProjectSettings : PersistentStateComponent<McpSteroidProjectSettings.State> {

    data class State(var alwaysAllow: Boolean = false)

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    var alwaysAllow: Boolean
        get() = myState.alwaysAllow
        set(value) { myState.alwaysAllow = value }

    companion object {
        fun getInstance(project: Project): McpSteroidProjectSettings = project.service()
    }
}
