/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

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
        val promptClasses = inputRoot
            .walkTopDown()
            .filter { it.isFile }
            .map { src -> ctx.generatePromptClazz(src) }
            .toList()

        promptClasses.forEach { clazz ->
            ctx.generatePromptClazzTest(clazz)
        }

        val indexes = promptClasses.groupBy { it.folder }
            .map { (folder, clazzes) ->
                val articles = groupByArticle(clazzes)
                val articleClasses = articles.map {
                    ctx.generateArticleClazz(folder, it)
                }

                val usedPromptNames = articleClasses.flatMap { it.article }.map { it.path }.toSortedSet()
                val standaloneFiles = clazzes.filter { it.path !in usedPromptNames }

                ctx.generateIndexClazz(folder, standaloneFiles, articleClasses)
            }

        ctx.generateIndexClazz(indexes)
    }
}
