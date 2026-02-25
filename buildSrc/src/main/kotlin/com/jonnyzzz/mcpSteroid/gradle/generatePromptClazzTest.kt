/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.gradle

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
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

fun PromptGenerationContext.generatePromptClazzTest(
    clazz: GeneratedPromptClazz,
) {
    val classType = run {
        ClassName(clazz.clazzName.packageName, clazz.clazzName.simpleName + "Test")
    }

    val testFuncSpec = FunSpec.builder("testReadResource")
        .returns(Unit::class)
        .addCode(buildCodeBlock {
            add("val content = %T(%S).readText()\n", File::class.asClassName(), clazz.src.absolutePath)
            add("assertEquals(content, %T().readPrompt())", clazz.clazzName)
        })
        .build()

    val testTypeSpec = TypeSpec.classBuilder(classType)
        .superclass(ClassName.bestGuess("com.intellij.testFramework.fixtures.BasePlatformTestCase"))
        .addFunction(testFuncSpec)
        .build()

    val testFileSpec = FileSpec.builder(classType)
        .addFileComment("GENERATED FILE - DO NOT EDIT")
        .addType(testTypeSpec)
        .build()

    writeTestClazz(testFileSpec, classType)
}

/**
 * Generates a compilation test for a `.kt` section file.
 *
 * The test reads the source file, wraps it via [CodeButcher.wrapToKotlinClass] (same
 * pipeline as live script execution), compiles it against the IDE classpath, and fails
 * if the kotlinc exit code is non-zero.
 *
 * Only called for files where [isSectionFile] is true and the extension is `.kt`.
 */
