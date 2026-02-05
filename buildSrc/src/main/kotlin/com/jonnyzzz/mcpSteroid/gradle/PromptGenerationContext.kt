/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.gradle

import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.Import
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.MemberName.Companion.member
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.asTypeName
import com.squareup.kotlinpoet.buildCodeBlock
import java.io.File
import kotlin.math.absoluteValue
import kotlin.random.Random

class PromptGenerationContext(
    val inputRoot: File,
    val outputRoot: File,
    val testOutputRoot: File,
) {
    val packageName = "com.jonnyzzz.mcpSteroid.prompts"
    val serviceAnnotation = ClassName("com.intellij.openapi.components", "Service")

    // Generate AllPrompts aggregator class
    val sequenceOfAny = Sequence::class.asClassName().parameterizedBy(ANY)
    val serviceMember = MemberName("com.intellij.openapi.components", "service")
}

fun String.toPromptClassName(): String {
    return split("-", "_")
        .map { if (it.all { it.isUpperCase() }) it.lowercase() else it }
        .map { it.titleCase() }
        .map { if (it.equals("intellij", ignoreCase = true)) "IntelliJ" else it }
        .joinToString("")
}

fun String.titleCase() = replaceFirstChar { it.titlecase() }

fun PromptGenerationContext.geratePromptClazz(
    src: File,
): ClassName {
    val content = src.readText()
    val factor = Random.nextInt(1000).absoluteValue + 11234

    val className = "Prompt" + src.nameWithoutExtension.toPromptClassName()
    val classType = ClassName(packageName, className)

    val readFn = content.chunked(1024).mapIndexed { index, content ->
        val packedContent = content.map { it.code * factor }
        FunSpec.builder("readPrompt_" + index)
            .addModifiers(KModifier.PRIVATE)
            .returns(Sequence::class.asClassName().parameterizedBy(Int::class.asTypeName()))
            .addCode(buildCodeBlock {
                beginControlFlow("return sequence")
                packedContent.forEach { code ->
                    addStatement("yield(%L)", code)
                }
                endControlFlow()
            })
            .build()
    }

    val readResourceFun = FunSpec.builder("readPrompt")
        .addModifiers(KModifier.OVERRIDE)
        .returns(String::class)
        .addCode(buildCodeBlock {
            beginControlFlow("return sequence")
            readFn.forEach { fn -> addStatement("yield(%L())", fn.name) }
            unindent()
            add("}.flatten().map { it / %L }.joinToString(%S) { it.toChar().toString() }\n", factor, "")
        })
        .build()

    val typeSpec = TypeSpec.classBuilder(className)
        .addAnnotation(
            AnnotationSpec.builder(serviceAnnotation)
                .addMember("%T.Level.APP", serviceAnnotation)
                .build()
        )
        .superclass(ClassName.bestGuess("com.jonnyzzz.mcpSteroid.prompts.PromptBase"))
        .addFunction(readResourceFun)
        .addFunctions(readFn)
        .build()

    val fileSpec = FileSpec.builder(packageName, className)
        .addFileComment("GENERATED FILE - DO NOT EDIT")
        .addType(typeSpec)
        .build()

    outputRoot.resolve("$className.kt").writeText(fileSpec.toString())

    val testFuncSpec = FunSpec.builder("test$className")
        .returns(Unit::class)
        .addCode(buildCodeBlock {
            add("val content = %T(%S).readText()\n", File::class.asClassName(), src.absolutePath)
            add("assertEquals(content, %T().%N())", classType, readResourceFun.name)
        })
        .build()

    val testTypeSpec = TypeSpec.classBuilder(className + "Test")
        .superclass(ClassName.bestGuess("com.intellij.testFramework.fixtures.BasePlatformTestCase"))
        .addFunction(testFuncSpec)
        .build()

    val testFileSpec = FileSpec.builder(packageName, className + "Test")
        .addFileComment("GENERATED FILE - DO NOT EDIT")
        .addType(testTypeSpec)
        .build()

    testOutputRoot.resolve("${className}Test.kt").writeText(testFileSpec.toString())

    return classType
}
