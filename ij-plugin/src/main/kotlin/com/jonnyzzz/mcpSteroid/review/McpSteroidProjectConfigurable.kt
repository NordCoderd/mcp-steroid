/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.review

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.util.registry.Registry
import com.intellij.ui.dsl.builder.TopGap
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.jonnyzzz.mcpSteroid.server.SteroidsMcpServer
import com.jonnyzzz.mcpSteroid.server.IdeaDescriptionWriter

class McpSteroidProjectConfigurable(private val project: Project) : BoundConfigurable("MCP Steroid") {

    override fun createPanel(): DialogPanel {
        val settings = McpSteroidProjectSettings.getInstance(project)
        val registryIsNever = Registry.stringValue(McpSteroidProjectSettings.REVIEW_MODE_REGISTRY_KEY) == "NEVER"

        return panel {
            group("Code Review") {
                row {
                    checkBox("Automatically approve all MCP Steroid executions for this project")
                        .bindSelected(settings::alwaysAllow)
                        .comment(
                            "Automatically approve all code blocks that an AI Agent sends to the MCP Steroid plugin to execute. " +
                            "The code shown in the editor during review is an example of what you approve.<br>" +
                            "There is no guarantee on what an AI Agent will want to execute. " +
                            "There is a chance it may harm your system or gain profit."
                        )
                }
                if (registryIsNever) {
                    row {
                        comment(
                            "Registry override is active: <code>mcp.steroid.review.mode=NEVER</code>. " +
                            "All executions are approved globally regardless of the setting above."
                        )
                    }
                }
            }

            val connectionInfo = buildConnectionInfo()
            if (connectionInfo != null) {
                group("Connection") {
                    row {
                        text(connectionInfo)
                    }.topGap(TopGap.NONE)
                }
            }
        }
    }

    private fun buildConnectionInfo(): String? {
        val server = ApplicationManager.getApplication().getService(SteroidsMcpServer::class.java)
            ?: return null
        val url = server.mcpUrl.takeIf { server.port > 0 } ?: return null
        val content = IdeaDescriptionWriter.getInstance().buildDescriptionContent(url)
        // Convert plain text to simple HTML for display
        return buildString {
            for (line in content.lines()) {
                val trimmed = line.trimStart()
                when {
                    trimmed.startsWith("# ") -> append("<b>${trimmed.removePrefix("# ")}</b><br>")
                    trimmed.startsWith("## ") -> append("<b>${trimmed.removePrefix("## ")}</b><br>")
                    trimmed.startsWith("- **") -> {
                        val formatted = trimmed.replace(Regex("\\*\\*(.+?)\\*\\*"), "<b>$1</b>")
                        append("$formatted<br>")
                    }
                    trimmed.startsWith("=== ") -> append("<b>${trimmed.removeSurrounding("=== ", " ===")}</b><br>")
                    line.startsWith("  ") -> append("<code>${trimmed.replace("<", "&lt;")}</code><br>")
                    trimmed.isEmpty() -> append("<br>")
                    else -> append("$trimmed<br>")
                }
            }
        }.removeSuffix("<br>")
    }
}
