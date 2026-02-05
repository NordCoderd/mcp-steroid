/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.gradle

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.buildCodeBlock
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File
import kotlin.math.absoluteValue
import kotlin.random.Random

abstract class CompilePromptsTask : DefaultTask() {
    @get:InputDirectory
    abstract val inputDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:OutputDirectory
    abstract val testOutputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val inputRoot = inputDir.get().asFile
        val outputRoot = outputDir.get().asFile
        val testOutputRoot = testOutputDir.get().asFile
        project.delete(outputRoot, testOutputRoot)
        project.mkdir(outputRoot)
        project.mkdir(testOutputRoot)

        val ctx = PromptGenerationContext(inputRoot, outputRoot, testOutputRoot)
        inputRoot
            .walkTopDown()
            .filter { it.isFile }
            .forEach { src -> ctx.geratePromptClazz(src) }
    }
}
