/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.gradle

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.buildCodeBlock

fun groupByArticle(promptClasses: List<GeneratedPromptClazz>): List<PromptArticle> {
    val headerExt = "-header.md"
    val seeAlsoExt = "-see-also.md"

    // Group files by stripping known suffixes to find article stems
    val articles = promptClasses.groupBy { clazz ->
        val path = clazz.path
        when {
            path.endsWith(headerExt) -> path.removeSuffix(headerExt)
            path.endsWith(seeAlsoExt) -> path.removeSuffix(seeAlsoExt)
            else -> path.removeSuffix(".kts").removeSuffix(".md")
        }
    }.mapNotNull { (stem, group) ->
        val header = group.singleOrNull { it.path.endsWith(headerExt) } ?: return@mapNotNull null
        val seeAlso = group.singleOrNull { it.path.endsWith(seeAlsoExt) }

        // Payload is whichever file is NOT the header and NOT the see-also
        val payload = group.singleOrNull { !it.path.endsWith(headerExt) && !it.path.endsWith(seeAlsoExt) }
            ?: return@mapNotNull null

        // Must have at least header + payload (see-also is optional)
        PromptArticle(
            header = header,
            seeAlso = seeAlso,
            payload = payload,
        )
    }
    return articles
}

data class PromptArticle(
    val header: GeneratedPromptClazz,
    val seeAlso: GeneratedPromptClazz?,
    val payload: GeneratedPromptClazz,
) {
    val mainElement get() = payload

    /** All non-null generated classes in this article */
    val allClasses: List<GeneratedPromptClazz>
        get() = listOfNotNull(payload, header, seeAlso)
}

data class GeneratedArticleClazz(
    val folder: String,
    val path: String,
    override val clazzName: ClassName,
    val article: PromptArticle?,
    val uri: String,
) : Generated {
    override val entryName: String
        get() = article?.mainElement?.entryName
            ?: path.substringAfterLast("/").toPromptIdentifierName()
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
    val stem = fileName.removeSuffix(".kts").removeSuffix(".md")
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
 * This is deterministic and matches the class name that [generateArticleClazz] will create.
 */
fun articleClassName(article: PromptArticle): ClassName {
    val packageName = article.allClasses.map { it.clazzName.packageName }.distinct().single()
    val className = article.mainElement.clazzName.simpleName + "Article"
    return ClassName(packageName, className)
}

/**
 * Extract the article name from a header file's content (first line).
 */
fun articleName(article: PromptArticle): String {
    return article.header.content.trim().lineSequence().first().trim()
}

/**
 * Extract the full description from a header file's content (all lines after title, trimmed).
 * Skips blank separator lines between title and description.
 */
fun articleDescription(article: PromptArticle): String {
    return article.header.content.trim().lineSequence()
        .drop(1)
        .dropWhile { it.isBlank() }
        .joinToString("\n")
        .trim()
}

/**
 * Extract the first line of description from a header file's content (second line onward).
 */
fun articleDescriptionFirstLine(article: PromptArticle): String {
    return article.header.content.trim().lineSequence()
        .drop(1)
        .dropWhile { it.isBlank() }
        .firstOrNull()?.trim() ?: ""
}

/**
 * Build a see-also markdown entry for an article.
 */
fun buildSeeAlsoLine(folder: String, article: PromptArticle): String {
    val uri = buildArticleUri(folder, article.mainElement.path)
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
    val uri = buildArticleUri(folder, article.mainElement.path)
    val name = articleName(article)
    val description = articleDescription(article)

    val classType = articleClassName(article)
    val pkg = classType.packageName

    val props = mutableListOf<PropertySpec>()

    // payload property
    props += PropertySpec.builder("payload", promptBaseClass)
        .addModifiers(KModifier.OVERRIDE)
        .getter(FunSpec.getterBuilder().addCode(buildCodeBlock {
            addStatement("return %T()", article.payload.clazzName)
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

    // path property (payload file path)
    props += PropertySpec.builder("path", String::class)
        .addModifiers(KModifier.OVERRIDE)
        .initializer("%S", article.payload.path)
        .build()

    // mimeType property (derived from payload file extension)
    val mimeType = when (article.payload.fileType) {
        "kts" -> "text/x-kotlin"
        "md" -> "text/markdown"
        else -> "text/plain"
    }
    props += PropertySpec.builder("mimeType", String::class)
        .addModifiers(KModifier.OVERRIDE)
        .initializer("%S", mimeType)
        .build()

    // description property - PromptBase holder for non-empty content
    if (description.isNotEmpty()) {
        val descHolderClass = ClassName(pkg, classType.simpleName + "Description")
        generateStringPromptClazz(description, descHolderClass)
        props += PropertySpec.builder("description", String::class)
            .addModifiers(KModifier.OVERRIDE)
            .getter(FunSpec.getterBuilder().addCode(buildCodeBlock {
                addStatement("return %T().readPrompt()", descHolderClass)
            }).build())
            .build()
    } else {
        props += PropertySpec.builder("description", String::class)
            .addModifiers(KModifier.OVERRIDE)
            .initializer("%S", "")
            .build()
    }

    // seeAlsoContent property - PromptBase holder for non-empty content
    if (seeAlsoContent.isNotEmpty()) {
        val seeAlsoHolderClass = ClassName(pkg, classType.simpleName + "SeeAlso")
        generateStringPromptClazz(seeAlsoContent, seeAlsoHolderClass)
        props += PropertySpec.builder("seeAlsoContent", String::class)
            .addModifiers(KModifier.OVERRIDE)
            .getter(FunSpec.getterBuilder().addCode(buildCodeBlock {
                addStatement("return %T().readPrompt()", seeAlsoHolderClass)
            }).build())
            .build()
    } else {
        props += PropertySpec.builder("seeAlsoContent", String::class)
            .addModifiers(KModifier.OVERRIDE)
            .initializer("%S", "")
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
    return GeneratedArticleClazz(folder, article.mainElement.path, classType, article, uri)
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

    props += PropertySpec.builder("path", String::class)
        .addModifiers(KModifier.OVERRIDE)
        .initializer("%S", "$folder/$tocEntryName")
        .build()

    props += PropertySpec.builder("mimeType", String::class)
        .addModifiers(KModifier.OVERRIDE)
        .initializer("%S", "text/markdown")
        .build()

    props += PropertySpec.builder("description", String::class)
        .addModifiers(KModifier.OVERRIDE)
        .initializer("%S", tocDescription)
        .build()

    props += PropertySpec.builder("seeAlsoContent", String::class)
        .addModifiers(KModifier.OVERRIDE)
        .initializer("%S", "")
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
