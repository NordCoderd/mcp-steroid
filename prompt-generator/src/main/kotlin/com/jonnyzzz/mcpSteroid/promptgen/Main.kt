/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.promptgen

import java.io.File

fun main(args: Array<String>) {
    val argsMap = parseArgs(args)
    val inputDir = File(argsMap.getValue("--input-dir"))
    val outputDir = File(argsMap.getValue("--output-dir"))
    val testOutputDir = File(argsMap.getValue("--test-output-dir"))

    require(inputDir.isDirectory) { "Input directory does not exist: $inputDir" }

    if (outputDir.exists()) outputDir.deleteRecursively()
    if (testOutputDir.exists()) testOutputDir.deleteRecursively()

    outputDir.mkdirs()
    testOutputDir.mkdirs()

    generate(inputDir, outputDir, testOutputDir)
}

private fun parseArgs(args: Array<String>): Map<String, String> {
    val result = mutableMapOf<String, String>()
    var i = 0
    while (i < args.size) {
        val key = args[i]
        require(key.startsWith("--")) { "Expected argument key starting with --, got: $key" }
        require(i + 1 < args.size) { "Missing value for argument: $key" }
        result[key] = args[i + 1]
        i += 2
    }
    return result
}

internal fun generate(
    inputRoot: File,
    outputRoot: File,
    testOutputRoot: File,
) {
    val ctx = PromptGenerationContext(inputRoot, outputRoot, testOutputRoot)
    val promptClasses = inputRoot
        .walkTopDown()
        .filter { it.isFile }
        .map { src -> ctx.generatePromptClazz(src) }
        .toList()

    promptClasses.forEach { clazz ->
        ctx.generatePromptClazzTest(clazz)
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
                val articleClazz = ctx.generateArticleClazz(folder, article, seeAlsoContent)
                ctx.generateArticleReadTest(articleClazz)
                ctx.generateMdKtBlockCompilationTests(articleClazz)
                articleClazz
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

/**
 * Extracts all `mcp-steroid://...` URIs mentioned in the article's `# See also` section
 * (the manual content embedded in the new-format `.md` file).
 *
 * Used to avoid duplicating links in the auto-generated sibling list.
 */
private fun extractManualSeeAlsoUris(article: PromptArticle): Set<String> {
    val content = article.payload.content
    val seeAlsoMarker = "\n\n# See also\n"
    val idx = content.indexOf(seeAlsoMarker)
    if (idx < 0) return emptySet()
    val seeAlsoSection = content.substring(idx)
    return Regex("mcp-steroid://[a-zA-Z0-9/_-]+").findAll(seeAlsoSection).map { it.value }.toSet()
}

/**
 * Build the auto-generated see-also content for an article.
 *
 * Returns a list of sibling article links that are NOT already mentioned in the
 * article's manual `# See also` section (embedded in the `.md` file).
 *
 * Returns empty string if there are no new sibling links to add.
 * Articles with `###_NO_AUTO_TOC_###` are excluded from sibling lists.
 */
private fun buildSeeAlsoContent(
    folder: String,
    article: PromptArticle,
    folderArticles: Map<String, List<PromptArticle>>,
): String {
    val siblings = folderArticles[folder]?.filter { it !== article && !it.noAutoToc } ?: emptyList()
    if (siblings.isEmpty()) return ""

    // Exclude siblings already referenced in the file's manual # See also section
    val manualUris = extractManualSeeAlsoUris(article)
    val newSiblings = siblings.filter {
        buildArticleUri(folder, it.canonicalPayloadPath) !in manualUris
    }
    if (newSiblings.isEmpty()) return ""

    val displayName = folderToDisplayName(folder)
    val folderLabel = displayName.ifEmpty { "related" }
    return buildString {
        appendLine("Related $folderLabel resources:")
        for (sibling in newSiblings) {
            appendLine(buildSeeAlsoLine(folder, sibling))
        }
    }.trim()
}

/**
 * Build the table-of-contents content for a folder, generated at build time.
 * Articles with `###_NO_AUTO_TOC_###` are excluded from the TOC.
 */
private fun buildTocContent(
    folder: String,
    articles: List<PromptArticle>,
): String {
    val tocArticles = articles.filter { !it.noAutoToc }
    if (tocArticles.isEmpty()) return ""
    val displayName = folderToDisplayName(folder)
    val tocName = if (displayName.isNotEmpty()) "$displayName Resources" else "Resources"

    return buildString {
        appendLine("# $tocName")
        appendLine()
        for (article in tocArticles) {
            appendLine(buildSeeAlsoLine(folder, article))
        }
    }.trim()
}
