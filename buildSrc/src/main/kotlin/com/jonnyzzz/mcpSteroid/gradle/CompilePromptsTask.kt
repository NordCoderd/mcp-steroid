/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

abstract class CompilePromptsTask : DefaultTask() {
    @get:InputDirectory
    abstract val inputDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:OutputDirectory
    abstract val testOutputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val inputRoot = inputDir.get().asFile
        val outputRoot = outputDir.get().asFile
        val testOutputRoot = testOutputDir.get().asFile
        project.delete(outputRoot, testOutputRoot)
        project.mkdir(outputRoot)
        project.mkdir(testOutputRoot)

        val ctx = PromptGenerationContext(inputRoot, outputRoot, testOutputRoot)
        val promptClasses = inputRoot
            .walkTopDown()
            .filter { it.isFile }
            .map { src -> ctx.generatePromptClazz(src) }
            .toList()

        promptClasses.forEach { clazz ->
            ctx.generatePromptClazzTest(clazz)
        }

        // First pass: group articles per folder and compute URIs
        val folderArticles = promptClasses.groupBy { it.folder }
            .mapValues { (_, clazzes) -> groupByArticle(clazzes) }

        // Second pass: generate article classes with see-also content
        val indexes = promptClasses.groupBy { it.folder }
            .map { (folder, clazzes) ->
                val articles = folderArticles[folder] ?: emptyList()

                val articleClasses = articles.map { article ->
                    val seeAlsoContent = buildSeeAlsoContent(folder, article, folderArticles)
                    ctx.generateArticleClazz(folder, article, seeAlsoContent)
                }

                val usedPromptNames = articleClasses.flatMap { it.article.allClasses }.map { it.path }.toSortedSet()
                val standaloneFiles = clazzes.filter { it.path !in usedPromptNames }

                ctx.generateIndexClazz(folder, standaloneFiles, articleClasses)
            }

        ctx.generateIndexClazz(indexes)
    }
}

/**
 * Build the see-also content for an article by merging:
 * 1. Auto-generated same-folder sibling links
 * 2. Manual cross-folder links from the -see-also.md file (if present)
 */
private fun buildSeeAlsoContent(
    folder: String,
    article: PromptArticle,
    folderArticles: Map<String, List<PromptArticle>>,
): String {
    val sb = StringBuilder()

    // Auto-generate same-folder sibling links
    val siblings = folderArticles[folder]?.filter { it !== article } ?: emptyList()
    if (siblings.isNotEmpty()) {
        val namePrefix = folderToNamePrefix(folder)
        val folderLabel = when {
            namePrefix.isNotEmpty() -> namePrefix.trimEnd(' ', ':')
            else -> "related"
        }
        sb.appendLine("## See Also")
        sb.appendLine()
        sb.appendLine("Related $folderLabel resources:")
        for (sibling in siblings) {
            val siblingUri = buildArticleUri(folder, sibling.mainElement.path)
            val siblingTitle = sibling.header.content.trim().lineSequence().first().trim()
            val siblingDesc = sibling.header.content.trim().lineSequence().drop(1).firstOrNull()?.trim() ?: ""
            val descSuffix = if (siblingDesc.isNotEmpty()) " - $siblingDesc" else ""
            sb.appendLine("- [$siblingTitle]($siblingUri)$descSuffix")
        }
    }

    // Append manual cross-folder references from -see-also.md file if present
    if (article.seeAlso != null) {
        val manualContent = article.seeAlso.content.trim()
        if (manualContent.isNotEmpty()) {
            if (sb.isNotEmpty()) {
                sb.appendLine()
            }
            sb.appendLine(manualContent)
        }
    }

    return sb.toString().trim()
}
