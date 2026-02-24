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
            if (isSectionFile(clazz.path) && clazz.fileType == "kt") {
                ctx.generateKtSectionCompilationTest(clazz)
            }
        }

        // First pass: group articles per folder
        val folderArticles = promptClasses.groupBy { it.folder }
            .mapValues { (_, clazzes) -> groupByArticle(clazzes) }

        // Second pass: generate article classes with see-also content, then index classes
        val allStandalonePrompts = mutableListOf<GeneratedPromptClazz>()
        val indexes = promptClasses.groupBy { it.folder }
            .map { (folder, clazzes) ->
                val articles = folderArticles[folder] ?: emptyList()

                val articleClasses = articles.map { article ->
                    val seeAlsoContent = buildSeeAlsoContent(folder, article, folderArticles)
                    ctx.generateArticleClazz(folder, article, seeAlsoContent)
                }

                val tocContent = buildTocContent(folder, articles)
                val pkg = articleClasses.firstOrNull()?.clazzName?.packageName
                    ?: (ctx.packageName + "." + folder.toPromptIdentifierName())
                val tocArticle = ctx.generateTocArticleClazz(folder, tocContent, pkg)
                val allArticleClasses = articleClasses + listOfNotNull(tocArticle)

                val usedPromptNames = allArticleClasses.flatMap { it.article?.allClasses ?: emptyList() }.map { it.path }.toSortedSet()
                val standaloneFiles = clazzes.filter { it.path !in usedPromptNames }
                allStandalonePrompts.addAll(standaloneFiles)

                ctx.generateIndexClazz(folder, standaloneFiles, allArticleClasses)
            }

        ctx.generateIndexClazz(indexes)

        // Generate test that validates all mcp-steroid:// URI references in prompt content
        ctx.generateResourceUriValidationTest(allStandalonePrompts)
    }
}

/**
 * Build the see-also content for an article.
 * Prepends manual cross-folder content from the see-also file, then auto-generates
 * links to all other articles in the same folder.
 */
private fun buildSeeAlsoContent(
    folder: String,
    article: PromptArticle,
    folderArticles: Map<String, List<PromptArticle>>,
): String {
    val siblings = folderArticles[folder]?.filter { it !== article } ?: emptyList()

    // Read manual cross-folder content from the see-also file
    val manualContent = article.seeAlso?.content?.trim() ?: ""

    if (siblings.isEmpty() && manualContent.isEmpty()) return ""

    return buildString {
        appendLine("## See Also")
        appendLine()

        // Prepend manual cross-folder references
        if (manualContent.isNotEmpty()) {
            appendLine(manualContent)
            appendLine()
        }

        // Auto-generated same-folder sibling links
        if (siblings.isNotEmpty()) {
            val displayName = folderToDisplayName(folder)
            val folderLabel = displayName.ifEmpty { "related" }
            appendLine("Related $folderLabel resources:")
            for (sibling in siblings) {
                appendLine(buildSeeAlsoLine(folder, sibling))
            }
        }
    }.trim()
}

/**
 * Build the table-of-contents content for a folder, generated at build time.
 */
private fun buildTocContent(
    folder: String,
    articles: List<PromptArticle>,
): String {
    if (articles.isEmpty()) return ""
    val displayName = folderToDisplayName(folder)
    val tocName = if (displayName.isNotEmpty()) "$displayName Resources" else "Resources"

    return buildString {
        appendLine("# $tocName")
        appendLine()
        for (article in articles) {
            appendLine(buildSeeAlsoLine(folder, article))
        }
    }.trim()
}
