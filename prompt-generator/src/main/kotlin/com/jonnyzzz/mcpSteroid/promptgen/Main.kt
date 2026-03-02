/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.promptgen

import com.squareup.kotlinpoet.ClassName
import java.io.File

fun main(args: Array<String>) {
    val argsMap = parseArgs(args)
    val inputDir = File(argsMap.getValue("--input-dir"))
    val outputDir = File(argsMap.getValue("--output-dir"))
    val testOutputDir = File(argsMap.getValue("--test-output-dir"))
    val extraInputDirs = argsMap["--extra-input-dirs"]
        ?.split(",")
        ?.filter { it.isNotBlank() }
        ?.map { File(it) }
        ?: emptyList()

    require(inputDir.isDirectory) { "Input directory does not exist: $inputDir" }
    for (dir in extraInputDirs) {
        require(dir.isDirectory) { "Extra input directory does not exist: $dir" }
    }

    if (outputDir.exists()) outputDir.deleteRecursively()
    if (testOutputDir.exists()) testOutputDir.deleteRecursively()

    outputDir.mkdirs()
    testOutputDir.mkdirs()

    generate(listOf(inputDir) + extraInputDirs, outputDir, testOutputDir)
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
) = generate(listOf(inputRoot), outputRoot, testOutputRoot)

internal fun generate(
    inputRoots: List<File>,
    outputRoot: File,
    testOutputRoot: File,
) {
    val primaryCtx = PromptGenerationContext(inputRoots.first(), outputRoot, testOutputRoot)
    val promptClasses = inputRoots.flatMap { inputRoot ->
        val ctx = PromptGenerationContext(inputRoot, outputRoot, testOutputRoot)
        inputRoot
            .walkTopDown()
            .filter { it.isFile }
            .map { src -> ctx.generatePromptClazz(src) }
            .toList()
            .also { clazzes -> clazzes.forEach { ctx.generatePromptClazzTest(it) } }
    }
    val ctx = primaryCtx

    // First pass: group articles per folder
    val folderArticles = promptClasses.groupBy { it.folder }
        .mapValues { (_, clazzes) -> groupByArticle(clazzes) }

    // Build URI → ClassName and URI → title maps for see-also resolution
    val uriToClassName = mutableMapOf<String, ClassName>()
    val uriToTitle = mutableMapOf<String, String>()
    for ((folder, articles) in folderArticles) {
        for (article in articles) {
            val uri = buildArticleUri(folder, article.canonicalPayloadPath)
            uriToClassName[uri] = articleClassName(article)
            uriToTitle[uri] = articleName(article)
        }
    }

    // Second pass: generate article classes with resolved see-also, then index classes
    val allStandalonePrompts = mutableListOf<GeneratedPromptClazz>()
    val indexes = promptClasses.groupBy { it.folder }
        .map { (folder, clazzes) ->
            val articles = folderArticles[folder] ?: emptyList()

            val articleClasses = articles.map { article ->
                val resolvedSeeAlso = resolveSeeAlsoClassNames(folder, article, folderArticles, uriToClassName, uriToTitle)
                val articleClazz = ctx.generateArticleClazz(folder, article, resolvedSeeAlso)
                ctx.generateArticleReadTest(articleClazz)
                ctx.generateMdKtBlockCompilationTests(articleClazz)
                articleClazz
            }

            // Collect member articles for the TOC (excluding no-auto-toc)
            val tocMembers = articles.filter { !it.noAutoToc }

            val pkg = articleClasses.firstOrNull()?.clazzName?.packageName
                ?: (ctx.packageName + "." + folder.toPromptIdentifierName())
            val tocArticle = ctx.generateTocArticleClazz(folder, tocMembers, pkg)
            val allArticleClasses = articleClasses + listOfNotNull(tocArticle)

            val usedPromptNames = allArticleClasses.flatMap { it.article?.allClasses ?: emptyList() }.map { it.path }.toSortedSet()
            val standaloneFiles = clazzes.filter { it.path !in usedPromptNames }
            allStandalonePrompts.addAll(standaloneFiles)

            // Write prompt classes: standalone → main output, article-content → test output
            for (clazz in standaloneFiles) {
                ctx.writeClazz(clazz.fileSpec, clazz.clazzName)
            }
            for (clazz in clazzes.filter { it.path in usedPromptNames }) {
                ctx.writeTestClazz(clazz.fileSpec, clazz.clazzName)
            }

            ctx.generateIndexClazz(folder, standaloneFiles, allArticleClasses)
        }

    ctx.generateIndexClazz(indexes)

    // Generate test that validates all mcp-steroid:// URI references in prompt content
    ctx.generateResourceUriValidationTest(allStandalonePrompts)
}

/**
 * Resolves see-also entries for an article into article class names.
 *
 * 1. **Custom**: extracts `mcp-steroid://...` URIs from the `# See also` section in the
 *    source `.md` file and resolves them to article class names via [uriToClassName].
 * 2. **Generated**: sibling articles in the same folder (not already in custom, not no-auto-toc)
 *    are added as auto-generated see-also entries.
 *
 * Custom entries come first, then generated entries.
 */
private fun resolveSeeAlsoClassNames(
    folder: String,
    article: PromptArticle,
    folderArticles: Map<String, List<PromptArticle>>,
    uriToClassName: Map<String, ClassName>,
    uriToTitle: Map<String, String>,
): ResolvedSeeAlso {
    // Extract manual URIs from # See also section
    val manualUris = extractManualSeeAlsoUris(article)
    val customClassNames = manualUris.mapNotNull { uri ->
        val cls = uriToClassName[uri] ?: return@mapNotNull null
        val title = uriToTitle[uri] ?: ""
        cls to title
    }.sortedBy { it.second }.map { it.first }

    // Auto-generated siblings (not already in manual, not no-auto-toc), sorted by title
    val siblings = folderArticles[folder]?.filter { it !== article && !it.noAutoToc } ?: emptyList()
    val generatedClassNames = siblings
        .filter { buildArticleUri(folder, it.canonicalPayloadPath) !in manualUris }
        .sortedBy { articleName(it) }
        .map { articleClassName(it) }

    return ResolvedSeeAlso(customClassNames, generatedClassNames)
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
