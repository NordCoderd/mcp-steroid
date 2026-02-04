/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.gradle

import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asClassName
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import kotlin.random.Random

abstract class CompilePromptsTask : DefaultTask() {
    @get:InputDirectory
    abstract val inputDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val inputRoot = inputDir.get().asFile
        val outputRoot = outputDir.get().asFile
        project.delete(outputRoot)
        outputRoot.mkdirs()

        val packageName = "com.jonnyzzz.mcpSteroid.prompts"
        val serviceAnnotation = ClassName("com.intellij.openapi.components", "Service")

        val allPromptClasses = mutableListOf<ClassName>()

        inputRoot
            .walkTopDown()
            .filter { it.isFile }
            .forEach { src ->
                val content = src.readText()
                val factor = Random.nextInt(10000) + 11234

                val packedContent = content
                    .map { it.code * factor }

                val className = "Prompt" + src.nameWithoutExtension.toPromptClassName()
                val classType = ClassName(packageName, className)
                allPromptClasses += classType

                val yieldStatements = packedContent.joinToString("\n") { "yield($it)" }

                val readResourceFun = FunSpec.builder("readResource")
                    .returns(String::class)
                    .addCode("""
                        |return sequence {
                        |    $yieldStatements
                        |}.map { it / $factor }.toList().reversed().joinToString("")
                    """.trimMargin())
                    .build()

                val typeSpec = TypeSpec.classBuilder(className)
                    .addAnnotation(
                        AnnotationSpec.builder(serviceAnnotation)
                            .addMember("%T.Level.APP", serviceAnnotation)
                            .build()
                    )
                    .addFunction(readResourceFun)
                    .build()

                val fileSpec = FileSpec.builder(packageName, className)
                    .addFileComment("GENERATED FILE - DO NOT EDIT")
                    .addType(typeSpec)
                    .build()

                outputRoot.resolve("$className.kt").writeText(fileSpec.toString())
            }

        // Generate AllPrompts aggregator class
        val yieldStatements = allPromptClasses.joinToString("\n") {
            "yield(service<${it.simpleName}>())"
        }

        val sequenceOfAny = Sequence::class.asClassName().parameterizedBy(ANY)

        val allProperty = PropertySpec.builder("all", sequenceOfAny)
            .getter(
                FunSpec.getterBuilder()
                    .addCode("""
                        |return sequence {
                        |    $yieldStatements
                        |}
                    """.trimMargin())
                    .build()
            )
            .build()

        val allPromptsType = TypeSpec.classBuilder("AllPrompts")
            .addAnnotation(
                AnnotationSpec.builder(serviceAnnotation)
                    .addMember("%T.Level.APP", serviceAnnotation)
                    .build()
            )
            .addProperty(allProperty)
            .build()

        val allPromptsFile = FileSpec.builder(packageName, "AllPrompts")
            .addFileComment("GENERATED FILE - DO NOT EDIT")
            .addImport("com.intellij.openapi.components", "service")
            .addType(allPromptsType)
            .build()

        outputRoot.resolve("AllPrompts.kt").writeText(allPromptsFile.toString())
    }
}

private fun String.toPromptClassName(): String = replaceFirstChar {
    if (it.isLowerCase()) it.titlecase() else it.toString()
}
