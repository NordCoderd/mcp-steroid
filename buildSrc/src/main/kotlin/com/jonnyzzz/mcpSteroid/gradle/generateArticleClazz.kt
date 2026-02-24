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
 * Returns true if [path] looks like a section file: the filename contains `-section-`
 * and ends with `.md` or `.kt`.
 *
 * Section files are the building blocks of multi-section articles. They are named like
 * `<article-stem>-section-<id>.(md|kt)` and are merged (in alphabetical order) to form
 * the article's payload content.
 */
fun isSectionFile(path: String): Boolean {
    val name = path.substringAfterLast("/")
    return Regex("""-section-[^/]+\.(md|kt)$""").containsMatchIn(name)
}

/**
 * Derives the article stem path from a section file path.
 *
 * Examples:
 * - `skill/foo-bar-section-0001.md`  →  `skill/foo-bar`
 * - `coding-with-intellij-section-0002.kt`  →  `coding-with-intellij`
 */
fun articleStemFromSection(path: String): String {
    val dir = path.substringBeforeLast("/", missingDelimiterValue = "")
    val name = path.substringAfterLast("/")
    val stem = name.replace(Regex("""-section-[^.]+\.(md|kt)$"""), "")
    return if (dir.isEmpty()) stem else "$dir/$stem"
}

fun groupByArticle(promptClasses: List<GeneratedPromptClazz>): List<PromptArticle> {
    val headerExt = "-header.md"
    val seeAlsoExt = "-see-also.md"

    // Group all files by their article stem.
    // Section files have their -section-XXXX.(md|kt) suffix stripped to find the stem.
    val grouped = promptClasses.groupBy { clazz ->
        val path = clazz.path
        when {
            path.endsWith(headerExt) -> path.removeSuffix(headerExt)
            path.endsWith(seeAlsoExt) -> path.removeSuffix(seeAlsoExt)
            isSectionFile(path) -> articleStemFromSection(path)
            else -> path.removeSuffix(".kts").removeSuffix(".md").removeSuffix(".kt")
        }
    }

    return grouped.mapNotNull { (stem, group) ->
        val header = group.singleOrNull { it.path.endsWith(headerExt) }
        val seeAlso = group.singleOrNull { it.path.endsWith(seeAlsoExt) }
        val sections = group.filter { isSectionFile(it.path) }.sortedBy { it.path }
        val singlePayload = group.singleOrNull {
            !it.path.endsWith(headerExt) && !it.path.endsWith(seeAlsoExt) && !isSectionFile(it.path)
        }

        if (header == null) {
            // New single-file format: one .md file acts as both header and payload.
            // Only applies to files in subdirectories (stem contains '/') — root-level standalone
            // files like mcp-steroid-info.md are not articles.
            val mainFile = singlePayload ?: return@mapNotNull null
            if (mainFile.fileType != "md") return@mapNotNull null
            if (!stem.contains("/")) return@mapNotNull null
            require(sections.isEmpty()) {
                "Article '$stem' has no '-header.md' but has section files. Unsupported combination."
            }
            PromptArticle(header = mainFile, seeAlso = seeAlso, payload = mainFile, sections = emptyList(), newFormat = true)
        } else if (sections.isNotEmpty()) {
            require(singlePayload == null) {
                "Article '$stem' has both section files and a single payload file '${singlePayload?.path}'. " +
                    "Remove the single payload file or the section files."
            }
            PromptArticle(header = header, seeAlso = seeAlso, payload = null, sections = sections)
        } else {
            val payload = singlePayload ?: return@mapNotNull null
            PromptArticle(header = header, seeAlso = seeAlso, payload = payload, sections = emptyList())
        }
    }
}

