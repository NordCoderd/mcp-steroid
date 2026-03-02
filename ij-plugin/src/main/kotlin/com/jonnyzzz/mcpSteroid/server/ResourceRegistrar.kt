/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.application.ApplicationInfo
import com.jonnyzzz.mcpSteroid.mcp.McpServerCore
import com.jonnyzzz.mcpSteroid.mcp.Prompt
import com.jonnyzzz.mcpSteroid.mcp.PromptContent
import com.jonnyzzz.mcpSteroid.mcp.PromptGetResult
import com.jonnyzzz.mcpSteroid.mcp.PromptMessage
import com.jonnyzzz.mcpSteroid.prompts.PromptIndexBase
import com.jonnyzzz.mcpSteroid.prompts.PromptsContext
import com.jonnyzzz.mcpSteroid.prompts.generated.ResourcesIndex

/**
 * Registers all generated prompt articles as MCP resources and prompts.
 *
 * Uses the generated [ResourcesIndex] to iterate over all folders and articles,
 * eliminating the need for generated registration code.
 *
 * Articles are filtered by their root [IdeFilter] — articles that don't match
 * the current IDE are skipped entirely.
 *
 * Content is rendered via [ArticleBase.readPayload] which handles per-part
 * filtering and see-also filtering internally.
 */
class ResourceRegistrar : McpRegistrar {

    override fun register(server: McpServerCore) {
        val resourcesIndex = ResourcesIndex()
        val context = buildPromptsContext()

        for ((folder, index) in resourcesIndex.roots) {
            registerArticleResources(server, index, context)
            if (folder == "prompt") {
                registerSkillPrompts(server, index, context)
            }
        }

    }

    private fun registerArticleResources(
        server: McpServerCore,
        index: PromptIndexBase,
        context: PromptsContext,
    ) {
        for ((_, article) in index.articles) {
            if (!article.filter.matches(context)) continue

            server.resourceRegistry.registerResource(
                uri = article.uri,
                name = article.title.readPrompt(),
                description = article.description.readPrompt(),
                mimeType = "text/markdown",
            ) {
                article.readPayload(context)
            }
        }
    }

    private fun registerSkillPrompts(
        server: McpServerCore,
        index: PromptIndexBase,
        context: PromptsContext,
    ) {
        for ((_, article) in index.articles) {
            if (!article.filter.matches(context)) continue

            server.promptRegistry.registerPrompt(
                Prompt(
                    name = article.uri,
                    title = article.title.readPrompt(),
                    description = article.description.readPrompt(),
                )
            ) {
                PromptGetResult(
                    description = article.description.readPrompt(),
                    messages = listOf(
                        PromptMessage(
                            role = "user",
                            content = PromptContent.Text(article.readPayload(context))
                        )
                    )
                )
            }
        }
    }

    companion object {
        fun buildPromptsContext(): PromptsContext {
            val buildInfo = ApplicationInfo.getInstance().build
            return PromptsContext(
                productCode = buildInfo.productCode,
                baselineVersion = buildInfo.baselineVersion,
            )
        }
    }
}
