/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.review

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.util.registry.Registry
import com.intellij.ui.components.JBScrollBar
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.fields.ExpandableSupport
import com.intellij.ui.components.fields.ExtendableTextComponent
import com.intellij.ui.components.fields.ExtendableTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.TopGap
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.jonnyzzz.mcpSteroid.aiAgents.McpConnectionInfo
import com.jonnyzzz.mcpSteroid.server.SteroidsMcpServer
import java.awt.datatransfer.StringSelection

class McpSteroidProjectConfigurable(private val project: Project) : BoundConfigurable("MCP Steroid") {

    override fun createPanel(): DialogPanel {
        val settings = McpSteroidProjectSettings.getInstance(project)
        val registryIsNever = Registry.stringValue(McpSteroidProjectSettings.REVIEW_MODE_REGISTRY_KEY) == "NEVER"
        val info = loadConnectionInfo()

        return panel {
            // 1. Server URL
            if (info != null) {
                row("Server URL:") {
                    cell(copyableTextField(info.serverUrl)).align(AlignX.FILL)
                }
            }

            // 2. Feedback link
            if (info != null) {
                row {
                    browserLink("Report issues, Join Slack & Community", info.feedbackUrl)
                }.topGap(TopGap.SMALL)
            }

            // 3. Code Review
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

            // 4. Quick Start CLI commands
            if (info != null) {
                group("Quick Start") {
                    for ((name, command) in info.commands) {
                        row("$name:") {
                            cell(copyableTextField(command)).align(AlignX.FILL)
                        }
                    }

                    // Cursor/JSON Config — one level deep inside Quick Start
                    group("Cursor / JSON Config") {
                        val json = info.jsonConfig.trim()
                        val scrollPane = copyableTextArea(json, rows = 10)
                        row {
                            cell(scrollPane).align(Align.FILL)
                        }.topGap(TopGap.NONE)
                    }
                }
            }
        }
    }

    private fun copyableTextField(content: String): ExtendableTextField =
        ExtendableTextField().apply {
            text = content
            isEditable = false
            addExtension(ExtendableTextComponent.Extension.create(
                AllIcons.General.InlineCopy,
                AllIcons.General.InlineCopyHover,
                "Copy to clipboard"
            ) {
                CopyPasteManager.getInstance().setContents(StringSelection(content))
            })
        }

    private fun copyableTextArea(content: String, rows: Int): JBScrollPane {
        val copyExt = ExtendableTextComponent.Extension.create(
            AllIcons.General.InlineCopy,
            AllIcons.General.InlineCopyHover,
            "Copy to clipboard"
        ) {
            CopyPasteManager.getInstance().setContents(StringSelection(content))
        }
        val textArea = JBTextArea(content).apply {
            isEditable = false
            this.rows = rows
        }
        val scrollPane = JBScrollPane(textArea)
        scrollPane.verticalScrollBar.add(JBScrollBar.LEADING, ExpandableSupport.createLabel(copyExt))
        return scrollPane
    }

    private fun loadConnectionInfo(): McpConnectionInfo? {
        val server = ApplicationManager.getApplication().getService(SteroidsMcpServer::class.java)
            ?: return null
        val url = server.mcpUrl.takeIf { server.port > 0 } ?: return null
        return McpConnectionInfo.build(url)
    }
}
