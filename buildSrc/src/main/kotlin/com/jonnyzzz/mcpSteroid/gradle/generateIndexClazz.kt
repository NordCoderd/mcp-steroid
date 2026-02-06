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
): GeneratedIndexClazz {

    println("generate prompt index $folder: ${prompts.joinToString { it.folder }}")

    val classType = run {
        val clazzName = if (folder.isEmpty()) "Root" else folder.toPromptClassName()
        val packageName = (articles.flatMap { it.article?.allClasses ?: emptyList() } + prompts).map { it.clazzName.packageName }.distinct().single()
        ClassName(packageName, clazzName + "Index")
    }

    val sortedPrompts = prompts
        .sortedWith(compareBy<GeneratedPromptClazz>({ it.fileType }, { it.clazzName }))

    val sortedArticles = articles
        .sortedWith(compareBy<GeneratedArticleClazz>({ it.path }))

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

    val typeSpec = TypeSpec.classBuilder(classType)
        .superclass(promptIndexBaseClass)
        .addProperty(articleProperty)
        .addProperties(typedGetters)
        .build()

    val fileSpec = FileSpec.builder(classType.packageName, classType.simpleName + ".kt")
        .addFileComment("GENERATED FILE - DO NOT EDIT")
        .addType(typeSpec)
        .build()

    writeClazz(fileSpec, classType)
    return GeneratedIndexClazz(folder, classType)
}
