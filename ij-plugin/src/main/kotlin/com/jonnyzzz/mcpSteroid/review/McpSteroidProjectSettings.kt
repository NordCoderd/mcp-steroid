/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.review

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.registry.Registry

/**
 * Per-project settings for MCP Steroid, stored in .idea/mcp-steroid.xml.
 * Commit this file to share settings with your team, or add it to .gitignore to keep them local.
 *
 * This service also centralises the "should review?" decision, taking into account
 * both the global registry override and the per-project always-allow flag.
 */
@Service(Service.Level.PROJECT)
@State(
    name = "McpSteroidProjectSettings",
    storages = [Storage("mcp-steroid.xml")]
)
class McpSteroidProjectSettings(private val project: Project) :
    SimplePersistentStateComponent<McpSteroidProjectSettings.State>(State()) {

    class State : BaseState() {
        var alwaysAllow by property(false)
    }

    var alwaysAllow: Boolean
        get() = state.alwaysAllow
        set(value) { state.alwaysAllow = value }

    /** Returns true when execution should proceed without showing the review panel. */
    fun isAutoApproved(): Boolean {
        if (Registry.stringValue(REVIEW_MODE_REGISTRY_KEY) == "NEVER") return true
        return state.alwaysAllow
    }

    companion object {
        const val REVIEW_MODE_REGISTRY_KEY = "mcp.steroid.review.mode"

        fun getInstance(project: Project): McpSteroidProjectSettings = project.service()
    }
}
