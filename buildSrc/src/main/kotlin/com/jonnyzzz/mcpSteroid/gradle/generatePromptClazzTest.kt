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
                    "val cmd = %T(outputJar).withNoStdLib(true).addClasspathEntries(classpath).addSource(sourceFile).build()",
                    kotlincCommandLineBuilderClass,
                )
                addStatement("val result = kotlincProcessClient.kotlinc(cmd.args, tempDir)")
                addStatement("val output = (result.stdout + %S + result.stderr).trim()", "\n")
                addStatement(
                    "assertEquals(%S + output, 0, result.exitCode)",
                    "Compilation failed for ${clazz.src.name}:\n",
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
