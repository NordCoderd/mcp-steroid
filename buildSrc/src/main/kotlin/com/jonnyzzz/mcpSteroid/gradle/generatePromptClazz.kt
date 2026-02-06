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
 */
fun buildEncodedReadFunctions(content: String): Pair<List<FunSpec>, FunSpec> {
    val readFn = content.chunked(1024).mapIndexed { index, chunk ->
        val factor = Random.nextInt(1000).absoluteValue + 11234

        val packedContent = chunk
            .map { it.code * factor }
            .chunked(80 / 7)
            .map { it.joinToString("|") }

        FunSpec.builder("readPrompt" + index)
            .addModifiers(KModifier.PRIVATE)
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
            controlFlow("return sequence") {
                readFn.forEach { fn ->
                    addStatement("yield(%L())", fn.name)
                }
            }
            controlFlow(".flatMap") {
                addStatement("val els = it.iterator()")
                addStatement("val seed = els.next().toInt()")
                controlFlow("els.asSequence().flatMap") {
                    controlFlow("it.splitToSequence(%S).map", "|") {
                        addStatement("it.toInt() / seed")
                    }
                }
            }
            controlFlow(".joinToString(%S)", "") {
                addStatement("it.toChar().toString()")
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
 * Generate a PromptBase subclass that holds an inline string content.
 * Used for generated content like descriptions, see-also, and TOC.
 */
fun PromptGenerationContext.generateStringPromptClazz(
    content: String,
    classType: ClassName,
    mimeType: String = "text/markdown",
) {
    val (readFn, readResourceFun) = buildEncodedReadFunctions(content)

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
}

