/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.promptgen

import com.jonnyzzz.mcpSteroid.prompts.ArticlePart
import com.jonnyzzz.mcpSteroid.prompts.IdeFilter
import com.jonnyzzz.mcpSteroid.prompts.SeeAlsoItem
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.buildCodeBlock

/**
 * Directives that can appear as standalone lines in the body of a new-format `.md` article.
 * When either is present:
 * - The article is excluded from the auto-generated folder TOC and from sibling see-also links.
 * - The directive line is stripped from the payload output (not visible to MCP clients).
 *
 * Both forms are supported as aliases; prefer [EXCLUDE_FROM_AUTO_TOC_MARKER].
 */
const val NO_AUTO_TOC_MARKER = "###_NO_AUTO_TOC_###"
const val EXCLUDE_FROM_AUTO_TOC_MARKER = "###_EXCLUDE_FROM_AUTO_TOC_###"

private val AUTO_TOC_DIRECTIVES = setOf(NO_AUTO_TOC_MARKER, EXCLUDE_FROM_AUTO_TOC_MARKER)

fun groupByArticle(promptClasses: List<GeneratedPromptClazz>): List<PromptArticle> {
    return promptClasses.mapNotNull { clazz ->
        if (clazz.fileType != "md") return@mapNotNull null
        if (!clazz.path.contains("/")) return@mapNotNull null  // root-level standalone files are not articles
        PromptArticle(payload = clazz)
    }
}

data class PromptArticle(
    val payload: GeneratedPromptClazz,
) {
    val canonicalPayloadPath: String get() = payload.path

    val mainElement: GeneratedPromptClazz get() = payload

    /**
     * True if the article body contains either `###_NO_AUTO_TOC_###` or `###_EXCLUDE_FROM_AUTO_TOC_###`.
     * Such articles are excluded from the auto-generated folder TOC and from sibling see-also links.
     */
    val noAutoToc: Boolean
        get() = AUTO_TOC_DIRECTIVES.any { payload.content.contains(it) }

    val allClasses: List<GeneratedPromptClazz>
        get() = listOf(payload)

}

/**
 * Parsed parts of a new-format `.md` article.
 *
 * The file format is:
 * ```
 * Title              ← line 1
 * [RD]               ← line 2: filter (or blank for IdeFilter.All)
 * Description        ← line 3 (always required, never empty)
 *                    ← line 4: blank
 * ...body content (markdown + ```kotlin``` blocks)...
 *
 * # See also        ← optional section
 *
 * - links...
 * ```
 *
 * Invariant: [assembleFullContent] produces the exact original file content.
 */
data class NewFormatParts(
    val title: String,
    val rootFilter: IdeFilter,
    val description: String,
    /** Markdown segments interleaved with kotlin blocks; length = ktBodyParts.size + 1 */
    val mdBodyParts: List<String>,
    /** Kotlin code blocks (without fence lines) with fence annotation metadata, in order */
    val ktBodyParts: List<KtBlockWithMeta>,
    /**
     * Content after `# See also\n\n`, preserving trailing newlines from the source file.
     * Null if the file has no `# See also` section.
     */
    val seeAlsoManual: String?,
)

/**
 * Parses a new-format `.md` article content into its constituent parts.
 */
