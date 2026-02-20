/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.review

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.util.registry.Registry
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows

class McpSteroidProjectConfigurable(private val project: Project) : BoundConfigurable("MCP Steroid") {

    override fun createPanel(): DialogPanel {
        val settings = McpSteroidProjectSettings.getInstance(project)
        val registryIsNever = Registry.stringValue(McpSteroidProjectSettings.REVIEW_MODE_REGISTRY_KEY) == "NEVER"

        return panel {
            row {
                text(
                    "Automatically approve all code blocks that an AI Agent sends to the MCP Steroid plugin to execute. " +
                    "The code shown in the editor during review is an example of what you approve.<br><br>" +
                    "There is no guarantee on what an AI Agent will want to execute. " +
                    "There is a chance it may harm your system or gain profit."
                )
            }

            row {
                checkBox("Automatically approve all MCP Steroid executions for this project")
                    .bindSelected(settings::alwaysAllow)
            }.topGap(com.intellij.ui.dsl.builder.TopGap.MEDIUM)

            if (registryIsNever) {
                row {
                    comment(
                        "Registry override is active: <code>mcp.steroid.review.mode=NEVER</code>. " +
                        "All executions are approved globally regardless of the setting above."
                    )
                }
            }
        }
    }
}
