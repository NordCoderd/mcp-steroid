/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.review

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.PersistentStateComponent

/**
 * Per-project persistent flag: user has enabled "Always Allow" for this project.
 * When enabled, code review is skipped and executions are auto-approved.
 *
 * Stored in workspace file (not committed to VCS). Controlled by the review panel.
 * The registry key mcp.steroid.review.mode=NEVER overrides this flag.
 */
@Service(Service.Level.PROJECT)
@State(
    name = "McpSteroidAlwaysAllow",
    storages = [Storage(StoragePathMacros.WORKSPACE_FILE)]
)
class AlwaysAllowService : PersistentStateComponent<AlwaysAllowService.State> {
    data class State(var enabled: Boolean = false)

    private var myState = State()

    override fun getState(): State = myState
    override fun loadState(state: State) { myState = state }

    var isEnabled: Boolean
        get() = myState.enabled
        set(value) { myState.enabled = value }
}