fun parseNewFormatArticleParts(content: String): NewFormatParts {
    val lines = content.lines()
    require(lines.size >= 4) { "Article must have at least 4 lines, got ${lines.size}" }

    val title = lines[0].trim()

    // Line 2: filter (e.g. "[RD]") or blank (IdeFilter.All)
    val line2 = lines[1].trim()
    require(line2.isBlank() || (line2.startsWith("[") && line2.endsWith("]"))) {
        "Line 2 must be blank or a filter like [RD], got: '$line2'"
    }
    val rootFilter: IdeFilter = if (line2.isBlank()) {
        IdeFilter.All
    } else {
        val bracket = line2.removePrefix("[").removeSuffix("]")
        val meta = FenceMetadata.parse(bracket)
        IdeFilter.Ide(meta.productCodes, meta.minVersion, meta.maxVersion)
    }

    // Description: lines 3+ until the first blank line
    val descriptionLines = mutableListOf<String>()
    var lineIdx = 2
    while (lineIdx < lines.size && lines[lineIdx].isNotBlank()) {
        descriptionLines.add(lines[lineIdx])
        lineIdx++
    }
    require(descriptionLines.isNotEmpty()) { "Description must not be empty (line 3+)" }
    require(lineIdx < lines.size && lines[lineIdx].isBlank()) {
        "Expected blank line after description at line ${lineIdx + 1}, got: '${lines.getOrNull(lineIdx)}'"
    }
    val description = descriptionLines.joinToString("\n")

    // lines().joinToString("\n") round-trips correctly:
    // "text\n".lines() = ["text", ""] → joinToString → "text\n"
    // "text".lines()   = ["text"]     → joinToString → "text"
    val bodyAndSeeAlso = lines.drop(lineIdx + 1).joinToString("\n")

    val seeAlsoMarker = "\n\n# See also\n"
    val seeAlsoIdx = bodyAndSeeAlso.indexOf(seeAlsoMarker)
    val body: String
    val seeAlsoManual: String?
    if (seeAlsoIdx >= 0) {
        body = bodyAndSeeAlso.substring(0, seeAlsoIdx)
        val afterMarker = bodyAndSeeAlso.substring(seeAlsoIdx + seeAlsoMarker.length)
        // Skip the blank line after "# See also" header; keep trailing newlines as-is
        // (they are part of the file content and must be preserved for round-trip equality)
        seeAlsoManual = afterMarker.removePrefix("\n")
    } else {
        body = bodyAndSeeAlso
        seeAlsoManual = null
    }

    val mdParts = mutableListOf<String>()
    val ktParts = mutableListOf<KtBlockWithMeta>()

    val ktFencePattern = Regex("""```kotlin(\[([^\]]*)])?\n""")
    var remaining = body
    while (true) {
        val match = ktFencePattern.find(remaining)
        if (match == null) {
            mdParts.add(remaining)
            break
        }
        mdParts.add(remaining.substring(0, match.range.first))
        val bracket = match.groupValues[2]  // content inside [...], or "" if no bracket
        val meta = if (bracket.isNotEmpty()) FenceMetadata.parse(bracket) else FenceMetadata.DEFAULT
        val codeStart = match.range.last + 1
        val closingIdx = remaining.indexOf("\n```", codeStart)
        require(closingIdx >= 0) { "Unclosed ```kotlin block in article body" }
        ktParts.add(KtBlockWithMeta(meta, remaining.substring(codeStart, closingIdx + 1)))
        remaining = remaining.substring(closingIdx + "\n```".length)
    }

    return NewFormatParts(title, rootFilter, description, mdParts, ktParts, seeAlsoManual)
}

/**
 * Assembles the full file content from [NewFormatParts].
 * Used at build time to assert [parseNewFormatArticleParts] round-trips correctly.
 */
fun assembleFullContent(parts: NewFormatParts): String = buildString {
    append(parts.title)
    append("\n")
    // Line 2: filter or blank
    if (parts.rootFilter is IdeFilter.All) {
        append("\n")
    } else {
        append(formatFilterLine(parts.rootFilter))
        append("\n")
    }
    append(parts.description)
    append("\n\n")
    for (i in parts.mdBodyParts.indices) {
        val md = parts.mdBodyParts[i]
        if (md.isNotEmpty()) append(md)
        if (i < parts.ktBodyParts.size) {
            val block = parts.ktBodyParts[i]
            append("```kotlin")
            append(block.metadata.toFenceSuffix())
            append("\n")
            append(block.code)
            append("```")
        }
    }
    if (parts.seeAlsoManual != null) {
        append("\n\n# See also\n\n")
        append(parts.seeAlsoManual)
    }
    // Trailing newlines are preserved inside mdBodyParts (no-see-also case) or
    // inside seeAlsoManual (see-also case) — no extra append needed.
}

/**
 * Formats an [IdeFilter] as a bracket line for article line 2.
 * E.g. `IdeFilter.Ide(setOf("RD"))` → `"[RD]"`.
 */
