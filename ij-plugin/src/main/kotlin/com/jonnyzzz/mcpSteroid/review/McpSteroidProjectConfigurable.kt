/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.review

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.util.registry.Registry
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.TopGap
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.jonnyzzz.mcpSteroid.aiAgents.McpConnectionInfo
import com.jonnyzzz.mcpSteroid.server.SteroidsMcpServer

class McpSteroidProjectConfigurable(private val project: Project) : BoundConfigurable("MCP Steroid") {

    override fun createPanel(): DialogPanel {
        val settings = McpSteroidProjectSettings.getInstance(project)
        val registryIsNever = Registry.stringValue(McpSteroidProjectSettings.REVIEW_MODE_REGISTRY_KEY) == "NEVER"
        val connectionInfo = loadConnectionInfo()

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

            if (connectionInfo != null) {
                group("Connection") {
                    row("Server URL:") {
                        textField()
                            .applyToComponent { text = connectionInfo.serverUrl; isEditable = false }
                            .align(AlignX.FILL)
                    }

                    group("Quick Start") {
                        row("Claude Code:") {
                            textField()
                                .applyToComponent { text = connectionInfo.claudeCommand; isEditable = false }
                                .align(AlignX.FILL)
                        }
                        row("Codex:") {
                            textField()
                                .applyToComponent { text = connectionInfo.codexCommand; isEditable = false }
                                .align(AlignX.FILL)
                        }
                        row("Gemini:") {
                            textField()
                                .applyToComponent { text = connectionInfo.geminiCommand; isEditable = false }
                                .align(AlignX.FILL)
                        }
                    }

                    group("Cursor / JSON Config") {
                        row {
                            val textArea = JBTextArea(connectionInfo.jsonConfig.trim()).apply {
                                isEditable = false
                                rows = 7
                            }
                            cell(JBScrollPane(textArea)).align(Align.FILL)
                        }.topGap(TopGap.NONE)
                    }

                    row {
                        browserLink("Report issues, Join Slack & Community", connectionInfo.feedbackUrl)
                    }
                }
            }
        }
    }

    private fun loadConnectionInfo(): McpConnectionInfo? {
        val server = ApplicationManager.getApplication().getService(SteroidsMcpServer::class.java)
            ?: return null
        val url = server.mcpUrl.takeIf { server.port > 0 } ?: return null
        return McpConnectionInfo.build(url)
    }
}
