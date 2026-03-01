/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.promptgen

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.buildCodeBlock
import java.io.File

/**
 * Extracts all ` ```kotlin ``` ` blocks from a markdown file's content.
 *
 * Returns the body of each block (without the fence lines themselves), in order.
 */
fun extractKotlinBlocks(content: String): List<String> {
    val lines = content.lines()
    val blocks = mutableListOf<String>()
    var inBlock = false
    val current = StringBuilder()
    for (line in lines) {
        when {
            !inBlock && line.trimStart().startsWith("```kotlin") -> {
                inBlock = true
                current.clear()
            }
            inBlock && line.trimStart() == "```" -> {
                blocks += current.toString()
                inBlock = false
            }
            inBlock -> current.appendLine(line)
        }
    }
    return blocks
}

private val junitTestAnnotation = ClassName("org.junit.jupiter.api", "Test")
private val junitAssertions = ClassName("org.junit.jupiter.api", "Assertions")

fun PromptGenerationContext.generatePromptClazzTest(
    clazz: GeneratedPromptClazz,
) {
    val classType = run {
        ClassName(clazz.clazzName.packageName, clazz.clazzName.simpleName + "Test")
    }

    val testFuncSpec = FunSpec.builder("testReadResource")
        .addAnnotation(junitTestAnnotation)
        .returns(Unit::class)
        .addCode(buildCodeBlock {
            add("val content = %T(%S).readText()\n", File::class.asClassName(), clazz.src.absolutePath)
            add("%T.assertEquals(content, %T().readPrompt())", junitAssertions, clazz.clazzName)
        })
        .build()

    val testTypeSpec = TypeSpec.classBuilder(classType)
        .addFunction(testFuncSpec)
        .build()

    val testFileSpec = FileSpec.builder(classType)
        .addFileComment("GENERATED FILE - DO NOT EDIT")
        .addType(testTypeSpec)
        .build()

    writeTestClazz(testFileSpec, classType)
}


/**
 * Generates a test class for an article that verifies [payload], [description], and [seeAlso]
 * can all be read without error and produce non-empty content.
 *
 * A separate test method is generated for each non-null field so tests can be run individually.
 */
fun PromptGenerationContext.generateArticleReadTest(
    article: GeneratedArticleClazz,
) {
    val articleClassName = article.clazzName
    val baseStem = articleClassName.simpleName.removeSuffix("PromptArticle")
    val classType = ClassName(articleClassName.packageName, "${baseStem}PromptArticleReadTest")

    val methods = mutableListOf<FunSpec>()

    methods += FunSpec.builder("testPayloadReadable")
        .addAnnotation(junitTestAnnotation)
        .addKdoc("Verifies %T().payload.readPrompt() returns non-empty content.", articleClassName)
        .returns(Unit::class)
        .addCode(buildCodeBlock {
            addStatement("val payload = %T().payload.readPrompt()", articleClassName)
            addStatement(
                "%T.assertTrue(payload.isNotEmpty(), %S)",
                junitAssertions,
                "payload must not be empty for ${article.path}",
            )
        })
        .build()

    if (article.hasDescription) {
        methods += FunSpec.builder("testDescriptionReadable")
            .addAnnotation(junitTestAnnotation)
            .addKdoc("Verifies %T().description!!.readPrompt() returns non-empty content.", articleClassName)
            .returns(Unit::class)
            .addCode(buildCodeBlock {
                addStatement("val desc = %T().description!!.readPrompt()", articleClassName)
                addStatement(
                    "%T.assertTrue(desc.isNotEmpty(), %S)",
                    junitAssertions,
                    "description must not be empty for ${article.path}",
                )
            })
            .build()
    }

    if (article.hasSeeAlso) {
        methods += FunSpec.builder("testSeeAlsoReadable")
            .addAnnotation(junitTestAnnotation)
            .addKdoc("Verifies %T().seeAlso!!.readPrompt() returns non-empty content.", articleClassName)
            .returns(Unit::class)
            .addCode(buildCodeBlock {
                addStatement("val seeAlso = %T().seeAlso!!.readPrompt()", articleClassName)
                addStatement(
                    "%T.assertTrue(seeAlso.isNotEmpty(), %S)",
                    junitAssertions,
                    "seeAlso must not be empty for ${article.path}",
                )
            })
            .build()
    }

    val testTypeSpec = TypeSpec.classBuilder(classType)
        .addFunctions(methods)
        .build()

    val testFileSpec = FileSpec.builder(classType)
        .addFileComment("GENERATED FILE - DO NOT EDIT")
        .addType(testTypeSpec)
        .build()

    writeTestClazz(testFileSpec, classType)
}

/**
 * Generates a single compilation test class per new-format `.md` article, with one
 * test method per ` ```kotlin ``` ` block.
 *
 * Called after [generateArticleClazz] so the article's generated class (with its
 * `ktBlock000`, `ktBlock001`, … properties) already exists. Each test method simply
 * instantiates that class and passes the corresponding block to
 * [KtBlockCompilationTestBase.compileKtBlock].
 *
 * Generates a class named `{ArticleStem}KtBlocksCompilationTest` with methods:
 *   `testBlock000Compiles()`, `testBlock001Compiles()`, …
 *
 * These tests are written to [testOutputRoot] and extend
 * [KtBlockCompilationTestBase] (JUnit 5, no IntelliJ test framework).
 */
fun PromptGenerationContext.generateMdKtBlockCompilationTests(
    article: GeneratedArticleClazz,
) {
    val promptArticle = article.article ?: return

    val parts = parseNewFormatArticleParts(promptArticle.payload.content)
    val blockCount = parts.ktBodyParts.size
    if (blockCount == 0) return

    val articleClassName = article.clazzName
    val baseStem = articleClassName.simpleName.removeSuffix("PromptArticle")
    val classType = ClassName(
        articleClassName.packageName,
        "${baseStem}KtBlocksCompilationTest",
    )

    val testMethods = (0 until blockCount).map { index ->
        val blockIndex = index.toString().padStart(3, '0')
        FunSpec.builder("testBlock${blockIndex}Compiles")
            .addAnnotation(junitTestAnnotation)
            .addKdoc("Source: %L, block #%L", article.path, index)
            .returns(Unit::class)
            .addStatement("compileKtBlock(%T().ktBlock${blockIndex})", articleClassName)
            .build()
    }

    val testTypeSpec = TypeSpec.classBuilder(classType)
        .superclass(ClassName.bestGuess("com.jonnyzzz.mcpSteroid.prompts.KtBlockCompilationTestBase"))
        .addFunctions(testMethods)
        .build()

    val testFileSpec = FileSpec.builder(classType)
        .addFileComment("GENERATED FILE - DO NOT EDIT")
        .addType(testTypeSpec)
        .build()

    writeTestClazz(testFileSpec, classType)
}
