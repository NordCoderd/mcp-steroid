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
    val article: PromptArticle,
    val uri: String,
    val seeAlsoContent: String,
) : Generated {
    override val entryName: String get() = article.mainElement.entryName
}

/**
 * Derive the URI prefix from a folder name: strip `-examples` suffix.
 */
fun folderToUriPrefix(folder: String): String {
    if (folder.isEmpty()) return ""
    return folder.removeSuffix("-examples")
}

/**
 * Derive the name prefix from a folder for resource naming.
 */
fun folderToNamePrefix(folder: String): String {
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
        else -> "${prefix.titleCase()}: "
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

fun PromptGenerationContext.generateArticleClazz(
    folder: String,
    article: PromptArticle,
    seeAlsoContent: String,
): GeneratedArticleClazz {
    val uri = buildArticleUri(folder, article.mainElement.path)

    val classType = run {
        val packageName = article.allClasses.map { it.clazzName.packageName }.distinct().single()
        val className = article.mainElement.clazzName.simpleName + "Article"
        ClassName(packageName, className)
    }

    val props = mutableListOf<PropertySpec>()

    // header property
    props += PropertySpec.builder("header", promptBaseClass)
        .addModifiers(KModifier.OVERRIDE)
        .getter(FunSpec.getterBuilder().addCode(buildCodeBlock {
            addStatement("return %T()", article.header.clazzName)
        }).build())
        .build()

    // payload property
    props += PropertySpec.builder("payload", promptBaseClass)
        .addModifiers(KModifier.OVERRIDE)
        .getter(FunSpec.getterBuilder().addCode(buildCodeBlock {
            addStatement("return %T()", article.payload.clazzName)
        }).build())
        .build()

    // seeAlso property (nullable)
    if (article.seeAlso != null) {
        props += PropertySpec.builder("seeAlsoFile", promptBaseClass.copy(nullable = true))
            .addModifiers(KModifier.OVERRIDE)
            .getter(FunSpec.getterBuilder().addCode(buildCodeBlock {
                addStatement("return %T()", article.seeAlso.clazzName)
            }).build())
            .build()
    } else {
        props += PropertySpec.builder("seeAlsoFile", promptBaseClass.copy(nullable = true))
            .addModifiers(KModifier.OVERRIDE)
            .getter(FunSpec.getterBuilder().addCode(buildCodeBlock {
                addStatement("return null")
            }).build())
            .build()
    }

    // uri property
    props += PropertySpec.builder("uri", String::class)
        .addModifiers(KModifier.OVERRIDE)
        .initializer("%S", uri)
        .build()

    // seeAlsoContent property (auto-generated + manual merged)
    props += PropertySpec.builder("seeAlsoContent", String::class)
        .addModifiers(KModifier.OVERRIDE)
        .initializer("%S", seeAlsoContent)
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
    return GeneratedArticleClazz(folder, article.mainElement.path, classType, article, uri, seeAlsoContent)
}