fun formatFilterLine(filter: IdeFilter): String = when (filter) {
    is IdeFilter.All -> ""
    is IdeFilter.Ide -> {
        val meta = FenceMetadata(filter.productCodes, filter.minVersion, filter.maxVersion)
        meta.toFenceSuffix()
    }
    else -> error("Root filter must be All or Ide, got: $filter")
}

data class GeneratedArticleClazz(
    val folder: String,
    val path: String,
    override val clazzName: ClassName,
    val article: PromptArticle?,
    val uri: String,
) : Generated {
    override val entryName: String
        get() = path.substringAfterLast("/").toPromptIdentifierName()
}

fun folderToUriPrefix(folder: String): String = folder

fun folderToDisplayName(folder: String): String {
    return when (folder) {
        "lsp" -> "LSP"
        "ide" -> "IDE"
        "debugger" -> "Debugger"
        "test" -> "Test"
        "vcs" -> "VCS"
        "open-project" -> "Open Project"
        "skill" -> "Skill"
        "prompt" -> "Prompt"
        "" -> ""
        else -> folder.titleCase()
    }
}

fun payloadFileStem(payloadPath: String): String {
    val fileName = payloadPath.substringAfterLast("/")
    val stem = fileName.removeSuffix(".kts").removeSuffix(".md").removeSuffix(".kt")
    return stem.replace('_', '-').lowercase()
}

fun buildArticleUri(folder: String, payloadPath: String): String {
    val prefix = folderToUriPrefix(folder)
    val stem = payloadFileStem(payloadPath)
    return if (prefix.isEmpty()) "mcp-steroid://$stem" else "mcp-steroid://$prefix/$stem"
}

fun articleClassName(article: PromptArticle): ClassName {
    val packageName = article.payload.clazzName.packageName
    val stem = article.canonicalPayloadPath
        .substringAfterLast("/")
        .removeSuffix(".md")
    val className = stem.toPromptClassName() + "PromptArticle"
    return ClassName(packageName, className)
}

fun articleName(article: PromptArticle): String =
    article.payload.content.trim().lineSequence().first().trim()

fun articleDescription(article: PromptArticle): String =
    article.payload.content.trim().lineSequence()
        .drop(1).dropWhile { it.isBlank() }.firstOrNull()?.trim() ?: ""

fun articleDescriptionFirstLine(article: PromptArticle): String =
    articleDescription(article)

/**
 * Computes the article-level filter from the declared root filter and the
 * OR-union of all kotlin code block filters.
 *
 * Rule: `articleFilter = declaredFilter AND (OR for all code block filters)`
 *
 * Simplifications (logically equivalent):
 * - `All AND x = x`, `x AND All = x`
 * - If all blocks are [IdeFilter.Ide] with contiguous version ranges, the OR-union
 *   is merged into a single `Ide` (codes united, versions widened)
 * - Otherwise, the full composite formula is kept (Or tree + And wrapper)
 */
fun computeArticleFilter(rootFilter: IdeFilter, ktBlockFilters: List<IdeFilter>): IdeFilter {
    if (ktBlockFilters.isEmpty()) return rootFilter

    // Build OR-union of all block filters, simplifying when possible
    val orUnion = simplifyOrUnion(ktBlockFilters)

    // Intersect with root filter, simplifying when possible
    return simplifyAnd(rootFilter, orUnion)
}

/**
 * Builds the OR-union of filters, applying simplifications that don't change logic:
 * - Single filter → return as-is
 * - Any [IdeFilter.All] → union is All
 * - All [IdeFilter.Ide] with contiguous version ranges → merge into single Ide
 * - Otherwise → composite Or tree
 */
private fun simplifyOrUnion(filters: List<IdeFilter>): IdeFilter {
    if (filters.size == 1) return filters[0]

    // If any filter is All, the union is All
    if (filters.any { it is IdeFilter.All }) return IdeFilter.All

    // If all are Ide, try to merge into a single Ide
    val ideFilters = filters.filterIsInstance<IdeFilter.Ide>()
    if (ideFilters.size == filters.size) {
        // Check for empty product codes (= any product = All)
        if (ideFilters.any { it.productCodes.isEmpty() }) return IdeFilter.All
        val merged = mergeIdeFilters(ideFilters)
        if (merged != null) return merged
    }

    // Can't simplify — build composite Or(f1, Or(f2, f3))
    return filters.reduce { acc, f -> acc.or(f) }
}