data class PromptArticle(
    val header: GeneratedPromptClazz,
    val seeAlso: GeneratedPromptClazz?,
    /** Non-null for single-file articles; null when the article uses section files. */
    val payload: GeneratedPromptClazz?,
    /** Non-empty for multi-section articles; empty when the article uses a single payload file. */
    val sections: List<GeneratedPromptClazz> = emptyList(),
    /**
     * True for new single-file format articles where one `.md` file serves as both header and payload.
     * Format: title (line 1), blank (line 2), description (line 3), blank (line 4), content (line 5+).
     */
    val newFormat: Boolean = false,
) {
    init {
        require((sections.isNotEmpty()) xor (payload != null)) {
            "Article must have either section files or a single payload file (not both, not neither). " +
                "Header: ${header.path}"
        }
    }

    /**
     * The canonical payload path used for URI and class-name derivation.
     *
     * - Single-file articles: the actual payload file path.
     * - Section articles: a virtual `<stem>.md` path derived from the first section filename
     *   (same as if the payload existed as a single file with that name).
     */
    val canonicalPayloadPath: String
        get() = when {
            payload != null -> payload.path
            else -> {
                val firstPath = sections.first().path
                val dir = firstPath.substringBeforeLast("/", missingDelimiterValue = "")
                val name = firstPath.substringAfterLast("/")
                val stem = name.replace(Regex("""-section-[^.]+\.(md|kt)$"""), "")
                if (dir.isEmpty()) "$stem.md" else "$dir/$stem.md"
            }
        }

    /**
     * For new-format articles, the payload content is lines 5+ of the single file
     * (skipping title, blank, description, blank).
     */
    val newFormatPayloadContent: String
        get() = payload!!.content.lines().drop(4).joinToString("\n").trimStart('\n')

    /** The primary element for naming purposes (payload or first section). */
    val mainElement: GeneratedPromptClazz get() = payload ?: sections.first()

    /** All non-null generated classes in this article (header, optional see-also, payload or sections). */
    val allClasses: List<GeneratedPromptClazz>
        get() = if (newFormat) {
            // header == payload in new-format; deduplicate by including payload only once
            listOfNotNull(payload, seeAlso)
        } else {
            listOfNotNull(payload, header, seeAlso) + sections
        }

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

/**
 * Derive the URI prefix from a folder name.
 * After the folder rename (removing `-examples` suffix), this is now just the folder name.
 */
fun folderToUriPrefix(folder: String): String {
    return folder
}

/**
 * Derive the display name for a folder (used for TOC headers and resource naming).
 */
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

/**
 * Derive the file stem (filename without extension) from a payload path, kebab-cased.
 * Converts UPPER_CASE names to lower-kebab-case.
 */
fun payloadFileStem(payloadPath: String): String {
    val fileName = payloadPath.substringAfterLast("/")
    val stem = fileName.removeSuffix(".kts").removeSuffix(".md").removeSuffix(".kt")
    // Convert UPPER_CASE_NAME to lower-kebab-case: replace _ with -, lowercase
    return stem.replace('_', '-').lowercase()
}

/**
 * Build the resource URI for an article.
 */
fun buildArticleUri(folder: String, payloadPath: String): String {
    val prefix = folderToUriPrefix(folder)
    val stem = payloadFileStem(payloadPath)
    return if (prefix.isEmpty()) {
        "mcp-steroid://$stem"
    } else {
        "mcp-steroid://$prefix/$stem"
    }
}

/**
 * Compute the generated article class name for a [PromptArticle].
 *
 * The name is derived from the article's [PromptArticle.canonicalPayloadPath] stem,
 * so it is the same regardless of whether the article uses a single file or multiple sections.
 * This matches the class name that [generateArticleClazz] will create.
 */
fun articleClassName(article: PromptArticle): ClassName {
    val packageName = article.allClasses.map { it.clazzName.packageName }.distinct().single()
    val stem = article.canonicalPayloadPath
        .substringAfterLast("/")
        .removeSuffix(".kts")
        .removeSuffix(".md")
        .removeSuffix(".kt")
    val className = stem.toPromptClassName() + "PromptArticle"
    return ClassName(packageName, className)
}

/**
 * Extract the article name (title) from an article.
 *
 * - New-format: first line of the single `.md` file.
 * - Old-format: first line of the `-header.md` file.
 */
fun articleName(article: PromptArticle): String {
    return if (article.newFormat) {
        article.payload!!.content.trim().lineSequence().first().trim()
    } else {
        article.header.content.trim().lineSequence().first().trim()
    }
}

/**
 * Extract the full description from an article.
 *
 * - New-format: single description line (line 3 of the `.md` file, after title+blank).
 * - Old-format: all lines after the title in the `-header.md` file, joined.
 */
fun articleDescription(article: PromptArticle): String {
    return if (article.newFormat) {
        article.payload!!.content.trim().lineSequence()
            .drop(1)
            .dropWhile { it.isBlank() }
            .firstOrNull()?.trim() ?: ""
    } else {
        article.header.content.trim().lineSequence()
            .drop(1)
            .dropWhile { it.isBlank() }
            .joinToString("\n")
            .trim()
    }
}

/**
 * Extract the first line of description from an article.
 *
 * - New-format: the single description line (line 3 of the `.md` file).
 * - Old-format: first line after the title in the `-header.md` file.
 */
fun articleDescriptionFirstLine(article: PromptArticle): String {
    return if (article.newFormat) {
        article.payload!!.content.trim().lineSequence()
            .drop(1)
            .dropWhile { it.isBlank() }
            .firstOrNull()?.trim() ?: ""
    } else {
        article.header.content.trim().lineSequence()
            .drop(1)
            .dropWhile { it.isBlank() }
            .firstOrNull()?.trim() ?: ""
    }
}

/**
 * Build a see-also markdown entry for an article.
 */
fun buildSeeAlsoLine(folder: String, article: PromptArticle): String {
    val uri = buildArticleUri(folder, article.canonicalPayloadPath)
    val name = articleName(article)
    val desc = articleDescriptionFirstLine(article)
    val suffix = if (desc.isNotEmpty()) " - $desc" else ""
    return "- [$name]($uri)$suffix"
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

    // Determine the payload class:
    // - New-format articles: generate an inline class holding lines 5+ (skipping title+desc header).
    // - Single-file articles (old format): use the already-generated class for that file.
    // - Section articles: generate a new inline class holding the merged content.
    val payloadClassName: ClassName = when {
        article.newFormat -> {
            val mergedClass = ClassName(pkg, classType.simpleName + "Payload")
            generateStringPromptClazz(article.newFormatPayloadContent, mergedClass)
            mergedClass
        }
        article.sections.isNotEmpty() -> {
            val mergedClass = ClassName(pkg, classType.simpleName + "Payload")
            generateSectionDelegatePayloadClazz(article.sections, mergedClass)
            mergedClass
        }
        else -> article.payload!!.clazzName
    }

    // payload property
    props += PropertySpec.builder("payload", promptBaseClass)
        .addModifiers(KModifier.OVERRIDE)
        .getter(FunSpec.getterBuilder().addCode(buildCodeBlock {
            addStatement("return %T()", payloadClassName)
        }).build())
        .build()

    // uri property
    props += PropertySpec.builder("uri", String::class)
        .addModifiers(KModifier.OVERRIDE)
        .initializer("%S", uri)
        .build()

    // name property (short, pre-defined string constant)
    props += PropertySpec.builder("name", String::class)
        .addModifiers(KModifier.OVERRIDE)
        .initializer("%S", name)
        .build()

    // description property - PromptBase? holder for non-empty content
    if (description.isNotEmpty()) {
        val descHolderClass = ClassName(pkg, classType.simpleName + "Description")
        generateStringPromptClazz(description, descHolderClass)
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

    // seeAlso property - PromptBase? holder for non-empty content
    if (seeAlsoContent.isNotEmpty()) {
        val seeAlsoHolderClass = ClassName(pkg, classType.simpleName + "SeeAlso")
        generateStringPromptClazz(seeAlsoContent, seeAlsoHolderClass)
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

    // ktBlock{NNN} properties - PromptBase getters for each ```kotlin``` block in new-format articles.
    // Lets tests (and callers) access raw Kotlin code via .readPrompt(), consistent with payload/description/seeAlso.
    if (article.newFormat) {
        val blocks = extractKotlinBlocks(article.newFormatPayloadContent)
        blocks.forEachIndexed { index, blockContent ->
            val blockIndex = index.toString().padStart(3, '0')
            val holderClass = ClassName(pkg, classType.simpleName + "KtBlock${blockIndex}")
            generateStringPromptClazz(blockContent.trimEnd(), holderClass, "text/x-kotlin")
            props += PropertySpec.builder("ktBlock${blockIndex}", promptBaseClass)
                .addKdoc("Raw Kotlin content of block #%L from %L", index, article.canonicalPayloadPath)
                .getter(FunSpec.getterBuilder().addCode(buildCodeBlock {
                    addStatement("return %T()", holderClass)
                }).build())
                .build()
        }
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
    return GeneratedArticleClazz(folder, article.canonicalPayloadPath, classType, article, uri)
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

    // Generate payload holder with TOC content
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

    // description - PromptBase holder for TOC description
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
