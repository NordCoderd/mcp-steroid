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

data class GeneratedPromptClazz(
    val fileType: String,
    val folder: String,
    val path: String,
    val clazzName: ClassName,
    val src: File,
) {
    val content get() = src.readText()
}

fun PromptGenerationContext.generatePromptClazz(
    src: File,
): GeneratedPromptClazz {
    val content = src.readText()

    val filePropValue = src.extension
    val pathValue = src.toRelativeString(inputRoot)
    val folderValue = src.parentFile.toRelativeString(inputRoot)

    val packageInfix = folderValue.trim('/')
        .split("/")
        .map { it.toPromptClassName().replaceFirstChar { it.lowercase() } }
        .joinToString("") { ".$it"}

    val className = "Prompt" + src.nameWithoutExtension.toPromptClassName()
    val classType = ClassName(packageName + packageInfix , className)

    val readFn = content.chunked(1024).mapIndexed { index, content ->
        val factor = Random.nextInt(1000).absoluteValue + 11234

        val packedContent = content
            .map { it.code * factor }
            .chunked(80/7)
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

    val fileTypeProp = PropertySpec
        .builder("fileType", String::class.asClassName())
        .addModifiers(KModifier.OVERRIDE)
        .initializer("%S", filePropValue)
        .build()

    val pathProp = PropertySpec
        .builder("path", String::class.asClassName())
        .addModifiers(KModifier.OVERRIDE)
        .initializer("%S", pathValue)
        .build()

    val folderProp = PropertySpec
        .builder("folder", String::class.asClassName())
        .addModifiers(KModifier.OVERRIDE)
        .initializer("%S", folderValue)
        .build()

    val typeSpec = TypeSpec.classBuilder(className)
        .addAnnotation(
            AnnotationSpec.builder(serviceAnnotation)
                .addMember("%T.Level.APP", serviceAnnotation)
                .build()
        )
        .superclass(promptBaseClass)
        .addProperty(pathProp)
        .addProperty(folderProp)
        .addProperty(fileTypeProp)
        .addFunction(readResourceFun)
        .addFunctions(readFn)
        .build()

    val fileSpec = FileSpec.builder(classType.packageName, className)
        .addFileComment("GENERATED FILE - DO NOT EDIT")
        .addType(typeSpec)
        .build()

    val testFuncSpec = FunSpec.builder("test$className")
        .returns(Unit::class)
        .addCode(buildCodeBlock {
            add("val content = %T(%S).readText()\n", File::class.asClassName(), src.absolutePath)
            add("assertEquals(content, %T().readPrompt())", classType)
        })
        .build()

    val testTypeSpec = TypeSpec.classBuilder(className + "Test")
        .superclass(ClassName.bestGuess("com.intellij.testFramework.fixtures.BasePlatformTestCase"))
        .addFunction(testFuncSpec)
        .build()

    val testFileSpec = FileSpec.builder(classType.packageName, className + "Test")
        .addFileComment("GENERATED FILE - DO NOT EDIT")
        .addType(testTypeSpec)
        .build()

    writeClazz(fileSpec, classType)
    writeTestClazz(testFileSpec, classType)

    return GeneratedPromptClazz(filePropValue, folderValue, pathValue, classType, src)
}
