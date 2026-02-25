/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.gradle

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
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
 * Title
 *
 * Short description (≤200 chars)
 *
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
    val description: String,
    /** Markdown segments interleaved with kotlin blocks; length = ktBodyParts.size + 1 */
    val mdBodyParts: List<String>,
    /** Kotlin code blocks (without fence lines), in order */
    val ktBodyParts: List<String>,
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
    require(lines[1].isBlank()) { "Line 2 must be blank, got: '${lines[1]}'" }
    val description = lines[2].trim()
    require(lines[3].isBlank()) { "Line 4 must be blank, got: '${lines[3]}'" }

    // lines().joinToString("\n") round-trips correctly:
    // "text\n".lines() = ["text", ""] → joinToString → "text\n"
    // "text".lines()   = ["text"]     → joinToString → "text"
    val bodyAndSeeAlso = lines.drop(4).joinToString("\n")

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
    val ktParts = mutableListOf<String>()

    var remaining = body
    while (true) {
        val ktStart = remaining.indexOf("```kotlin\n")
        if (ktStart < 0) {
            mdParts.add(remaining)
            break
        }
        mdParts.add(remaining.substring(0, ktStart))
        val codeStart = ktStart + "```kotlin\n".length
        val closingIdx = remaining.indexOf("\n```", codeStart)
        require(closingIdx >= 0) { "Unclosed ```kotlin block in article body" }
        ktParts.add(remaining.substring(codeStart, closingIdx + 1))
        remaining = remaining.substring(closingIdx + "\n```".length)
    }

    return NewFormatParts(title, description, mdParts, ktParts, seeAlsoManual)
}

/**
 * Assembles the full file content from [NewFormatParts].
 * Used at build time to assert [parseNewFormatArticleParts] round-trips correctly.
 */
