/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.gradle

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.asTypeName
import com.squareup.kotlinpoet.buildCodeBlock
import java.io.File
import kotlin.math.absoluteValue
import kotlin.random.Random

fun groupByArticle(promptClasses: List<GeneratedPromptClazz>): List<PromptArticle> {
    val ktsExt = ".kts"
    val headerExt = "-header.md"
    val seeAlsoExt = "-see-also.md"

    val articles = promptClasses.groupBy {
        it.path
            .removeSuffix(headerExt)
            .removeSuffix(seeAlsoExt)
            .removeSuffix(ktsExt)
    }.mapNotNull {
        //skip 1-element groups
        if (it.value.size == 1) return@mapNotNull null
        if (it.value.size != 3) error("Invalid group: " + it)

        PromptArticle(
            header = it.value.single { it.path.endsWith(headerExt) },
            seeAlso = it.value.single { it.path.endsWith(seeAlsoExt) },
            kts = it.value.single { it.path.endsWith(ktsExt) }
        )
    }
    return articles
}

data class PromptArticle(
    val header: GeneratedPromptClazz,
    val seeAlso: GeneratedPromptClazz,
    val kts: GeneratedPromptClazz,
) : List<GeneratedPromptClazz> by listOf(kts, header, seeAlso) {
    val mainElement get() = kts
}

data class GeneratedArticleClazz(
    val folder: String,
    val path: String,
    val clazzName: ClassName,
    val article: PromptArticle,
)

fun PromptGenerationContext.generateArticleClazz(
    folder: String,
    article: PromptArticle,
): GeneratedArticleClazz {
    val classType = run {
        val packageName = article.map { it.clazzName.packageName }.distinct().single()
        val className = article.mainElement.clazzName.simpleName + "Article"
        ClassName(packageName, className)
    }

    val allProps = listOf(
        "header" to article.header,
        "seeAlso" to article.seeAlso,
        "kts" to article.kts,
    ).map { (name, resource) ->
        val getter = FunSpec
            .getterBuilder()
            .addCode(buildCodeBlock {
                addStatement("return %T()", article.header.clazzName)
            })
            .build()

        PropertySpec.builder(name, promptReaderClass)
            .getter(getter)
            .build()
    }

    val typeSpec = TypeSpec.classBuilder(classType)
        .superclass(promptArticleClass)
        .addProperties(allProps)
        .build()

    val fileSpec = FileSpec.builder(classType)
        .addFileComment("GENERATED FILE - DO NOT EDIT")
        .addType(typeSpec)
        .build()

    writeClazz(fileSpec, classType)
    return GeneratedArticleClazz(folder, article.mainElement.path, classType, article)
}