/**
 * Intersects two filters (AND semantics), simplifying only trivial cases:
 * - All AND x → x
 * - x AND All → x
 * - Otherwise → And(a, b)
 */
private fun simplifyAnd(a: IdeFilter, b: IdeFilter): IdeFilter {
    if (a is IdeFilter.All) return b
    if (b is IdeFilter.All) return a
    return a.and(b)
}


/**
 * Tries to merge multiple [IdeFilter.Ide] filters into a single [IdeFilter.Ide].
 *
 * OR-union semantics: product codes are merged, version ranges are widened.
 * Returns the merged filter if all version ranges form a contiguous interval,
 * or null if there are gaps that prevent representing the union as a single range.
 */
private fun mergeIdeFilters(filters: List<IdeFilter.Ide>): IdeFilter.Ide? {
    if (filters.isEmpty()) return null

    val allCodes = filters.flatMapTo(mutableSetOf()) { it.productCodes }

    // If any filter has no version constraint, the union is unconstrained
    if (filters.any { it.minVersion == null && it.maxVersion == null }) {
        return IdeFilter.Ide(allCodes)
    }

    // Collect all intervals; treat null min as Int.MIN_VALUE, null max as Int.MAX_VALUE
    data class Interval(val min: Int, val max: Int)
    val intervals = filters.map {
        Interval(it.minVersion ?: Int.MIN_VALUE, it.maxVersion ?: Int.MAX_VALUE)
    }.sortedBy { it.min }

    // Merge contiguous/overlapping intervals
    var merged = intervals[0]
    for (i in 1 until intervals.size) {
        val next = intervals[i]
        if (next.min <= merged.max + 1) {
            merged = Interval(merged.min, maxOf(merged.max, next.max))
        } else {
            // Gap found — can't represent as single range
            return null
        }
    }

    val mergedMin = if (merged.min == Int.MIN_VALUE) null else merged.min
    val mergedMax = if (merged.max == Int.MAX_VALUE) null else merged.max
    return IdeFilter.Ide(allCodes, mergedMin, mergedMax)
}

fun buildSeeAlsoLine(folder: String, article: PromptArticle): String {
    val uri = buildArticleUri(folder, article.canonicalPayloadPath)
    val name = articleName(article)
    val desc = articleDescriptionFirstLine(article)
    val suffix = if (desc.isNotEmpty()) " - $desc" else ""
    return "- [$name]($uri)$suffix"
}

/**
 * Resolved see-also class names for an article, split into custom (from # See also section)
 * and generated (auto-sibling links).
 */
data class ResolvedSeeAlso(
    val customClassNames: List<ClassName>,
    val generatedClassNames: List<ClassName>,
)

