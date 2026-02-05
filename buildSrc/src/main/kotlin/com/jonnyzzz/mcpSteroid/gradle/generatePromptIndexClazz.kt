/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.gradle

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.WildcardTypeName
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.buildCodeBlock
import kotlin.reflect.KClass

data class GeneratedPromptIndexClazz(
    val folder: String,
    val clazzName: ClassName,
)

fun PromptGenerationContext.generatePromptIndexClazz(
    folder: String,
    prompts: List<GeneratedPromptClazz>,
    articles: List<GeneratedArticleClazz>,
): GeneratedPromptIndexClazz {

    println("generate prompt index $folder: ${prompts.joinToString { it.folder }}")

    val classType = run {
        val clazzName = if (folder.isEmpty()) "Root" else folder.toPromptClassName()
        ClassName(packageName + ".index", "PromptIndex" + clazzName)
    }

    val kclassType = KClass::class.asClassName().parameterizedBy(
        WildcardTypeName.producerOf(promptBaseClass)
    )

    val registryType = Map::class.asClassName().parameterizedBy(String::class.asClassName(), promptReaderClass)
    val registryProperty = PropertySpec.builder("files", registryType)
        .addModifiers(KModifier.OVERRIDE)
        .getter(
            FunSpec.getterBuilder()
                .addCode(
                    buildCodeBlock {
                        controlFlow("return buildMap") {
                            prompts
                                .sortedWith(compareBy<GeneratedPromptClazz>({ it.fileType }, { it.clazzName }))
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
                            articles
                                .sortedWith(compareBy<GeneratedArticleClazz>({ it.path }))
                                .forEach { r ->
                                    addStatement("put(%S, %T())", r.path.removePrefix(r.folder).trim('/'), r.clazzName)
                                }
                        }
                    }).build()
        )
        .build()

    val typeSpec = TypeSpec.classBuilder(classType)
        .superclass(promptIndexBaseClass)
        .addProperty(registryProperty)
        .addProperty(articleProperty)
        .build()

    val fileSpec = FileSpec.builder(classType.packageName, classType.simpleName + ".kt")
        .addFileComment("GENERATED FILE - DO NOT EDIT")
        .addType(typeSpec)
        .build()

    writeClazz(fileSpec, classType)
    return GeneratedPromptIndexClazz(folder, classType)
}
