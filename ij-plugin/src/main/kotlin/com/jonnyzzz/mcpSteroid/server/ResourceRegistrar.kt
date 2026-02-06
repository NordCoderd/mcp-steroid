/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.McpServerCore
import com.jonnyzzz.mcpSteroid.mcp.Prompt
import com.jonnyzzz.mcpSteroid.mcp.PromptContent
import com.jonnyzzz.mcpSteroid.mcp.PromptGetResult
import com.jonnyzzz.mcpSteroid.mcp.PromptMessage
import com.jonnyzzz.mcpSteroid.mcp.ResourceContent
import com.jonnyzzz.mcpSteroid.prompts.PromptIndexBase
import com.jonnyzzz.mcpSteroid.prompts.generated.ResourcesIndex

/**
 * Registers all generated prompt articles as MCP resources and prompts.
 *
 * Uses the generated [ResourcesIndex] to iterate over all folders and articles,
 * eliminating the need for generated registration code.
 */
class ResourceRegistrar : McpRegistrar {

    override fun register(server: McpServerCore) {
        val resourcesIndex = ResourcesIndex()

        for ((folder, index) in resourcesIndex.roots) {
            registerArticleResources(server, index)
            if (folder == "skill") {
                registerSkillPrompts(server, index)
            }
        }
    }

    private fun registerArticleResources(
        server: McpServerCore,
        index: PromptIndexBase,
    ) {
        for ((_, article) in index.articles) {
            server.resourceRegistry.registerResourceMultiContent(
                uri = article.uri,
                name = article.name,
                description = article.description,
                mimeType = article.mimeType,
                contentsProvider = {
                    buildList {
                        add(
                            ResourceContent(
                                uri = article.uri,
                                mimeType = article.mimeType,
                                text = article.payload.readPrompt()
                            )
                        )
                        val seeAlso = article.seeAlsoContent
                        if (seeAlso.isNotBlank()) {
                            add(
                                ResourceContent(
                                    uri = article.uri,
                                    mimeType = "text/markdown",
                                    text = seeAlso
                                )
                            )
                        }
                    }
                }
            )
        }
    }

    private fun registerSkillPrompts(
        server: McpServerCore,
        index: PromptIndexBase,
    ) {
        for ((_, article) in index.articles) {
            if (article.mimeType != "text/markdown") continue

            val content = article.payload.readPrompt()
            val parsed = parseSkillFrontmatter(content)
            val frontmatter = parsed.frontmatter ?: continue
            val promptName = frontmatter.name?.takeIf { it.isNotBlank() } ?: continue

            server.promptRegistry.registerPrompt(
                Prompt(
                    name = promptName,
                    title = article.name,
                    description = frontmatter.description,
                )
            ) {
                PromptGetResult(
                    description = frontmatter.description,
                    messages = listOf(
                        PromptMessage(
                            role = "user",
                            content = PromptContent.Text(parsed.body)
                        )
                    )
                )
            }
        }
    }
}