fun PromptGenerationContext.generateArticleClazz(
    folder: String,
    article: PromptArticle,
    resolvedSeeAlso: ResolvedSeeAlso,
): GeneratedArticleClazz {
    val uri = buildArticleUri(folder, article.canonicalPayloadPath)
    val title = articleName(article)

    val classType = articleClassName(article)
    val pkg = classType.packageName

    val props = mutableListOf<PropertySpec>()

    // Parse parts, assert round-trip
    val content = article.payload.content
    val parts = parseNewFormatArticleParts(content)

    val assembled = assembleFullContent(parts)
    require(assembled == content) {
        "Build-time assertion failed for ${article.payload.path}: " +
            "assembled parts differ from source file content.\n" +
            "Expected length=${content.length}, assembled length=${assembled.length}.\n" +
            "First diff at char ${content.zip(assembled).indexOfFirst { (a, b) -> a != b }}"
    }

    val description = parts.description
    require(description.isNotEmpty()) { "Description must not be empty for ${article.payload.path}" }

    // Title holder class
    val titleHolderClass = ClassName(pkg, classType.simpleName + "Title")
    generateStringPromptClazz(title, titleHolderClass, sourcePath = article.payload.path)

    // Description holder class
    val descHolderClass = ClassName(pkg, classType.simpleName + "Description")
    generateStringPromptClazz(description, descHolderClass, sourcePath = article.payload.path)

    // Generate part classes and ktBlock properties
    val partClassNames = generateNewFormatParts(article, parts, classType, pkg, props)

    // Compute article filter: declared root filter AND (OR-union of kotlin block filters)
    val contentParts = buildContentParts(parts)
    val ktBlockFilters = contentParts.filter { it.isKotlinBlock }.map { it.filter }
    val articleFilter = computeArticleFilter(parts.rootFilter, ktBlockFilters)

    // uri property
    props += PropertySpec.builder("uri", String::class)
        .addModifiers(KModifier.OVERRIDE)
        .initializer("%S", uri)
        .build()

    // title property
    props += PropertySpec.builder("title", promptBaseClass)
        .addModifiers(KModifier.OVERRIDE)
        .getter(FunSpec.getterBuilder().addCode(buildCodeBlock {
            addStatement("return %T()", titleHolderClass)
        }).build())
        .build()

    // filter property
    val filterType = IdeFilter::class.asClassName()
    props += PropertySpec.builder("filter", filterType)
        .addModifiers(KModifier.OVERRIDE)
        .initializer(emitFilterConstructor(articleFilter))
        .build()

    // description property (non-nullable)
    props += PropertySpec.builder("description", promptBaseClass)
        .addModifiers(KModifier.OVERRIDE)
        .getter(FunSpec.getterBuilder().addCode(buildCodeBlock {
            addStatement("return %T()", descHolderClass)
        }).build())
        .build()

    // parts property
    val articlePartListType = List::class.asClassName().parameterizedBy(articlePartClass)
    props += PropertySpec.builder("parts", articlePartListType)
        .addModifiers(KModifier.OVERRIDE)
        .getter(FunSpec.getterBuilder().addCode(buildCodeBlock {
            if (partClassNames.isEmpty()) {
                addStatement("return emptyList()")
            } else {
                controlFlow("return listOf(") {
                    // nothing — items added below
                }
                // Use manual code block to list items
            }
        }).build())
        .build()

    // Actually, let me build the parts property more carefully
    props.removeIf { it.name == "parts" }
    props += PropertySpec.builder("parts", articlePartListType)
        .addModifiers(KModifier.OVERRIDE)
        .getter(FunSpec.getterBuilder().addCode(buildCodeBlock {
            if (partClassNames.isEmpty()) {
                addStatement("return emptyList()")
            } else {
                add("return listOf(\n")
                for ((i, cls) in partClassNames.withIndex()) {
                    val comma = if (i < partClassNames.size - 1) "," else ","
                    add("  %T()$comma\n", cls)
                }
                add(")\n")
            }
        }).build())
        .build()

    // seeAlsoItems property
    val seeAlsoItemListType = List::class.asClassName().parameterizedBy(seeAlsoItemClass)
    val allSeeAlsoClassNames = resolvedSeeAlso.customClassNames + resolvedSeeAlso.generatedClassNames
    props += PropertySpec.builder("seeAlsoItems", seeAlsoItemListType)
        .addModifiers(KModifier.OVERRIDE)
        .getter(FunSpec.getterBuilder().addCode(buildCodeBlock {
            if (allSeeAlsoClassNames.isEmpty()) {
                addStatement("return emptyList()")
            } else {
                add("return listOf(\n")
                for (cls in allSeeAlsoClassNames) {
                    add("  %T(),\n", cls)
                }
                add(").map { it.asSeeAlsoItem() }\n")
            }
        }).build())
        .build()

    val typeSpec = TypeSpec.classBuilder(classType)
        .superclass(promptArticleClass)
        .addProperties(props)
        .build()

    val fileSpec = FileSpec.builder(classType)
        .addFileComment("GENERATED FILE - DO NOT EDIT")
        .addType(typeSpec)
        .build()

    writeClazz(fileSpec, classType)
    return GeneratedArticleClazz(
        folder = folder,
        path = article.canonicalPayloadPath,
        clazzName = classType,
        article = article,
        uri = uri,
    )
}

/**
 * Generates part classes (extending ArticlePart.Markdown/KotlinCode) and ktBlock properties
 * for a new-format article.
 *
 * Returns the list of generated part class names (in order).
 */
