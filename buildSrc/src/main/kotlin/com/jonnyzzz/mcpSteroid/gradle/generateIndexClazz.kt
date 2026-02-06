/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.gradle

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.buildCodeBlock


data class GeneratedIndexClazz(
    val folder: String,
    val clazzName: ClassName,
)

fun PromptGenerationContext.generateIndexClazz(
    folder: String,
    prompts: List<GeneratedPromptClazz>,
    articles: List<GeneratedArticleClazz>,
    tocContent: String,
): GeneratedIndexClazz {

    println("generate prompt index $folder: ${prompts.joinToString { it.folder }}")

    val classType = run {
        val clazzName = if (folder.isEmpty()) "Root" else folder.toPromptClassName()
        val packageName = (articles.flatMap { it.article.allClasses } + prompts).map { it.clazzName.packageName }.distinct().single()
        ClassName(packageName, clazzName + "Index")
    }

    val sortedPrompts = prompts
        .sortedWith(compareBy<GeneratedPromptClazz>({ it.fileType }, { it.clazzName }))

    val sortedArticles = articles
        .sortedWith(compareBy<GeneratedArticleClazz>({ it.path }))

    val registryType = Map::class.asClassName().parameterizedBy(String::class.asClassName(), promptBaseClass)
    val registryProperty = PropertySpec.builder("files", registryType)
        .addModifiers(KModifier.OVERRIDE)
        .getter(
            FunSpec.getterBuilder()
                .addCode(
                    buildCodeBlock {
                        controlFlow("return buildMap") {
                            sortedPrompts
                                .forEach { r ->
                                    addStatement("put(%S, %T())", r.path.removePrefix(r.folder).trim('/'), r.clazzName)
                                }
                        }
                    }).build()
        )
        .build()

    val articleType = Map::class.asClassName().parameterizedBy(String::class.asClassName(), promptArticleClass)
    val articleProperty = PropertySpec.builder("articles", articleType)
        .addModifiers(KModifier.OVERRIDE)
        .getter(
            FunSpec.getterBuilder()
                .addCode(
                    buildCodeBlock {
                        controlFlow("return buildMap") {
                            sortedArticles
                                .forEach { r ->
                                    addStatement("put(%S, %T())", r.path.removePrefix(r.folder).trim('/'), r.clazzName)
                                }
                        }
                    }).build()
        )
        .build()

    val typedGetters = (sortedArticles + sortedPrompts).map { e ->
        PropertySpec.builder(e.entryName, e.clazzName)
            .getter(
                FunSpec.getterBuilder()
                    .addCode(
                        buildCodeBlock {
                            addStatement("return %T()", e.clazzName)
                        }
                    ).build()
            )
            .build()
    }

    // TOC properties (all computed at build time)
    val uriPrefix = folderToUriPrefix(folder)
    val displayName = folderToDisplayName(folder)

    val tocUriProperty = PropertySpec.builder("tocUri", String::class)
        .addModifiers(KModifier.OVERRIDE)
        .initializer("%S", if (uriPrefix.isEmpty()) "" else "mcp-steroid://$uriPrefix")
        .build()

    val tocNameProperty = PropertySpec.builder("tocName", String::class)
        .addModifiers(KModifier.OVERRIDE)
        .initializer("%S", if (displayName.isEmpty()) "" else "$displayName Resources")
        .build()

    // tocContent - PromptBase holder for non-empty content
    val tocContentProperty = if (tocContent.isNotEmpty()) {
        val tocHolderClass = ClassName(classType.packageName, classType.simpleName + "TocContent")
        generateStringPromptClazz(tocContent, tocHolderClass)
        PropertySpec.builder("tocContent", String::class)
            .addModifiers(KModifier.OVERRIDE)
            .getter(FunSpec.getterBuilder().addCode(buildCodeBlock {
                addStatement("return %T().readPrompt()", tocHolderClass)
            }).build())
            .build()
    } else {
        PropertySpec.builder("tocContent", String::class)
            .addModifiers(KModifier.OVERRIDE)
            .initializer("%S", "")
            .build()
    }

    val typeSpec = TypeSpec.classBuilder(classType)
        .superclass(promptIndexBaseClass)
        .addProperty(registryProperty)
        .addProperty(articleProperty)
        .addProperty(tocUriProperty)
        .addProperty(tocNameProperty)
        .addProperty(tocContentProperty)
        .addProperties(typedGetters)
        .build()

    val fileSpec = FileSpec.builder(classType.packageName, classType.simpleName + ".kt")
        .addFileComment("GENERATED FILE - DO NOT EDIT")
        .addType(typeSpec)
        .build()

    writeClazz(fileSpec, classType)
    return GeneratedIndexClazz(folder, classType)
}