fun PromptGenerationContext.generateKtSectionCompilationTest(
    clazz: GeneratedPromptClazz,
) {
    val classType = ClassName(
        clazz.clazzName.packageName,
        clazz.clazzName.simpleName.removeSuffix("Prompt") + "KtCompilationTest",
    )

    val fileClass = File::class.asClassName()
    val filesClass = ClassName("java.nio.file", "Files")
    val standardCharsetsClass = ClassName("java.nio.charset", "StandardCharsets")
    val codeButcherClass = ClassName("com.jonnyzzz.mcpSteroid.execution", "CodeButcher")
    val kotlincCommandLineBuilderClass = ClassName("com.jonnyzzz.mcpSteroid.koltinc", "KotlincCommandLineBuilder")

    val testFuncSpec = FunSpec.builder("testCompiles")
        .returns(Unit::class)
        .addCode(buildCodeBlock {
            controlFlow("timeoutRunBlocking(120.seconds)") {
                addStatement("val content = %T(%S).readText()", fileClass, clazz.src.absolutePath)
                addStatement("val wrapped = %T().wrapToKotlinClass(%S, content)", codeButcherClass, "KtSectionScript")
                addStatement("val tempDir = %T.createTempDirectory(%S)", filesClass, "kt-section-compile")
                addStatement("val sourceFile = tempDir.resolve(%S)", "Script.kt")
                addStatement("%T.writeString(sourceFile, wrapped.code, %T.UTF_8)", filesClass, standardCharsetsClass)
                addStatement("val outputJar = tempDir.resolve(%S)", "out.jar")
                addStatement("val classpath = scriptClassLoaderFactory.ideClasspath()")
                addStatement(
                    "val cmd = %T(outputJar).withNoStdLib(true).withExtraParameters(listOf(%S)).addClasspathEntries(classpath).addSource(sourceFile).build()",
                    kotlincCommandLineBuilderClass,
                    "-Werror",
                )
                addStatement("val result = kotlincProcessClient.kotlinc(cmd.args, tempDir)")
                addStatement("val output = (result.stdout + %S + result.stderr).trim()", "\n")
                addStatement(
                    "assertEquals(%S + output, 0, result.exitCode)",
                    "Compilation failed or has warnings (-Werror) for ${clazz.src.name}:\n",
                )
            }
        })
        .build()

    val runInDispatchFuncSpec = FunSpec.builder("runInDispatchThread")
        .addModifiers(KModifier.OVERRIDE)
        .returns(Boolean::class)
        .addStatement("return false")
        .build()

    val testTypeSpec = TypeSpec.classBuilder(classType)
        .superclass(ClassName.bestGuess("com.intellij.testFramework.fixtures.BasePlatformTestCase"))
        .addFunction(runInDispatchFuncSpec)
        .addFunction(testFuncSpec)
        .build()

    val testFileSpec = FileSpec.builder(classType)
        .addFileComment("GENERATED FILE - DO NOT EDIT")
        .addImport("com.jonnyzzz.mcpSteroid.koltinc", "scriptClassLoaderFactory")
        .addImport("com.jonnyzzz.mcpSteroid.koltinc", "kotlincProcessClient")
        .addImport("com.intellij.testFramework.common", "timeoutRunBlocking")
        .addImport("kotlin.time.Duration.Companion", "seconds")
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
        .addKdoc("Verifies %T().payload.readPrompt() returns non-empty content.", articleClassName)
        .returns(Unit::class)
        .addCode(buildCodeBlock {
            addStatement("val payload = %T().payload.readPrompt()", articleClassName)
            addStatement("assertTrue(%S, payload.isNotEmpty())", "payload must not be empty for ${article.path}")
        })
        .build()

    if (article.hasDescription) {
        methods += FunSpec.builder("testDescriptionReadable")
            .addKdoc("Verifies %T().description!!.readPrompt() returns non-empty content.", articleClassName)
            .returns(Unit::class)
            .addCode(buildCodeBlock {
                addStatement("val desc = %T().description!!.readPrompt()", articleClassName)
                addStatement("assertTrue(%S, desc.isNotEmpty())", "description must not be empty for ${article.path}")
            })
            .build()
    }

    if (article.hasSeeAlso) {
        methods += FunSpec.builder("testSeeAlsoReadable")
            .addKdoc("Verifies %T().seeAlso!!.readPrompt() returns non-empty content.", articleClassName)
            .returns(Unit::class)
            .addCode(buildCodeBlock {
                addStatement("val seeAlso = %T().seeAlso!!.readPrompt()", articleClassName)
                addStatement("assertTrue(%S, seeAlso.isNotEmpty())", "seeAlso must not be empty for ${article.path}")
            })
            .build()
    }

    val testTypeSpec = TypeSpec.classBuilder(classType)
        .superclass(ClassName.bestGuess("com.intellij.testFramework.fixtures.BasePlatformTestCase"))
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
 * [com.jonnyzzz.mcpSteroid.koltinc.BaseKtBlocksCompilationTest.compileKtBlock].
 *
 * Generates a class named `{ArticleStem}KtBlocksCompilationTest` with methods:
 *   `testBlock000Compiles()`, `testBlock001Compiles()`, …
 */
fun PromptGenerationContext.generateMdKtBlockCompilationTests(
    article: GeneratedArticleClazz,
) {
    val promptArticle = article.article ?: return
    require(promptArticle.newFormat)

    val parts = parseNewFormatArticleParts(promptArticle.payload!!.content)
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
            .addKdoc("Source: %L, block #%L", article.path, index)
            .returns(Unit::class)
            .addStatement("compileKtBlock(%T().ktBlock${blockIndex})", articleClassName)
            .build()
    }

    val testTypeSpec = TypeSpec.classBuilder(classType)
        .superclass(ClassName.bestGuess("com.jonnyzzz.mcpSteroid.koltinc.BaseKtBlocksCompilationTest"))
        .addFunctions(testMethods)
        .build()

    val testFileSpec = FileSpec.builder(classType)
        .addFileComment("GENERATED FILE - DO NOT EDIT")
        .addType(testTypeSpec)
        .build()

    writeTestClazz(testFileSpec, classType)
}