private fun PromptGenerationContext.generateNewFormatParts(
    article: PromptArticle,
    parts: NewFormatParts,
    classType: ClassName,
    pkg: String,
    props: MutableList<PropertySpec>,
): List<ClassName> {
    val sourcePath = article.payload.path

    // Strip any directive lines from md content — directives are not user-visible output
    fun stripDirective(s: String): String {
        if (AUTO_TOC_DIRECTIVES.none { s.contains(it) }) return s
        return s.lines().filter { it !in AUTO_TOC_DIRECTIVES }.joinToString("\n")
    }

    // Build flat content parts with filters from the parsed article parts
    val contentParts = buildContentParts(parts)
    val rootFilter = parts.rootFilter

    var partCounter = 0
    val partClassNames = mutableListOf<ClassName>()
    val ktPartClasses = mutableListOf<ClassName>()

    for (part in contentParts) {
        val content = if (part.isKotlinBlock) part.content else stripDirective(part.content)
        if (content.isEmpty()) {
            if (part.isKotlinBlock) {
                // Empty kotlin block — still counts for ktBlock numbering but no class
            }
            continue
        }

        // Compose part filter with article root filter so parts carry the effective filter
        // when accessed directly (e.g., in tests via article.parts)
        val effectiveFilter = when {
            rootFilter is IdeFilter.All -> part.filter
            part.filter is IdeFilter.All -> rootFilter
            else -> rootFilter.and(part.filter)
        }

        val idx = partCounter.toString().padStart(3, '0')
        val cls = ClassName(pkg, "${classType.simpleName}Part$idx")

        generateArticlePartClazz(
            content = content,
            classType = cls,
            isKotlinBlock = part.isKotlinBlock,
            filter = effectiveFilter,
            sourcePath = sourcePath,
        )

        partClassNames.add(cls)
        if (part.isKotlinBlock) {
            ktPartClasses.add(cls)
        }
        partCounter++
    }

    // ktBlock{NNN} properties — direct references to kt part classes (single source of truth)
    ktPartClasses.forEachIndexed { index, ktClass ->
        val blockIndex = index.toString().padStart(3, '0')
        props += PropertySpec.builder("ktBlock$blockIndex", promptBaseClass)
            .addKdoc("Kotlin block #%L from %L.", index, sourcePath)
            .getter(FunSpec.getterBuilder().addCode(buildCodeBlock {
                addStatement("return %T()", ktClass)
            }).build())
            .build()
    }

    return partClassNames
}

/**
 * Emits a KotlinPoet [CodeBlock] that constructs an [IdeFilter] instance at runtime.
 */
fun emitFilterConstructor(filter: IdeFilter): CodeBlock = when (filter) {
    is IdeFilter.All -> CodeBlock.of("%T.All", IdeFilter::class.asClassName())
    is IdeFilter.Ide -> {
        val codes = filter.productCodes.sorted().joinToString(", ") { "%S" }
        val codeArgs = filter.productCodes.sorted().toTypedArray()
        val builder = CodeBlock.builder()
        builder.add("%T.Ide(setOf(", IdeFilter::class.asClassName())
        for ((i, code) in filter.productCodes.sorted().withIndex()) {
            if (i > 0) builder.add(", ")
            builder.add("%S", code)
        }
        builder.add("), ")
        builder.add(if (filter.minVersion != null) "%L" else "null", filter.minVersion)
        builder.add(", ")
        builder.add(if (filter.maxVersion != null) "%L" else "null", filter.maxVersion)
        builder.add(")")
        builder.build()
    }
    is IdeFilter.Not -> CodeBlock.builder()
        .add("%T.Not(", IdeFilter::class.asClassName())
        .add(emitFilterConstructor(filter.inner))
        .add(")")
        .build()
    is IdeFilter.And -> CodeBlock.builder()
        .add("%T.And(", IdeFilter::class.asClassName())
        .add(emitFilterConstructor(filter.left))
        .add(", ")
        .add(emitFilterConstructor(filter.right))
        .add(")")
        .build()
    is IdeFilter.Or -> CodeBlock.builder()
        .add("%T.Or(", IdeFilter::class.asClassName())
        .add(emitFilterConstructor(filter.left))
        .add(", ")
        .add(emitFilterConstructor(filter.right))
        .add(")")
        .build()
}

