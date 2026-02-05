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
            registerArticleResources(server, folder, index)
            registerFolderToc(server, folder, index)
            registerSkillPrompts(server, folder, index)
        }
    }

    private fun registerArticleResources(
        server: McpServerCore,
        folder: String,
        index: PromptIndexBase,
    ) {
        val namePrefix = folderToNamePrefix(folder)
        for ((_, article) in index.articles) {
            server.resourceRegistry.registerResourceMultiContent(
                uri = article.uri,
                name = namePrefix + article.name,
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

    private fun registerFolderToc(
        server: McpServerCore,
        folder: String,
        index: PromptIndexBase,
    ) {
        if (folder.isEmpty()) return
        val uriPrefix = folderToUriPrefix(folder)
        if (uriPrefix.isEmpty()) return
        if (index.articles.isEmpty()) return

        val tocUri = "mcp-steroid://$uriPrefix"
        val label = folderToNamePrefix(folder).trimEnd(' ', ':')
        val tocName = if (label.isNotEmpty()) "$label Resources" else "$uriPrefix Resources"

        server.resourceRegistry.registerResource(
            uri = tocUri,
            name = tocName,
            description = "Table of contents for all $uriPrefix resources",
            mimeType = "text/markdown",
            contentProvider = {
                buildTocContent(tocName, index)
            }
        )
    }

    private fun registerSkillPrompts(
        server: McpServerCore,
        folder: String,
        index: PromptIndexBase,
    ) {
        if (folder != "skill") return

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

private fun buildTocContent(tocName: String, index: PromptIndexBase): String = buildString {
    appendLine("# $tocName")
    appendLine()
    for ((_, article) in index.articles) {
        val desc = article.description.lineSequence().firstOrNull()?.take(120) ?: ""
        val suffix = if (desc.isNotEmpty()) " - $desc" else ""
        appendLine("- [${article.name}](${article.uri})$suffix")
    }
}

private fun folderToUriPrefix(folder: String): String {
    if (folder.isEmpty()) return ""
    return folder.removeSuffix("-examples")
}

private fun folderToNamePrefix(folder: String): String {
    val prefix = folderToUriPrefix(folder)
    return when (prefix) {
        "lsp" -> "LSP: "
        "ide" -> "IDE: "
        "debugger" -> "Debugger: "
        "test" -> "Test: "
        "vcs" -> "VCS: "
        "open-project" -> "Open Project: "
        "skill" -> ""
        "docs" -> ""
        "" -> ""
        else -> "${prefix.replaceFirstChar { it.titlecase() }}: "
    }
}