fun assembleFullContent(parts: NewFormatParts): String = buildString {
    append(parts.title)
    append("\n\n")
    append(parts.description)
    append("\n\n")
    for (i in parts.mdBodyParts.indices) {
        val md = parts.mdBodyParts[i]
        if (md.isNotEmpty()) append(md)
        if (i < parts.ktBodyParts.size) {
            append("```kotlin\n")
            append(parts.ktBodyParts[i])
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

data class GeneratedArticleClazz(
    val folder: String,
    val path: String,
    override val clazzName: ClassName,
    val article: PromptArticle?,
    val uri: String,
    val hasDescription: Boolean = false,
    val hasSeeAlso: Boolean = false,
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

fun buildSeeAlsoLine(folder: String, article: PromptArticle): String {
    val uri = buildArticleUri(folder, article.canonicalPayloadPath)
    val name = articleName(article)
    val desc = articleDescriptionFirstLine(article)
    val suffix = if (desc.isNotEmpty()) " - $desc" else ""
    return "- [$name]($uri)$suffix"
}

/**
 * Combines the manual see-also content (from the file's `# See also` section) with the
 * auto-generated sibling links into a single string for the `seeAlso` property.
 *
 * Both inputs may be absent independently:
 * - [manual] is null if the file has no `# See also` section.
 * - [generated] is empty if all siblings are already covered by the manual section.
 */
private fun buildCombinedSeeAlso(manual: String?, generated: String): String {
    val m = manual?.trimEnd('\n')
    return when {
        m != null && generated.isNotEmpty() -> "$m\n\n$generated"
        m != null -> m
        generated.isNotEmpty() -> generated
        else -> ""
    }
}

fun PromptGenerationContext.generateArticleClazz(
    folder: String,
    article: PromptArticle,
    seeAlsoContent: String,
): GeneratedArticleClazz {
    val uri = buildArticleUri(folder, article.canonicalPayloadPath)
    val name = articleName(article)
    val description = articleDescription(article)

    val classType = articleClassName(article)
    val pkg = classType.packageName

    val props = mutableListOf<PropertySpec>()
    val nullablePromptBase = promptBaseClass.copy(nullable = true)

    // Description holder class
    val descHolderClass = ClassName(pkg, classType.simpleName + "Description")
    if (description.isNotEmpty()) {
        generateStringPromptClazz(description, descHolderClass, sourcePath = article.payload.path)
    }

    // Parse parts, assert round-trip, generate payload + kt-block properties
    val content = article.payload.content
    val parts = parseNewFormatArticleParts(content)

    val assembled = assembleFullContent(parts)
    require(assembled == content) {
        "Build-time assertion failed for ${article.payload.path}: " +
            "assembled parts differ from source file content.\n" +
            "Expected length=${content.length}, assembled length=${assembled.length}.\n" +
            "First diff at char ${content.zip(assembled).indexOfFirst { (a, b) -> a != b }}"
    }

    generateNewFormatParts(article, parts, classType, pkg, props)
    val effectiveSeeAlsoContent = buildCombinedSeeAlso(parts.seeAlsoManual, seeAlsoContent)

    // uri, name, description, seeAlso properties — same for all formats
    props += PropertySpec.builder("uri", String::class)
        .addModifiers(KModifier.OVERRIDE)
        .initializer("%S", uri)
        .build()

    props += PropertySpec.builder("name", String::class)
        .addModifiers(KModifier.OVERRIDE)
        .initializer("%S", name)
        .build()

    if (description.isNotEmpty()) {
        props += PropertySpec.builder("description", nullablePromptBase)
            .addModifiers(KModifier.OVERRIDE)
            .getter(FunSpec.getterBuilder().addCode(buildCodeBlock {
                addStatement("return %T()", descHolderClass)
            }).build())
            .build()
    } else {
        props += PropertySpec.builder("description", nullablePromptBase)
            .addModifiers(KModifier.OVERRIDE)
            .initializer("null")
            .build()
    }

    if (effectiveSeeAlsoContent.isNotEmpty()) {
        val seeAlsoHolderClass = ClassName(pkg, classType.simpleName + "SeeAlso")
        generateStringPromptClazz(effectiveSeeAlsoContent, seeAlsoHolderClass, sourcePath = article.canonicalPayloadPath)
        props += PropertySpec.builder("seeAlso", nullablePromptBase)
            .addModifiers(KModifier.OVERRIDE)
            .getter(FunSpec.getterBuilder().addCode(buildCodeBlock {
                addStatement("return %T()", seeAlsoHolderClass)
            }).build())
            .build()
    } else {
        props += PropertySpec.builder("seeAlso", nullablePromptBase)
            .addModifiers(KModifier.OVERRIDE)
            .initializer("null")
            .build()
    }

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
        hasDescription = description.isNotEmpty(),
        hasSeeAlso = effectiveSeeAlsoContent.isNotEmpty(),
    )
}

/**
 * Generates payload and ktBlock* properties for a new-format article using the parts architecture.
 *
 * Parts generated:
 * - `{Stem}PromptArticleKtPart{NNN}` — each kotlin code block (without fences)
 * - `{Stem}PromptArticleMdPart{NNN}` — each non-empty markdown segment
 * - `{Stem}PromptArticlePayload` — assembles body (lines 5+, without `# See also`) via buildString
 *
 * The `payload` property returns `{Stem}PromptArticlePayload()`.
 * The `ktBlock{NNN}` properties directly reference `{Stem}PromptArticleKtPart{NNN}`.
 *
 * The description part class is assumed to be generated by the caller before this function.
 */
private fun PromptGenerationContext.generateNewFormatParts(
    article: PromptArticle,
    parts: NewFormatParts,
    classType: ClassName,
    pkg: String,
    props: MutableList<PropertySpec>,
) {
    val sourcePath = article.payload!!.path

    // Strip any directive lines from md content — directives are not user-visible output
    fun stripDirective(s: String): String {
        if (AUTO_TOC_DIRECTIVES.none { s.contains(it) }) return s
        return s.lines().filter { it !in AUTO_TOC_DIRECTIVES }.joinToString("\n")
    }

    // Generate md body part classes (non-empty only)
    val mdPartClasses = parts.mdBodyParts.mapIndexed { i, md ->
        val stripped = stripDirective(md)
        if (stripped.isNotEmpty()) {
            val idx = i.toString().padStart(3, '0')
            val cls = ClassName(pkg, "${classType.simpleName}MdPart$idx")
            generateStringPromptClazz(stripped, cls, sourcePath = sourcePath)
            cls
        } else null
    }

    // Generate kt body part classes
    val ktPartClasses = parts.ktBodyParts.mapIndexed { i, code ->
        val idx = i.toString().padStart(3, '0')
        val cls = ClassName(pkg, "${classType.simpleName}KtPart$idx")
        generateStringPromptClazz(code, cls, mimeType = "text/x-kotlin", sourcePath = sourcePath)
        cls
    }

    // Generate a payload class that assembles the body via buildString (no see-also)
    val payloadClass = ClassName(pkg, "${classType.simpleName}Payload")
    generateNewFormatPayloadClass(payloadClass, mdPartClasses, ktPartClasses, sourcePath)

    props += PropertySpec.builder("payload", promptBaseClass)
        .addModifiers(KModifier.OVERRIDE)
        .getter(FunSpec.getterBuilder().addCode(buildCodeBlock {
            addStatement("return %T()", payloadClass)
        }).build())
        .build()

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
}

/**
 * Generates a [PromptBase] subclass that assembles the article body (lines 5+, without `# See also`)
 * by calling each part's `readPrompt()` and concatenating with proper fence markers.
 *
 * Using explicit `buildString` rather than wrapping parts in a list keeps the generated code
 * readable and avoids the need for [ConstPromptBase].
 */
private fun PromptGenerationContext.generateNewFormatPayloadClass(
    classType: ClassName,
    mdPartClasses: List<ClassName?>,
    ktPartClasses: List<ClassName>,
    sourcePath: String,
) {
    val readFun = FunSpec.builder("readPromptInternal")
        .addModifiers(KModifier.OVERRIDE)
        .returns(String::class)
        .addCode(buildCodeBlock {
            controlFlow("return buildString") {
                for (i in mdPartClasses.indices) {
                    val mdClass = mdPartClasses[i]
                    if (mdClass != null) {
                        addStatement("append(%T().readPrompt())", mdClass)
                    }
                    if (i < ktPartClasses.size) {
                        addStatement("append(%S)", "```kotlin\n")
                        addStatement("append(%T().readPrompt())", ktPartClasses[i])
                        addStatement("append(%S)", "```")
                    }
                }
            }
        })
        .build()

    val mimeTypeProp = PropertySpec.builder("mimeType", String::class)
        .addModifiers(KModifier.OVERRIDE)
        .initializer("%S", "text/markdown")
        .build()

    val typeSpec = TypeSpec.classBuilder(classType)
        .addKdoc("Payload body (lines 5+, without `# See also`) assembled from parts of %L.", sourcePath)
        .superclass(promptBaseClass)
        .addProperty(mimeTypeProp)
        .addFunction(readFun)
        .build()

    val fileSpec = FileSpec.builder(classType)
        .addFileComment("GENERATED FILE - DO NOT EDIT")
        .addType(typeSpec)
        .build()

    writeClazz(fileSpec, classType)
}

/**
 * Generate a TOC article for a folder. Returns null for empty folders.
 */
fun PromptGenerationContext.generateTocArticleClazz(
    folder: String,
    tocContent: String,
    packageName: String,
): GeneratedArticleClazz? {
    if (folder.isEmpty() || tocContent.isEmpty()) return null

    val uriPrefix = folderToUriPrefix(folder)
    val displayName = folderToDisplayName(folder)
    val uri = "mcp-steroid://$uriPrefix"
    val tocName = "$displayName Resources"
    val tocDescription = "Table of contents for all $displayName resources"
    val tocEntryName = "toc"

    val classType = ClassName(packageName, displayName.replace(" ", "") + "TocArticle")

    val payloadHolderClass = ClassName(packageName, classType.simpleName + "Payload")
    generateStringPromptClazz(tocContent, payloadHolderClass)

    val nullablePromptBase = promptBaseClass.copy(nullable = true)
    val props = mutableListOf<PropertySpec>()

    props += PropertySpec.builder("payload", promptBaseClass)
        .addModifiers(KModifier.OVERRIDE)
        .getter(FunSpec.getterBuilder().addCode(buildCodeBlock {
            addStatement("return %T()", payloadHolderClass)
        }).build())
        .build()

    props += PropertySpec.builder("uri", String::class)
        .addModifiers(KModifier.OVERRIDE)
        .initializer("%S", uri)
        .build()

    props += PropertySpec.builder("name", String::class)
        .addModifiers(KModifier.OVERRIDE)
        .initializer("%S", tocName)
        .build()

    val descHolderClass = ClassName(packageName, classType.simpleName + "Description")
    generateStringPromptClazz(tocDescription, descHolderClass)
    props += PropertySpec.builder("description", nullablePromptBase)
        .addModifiers(KModifier.OVERRIDE)
        .getter(FunSpec.getterBuilder().addCode(buildCodeBlock {
            addStatement("return %T()", descHolderClass)
        }).build())
        .build()

    props += PropertySpec.builder("seeAlso", nullablePromptBase)
        .addModifiers(KModifier.OVERRIDE)
        .initializer("null")
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
    return GeneratedArticleClazz(folder, "$folder/$tocEntryName", classType, null, uri)
}