/**
 * Generate a TOC article for a folder. Returns null for empty folders.
 *
 * Member articles are listed as [seeAlsoItems], filtered by their root filter
 * at runtime. The standard [readPayload] renders them under `# See also`.
 * An additional `memberArticles` getter provides direct access to the article instances.
 */
fun PromptGenerationContext.generateTocArticleClazz(
    folder: String,
    memberArticles: List<PromptArticle>,
    packageName: String,
): GeneratedArticleClazz? {
    if (folder.isEmpty() || memberArticles.isEmpty()) return null

    val uriPrefix = folderToUriPrefix(folder)
    val displayName = folderToDisplayName(folder)
    val uri = "mcp-steroid://$uriPrefix"
    val tocName = "$displayName Resources"
    val tocDescription = "Table of contents for all $displayName resources"

    val classType = ClassName(packageName, displayName.replace(" ", "") + "TocArticle")

    val filterType = IdeFilter::class.asClassName()
    val articlePartListType = List::class.asClassName().parameterizedBy(articlePartClass)
    val seeAlsoItemListType = List::class.asClassName().parameterizedBy(seeAlsoItemClass)

    // Title holder class
    val titleHolderClass = ClassName(packageName, classType.simpleName + "Title")
    generateStringPromptClazz(tocName, titleHolderClass)

    // Description holder class
    val descHolderClass = ClassName(packageName, classType.simpleName + "Description")
    generateStringPromptClazz(tocDescription, descHolderClass)

    // Collect member article ClassNames sorted by title
    val memberClassNames = memberArticles
        .sortedBy { articleName(it) }
        .map { articleClassName(it) }

    val props = mutableListOf<PropertySpec>()

    props += PropertySpec.builder("uri", String::class)
        .addModifiers(KModifier.OVERRIDE)
        .initializer("%S", uri)
        .build()

    props += PropertySpec.builder("title", promptBaseClass)
        .addModifiers(KModifier.OVERRIDE)
        .getter(FunSpec.getterBuilder().addCode(buildCodeBlock {
            addStatement("return %T()", titleHolderClass)
        }).build())
        .build()

    props += PropertySpec.builder("filter", filterType)
        .addModifiers(KModifier.OVERRIDE)
        .initializer("%T.All", IdeFilter::class.asClassName())
        .build()

    props += PropertySpec.builder("description", promptBaseClass)
        .addModifiers(KModifier.OVERRIDE)
        .getter(FunSpec.getterBuilder().addCode(buildCodeBlock {
            addStatement("return %T()", descHolderClass)
        }).build())
        .build()

    props += PropertySpec.builder("parts", articlePartListType)
        .addModifiers(KModifier.OVERRIDE)
        .initializer("emptyList()")
        .build()

    // seeAlsoItems: member articles as see-also entries, sorted by title at build time
    props += PropertySpec.builder("seeAlsoItems", seeAlsoItemListType)
        .addModifiers(KModifier.OVERRIDE)
        .getter(FunSpec.getterBuilder().addCode(buildCodeBlock {
            add("return listOf(\n")
            for (cls in memberClassNames) {
                add("  %T(),\n", cls)
            }
            add(").map { it.asSeeAlsoItem() }\n")
        }).build())
        .build()

    // memberArticles getter for direct access
    val memberArticlesListType = List::class.asClassName().parameterizedBy(promptArticleClass)
    props += PropertySpec.builder("memberArticles", memberArticlesListType)
        .getter(FunSpec.getterBuilder().addCode(buildCodeBlock {
            add("return listOf(\n")
            for (cls in memberClassNames) {
                add("  %T(),\n", cls)
            }
            add(")\n")
        }).build())
        .build()

    val typeSpec = TypeSpec.classBuilder(classType)
        .superclass(promptArticleClass)
        .addProperties(props)
        .build()

    val fileSpec = FileSpec.builder(classType)
        .addFileComment("GENERATED FILE - DO NOT EDIT")
        .addType(typeSpec)
        .build()

    writeClazz(fileSpec, classType)
    return GeneratedArticleClazz(folder, "$folder/toc", classType, null, uri)
}
