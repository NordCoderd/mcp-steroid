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
import com.squareup.kotlinpoet.asTypeName
import com.squareup.kotlinpoet.buildCodeBlock
import java.io.File
import kotlin.math.absoluteValue
import kotlin.random.Random

data class GeneratedPromptClazz(
    val fileType: String,
    val folder: String,
    val path: String,
    override val clazzName: ClassName,
    val src: File,
) : Generated {
    val content get() = src.readText()

    override val entryName: String get() = path.substringAfterLast("/").toPromptIdentifierName()
}

/**
 * Build the encoded readPromptN() helper functions and readPromptInternal() override
 * for a given text content. The content is chunked and obfuscated using a random factor.
 *
 * @param sourcePath Optional source path to embed as KDoc on each helper method (for readability).
 */
fun buildEncodedReadFunctions(content: String, sourcePath: String? = null): Pair<List<FunSpec>, FunSpec> {
    val readFn = content.chunked(1024).mapIndexed { index, chunk ->
        val factor = Random.nextInt(1000).absoluteValue + 11234

        val packedContent = chunk
            .map { it.code * factor }
            .chunked(80 / 7)
            .map { it.joinToString("|") }

        FunSpec.builder("readPrompt" + index)
            .addModifiers(KModifier.PRIVATE)
            .apply {
                if (sourcePath != null) {
                    addKdoc("Source: %L, chunk %L", sourcePath, index)
                }
            }
            .returns(Sequence::class.asClassName().parameterizedBy(String::class.asTypeName()))
            .addCode(buildCodeBlock {
                controlFlow("return sequence") {
                    addStatement("yield(%S)", factor)
                    packedContent.forEach { code ->
                        addStatement("yield(%S)", code)
                    }
                }
            })
            .build()
    }

    val readResourceFun = FunSpec.builder("readPromptInternal")
        .addModifiers(KModifier.OVERRIDE)
        .returns(String::class)
        .addCode(buildCodeBlock {
            controlFlow("return decodePromptChunks") {
                readFn.forEach { fn ->
                    addStatement("yield(%L())", fn.name)
                }
            }
        })
        .build()

    return readFn to readResourceFun
}

fun PromptGenerationContext.generatePromptClazz(
    src: File,
): GeneratedPromptClazz {
    val filePropValue = src.extension
    val pathValue = src.toRelativeString(inputRoot)
    val folderValue = src.parentFile.toRelativeString(inputRoot)

    val classType = run {
        val packageInfix = folderValue.trim('/')
            .split("/")
            .map { it.toPromptIdentifierName() }
            .joinToString("") { ".$it" }

        val className = src.nameWithoutExtension.toPromptClassName() + "Prompt"
        ClassName(packageName + packageInfix, className)
    }

    val (readFn, readResourceFun) = buildEncodedReadFunctions(src.readText())

    val mimeType = when (filePropValue) {
        "kts" -> "text/x-kotlin"
        "md" -> "text/markdown"
        else -> "text/plain"
    }
    val mimeTypeProp = PropertySpec.builder("mimeType", String::class)
        .addModifiers(KModifier.OVERRIDE)
        .initializer("%S", mimeType)
        .build()

    val typeSpec = TypeSpec.classBuilder(classType)
        .superclass(promptBaseClass)
        .addProperty(mimeTypeProp)
        .addFunction(readResourceFun)
        .addFunctions(readFn)
        .build()

    val fileSpec = FileSpec.builder(classType)
        .addFileComment("GENERATED FILE - DO NOT EDIT")
        .addType(typeSpec)
        .build()

    writeClazz(fileSpec, classType)
    return GeneratedPromptClazz(filePropValue, folderValue, pathValue, classType, src)
}

/**
 * Generate a PromptBase subclass whose payload is assembled at runtime by calling
 * [readPrompt] on each per-section Prompt class and joining them with the same
 * separator and ```kotlin``` fence logic used at build time.
 *
 * This avoids duplicating section content: each section is encoded once in its own
 * Prompt class and reused here rather than being re-encoded into a merged string.
 */
fun PromptGenerationContext.generateSectionDelegatePayloadClazz(
    sections: List<GeneratedPromptClazz>,
    classType: ClassName,
) {
    val mimeTypeProp = PropertySpec.builder("mimeType", String::class)
        .addModifiers(KModifier.OVERRIDE)
        .initializer("%S", "text/markdown")
        .build()

    val readResourceFun = FunSpec.builder("readPromptInternal")
        .addModifiers(KModifier.OVERRIDE)
        .returns(String::class)
        .addCode(buildCodeBlock {
            controlFlow("return buildString") {
                for ((index, section) in sections.withIndex()) {
                    if (index > 0) addStatement("append(%S)", "\n\n")
                    if (section.path.endsWith(".kt")) {
                        addStatement("appendLine(%S)", "```kotlin")
                        addStatement("append(%T().readPrompt().trim())", section.clazzName)
                        addStatement("appendLine()")
                        addStatement("append(%S)", "```")
                    } else {
                        addStatement("append(%T().readPrompt().trim())", section.clazzName)
                    }
                }
            }
        })
        .build()

    val typeSpec = TypeSpec.classBuilder(classType)
        .superclass(promptBaseClass)
        .addProperty(mimeTypeProp)
        .addFunction(readResourceFun)
        .build()

    val fileSpec = FileSpec.builder(classType)
        .addFileComment("GENERATED FILE - DO NOT EDIT")
        .addType(typeSpec)
        .build()

    writeClazz(fileSpec, classType)
}

/**
 * Generate a PromptBase subclass that holds an inline string content.
 * Used for generated content like descriptions, see-also, and TOC.
 *
 * @param sourcePath When provided, added as KDoc to the class and each encoded chunk method.
 */
fun PromptGenerationContext.generateStringPromptClazz(
    content: String,
    classType: ClassName,
    mimeType: String = "text/markdown",
    sourcePath: String? = null,
) {
    val (readFn, readResourceFun) = buildEncodedReadFunctions(content, sourcePath)

    val mimeTypeProp = PropertySpec.builder("mimeType", String::class)
        .addModifiers(KModifier.OVERRIDE)
        .initializer("%S", mimeType)
        .build()

    val typeSpec = TypeSpec.classBuilder(classType)
        .apply { if (sourcePath != null) addKdoc("Content from: %L", sourcePath) }
        .superclass(promptBaseClass)
        .addProperty(mimeTypeProp)
        .addFunction(readResourceFun)
        .addFunctions(readFn)
        .build()

    val fileSpec = FileSpec.builder(classType)
        .addFileComment("GENERATED FILE - DO NOT EDIT")
        .addType(typeSpec)
        .build()

    writeClazz(fileSpec, classType)
}

