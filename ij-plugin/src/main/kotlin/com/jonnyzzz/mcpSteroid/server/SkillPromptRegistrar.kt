/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.components.service
import com.jonnyzzz.mcpSteroid.mcp.McpServerCore
import com.jonnyzzz.mcpSteroid.mcp.Prompt
import com.jonnyzzz.mcpSteroid.mcp.PromptContent
import com.jonnyzzz.mcpSteroid.mcp.PromptGetResult
import com.jonnyzzz.mcpSteroid.mcp.PromptMessage

/**
 * Registers MCP prompts for bundled Agent Skills.
 */
class SkillPromptRegistrar : McpRegistrar {
    private val catalog: SkillCatalog
        get() = service()

    override fun register(server: McpServerCore) {
        catalog.listSkills().forEach { skill ->
            val prompt = Prompt(
                name = skill.promptName,
                title = skill.descriptor.resourceName,
                description = skill.description,
            )

            server.promptRegistry.registerPrompt(prompt) {
                PromptGetResult(
                    description = skill.description,
                    messages = listOf(
                        PromptMessage(
                            role = "user",
                            content = PromptContent.Text(skill.contentWithoutFrontmatter)
                        )
                    )
                )
            }
        }
    }
}
