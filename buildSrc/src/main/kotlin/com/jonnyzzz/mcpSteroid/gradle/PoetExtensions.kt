/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.gradle

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.buildCodeBlock
import java.io.File

fun CodeBlock.Builder.controlFlow(
    controlFlow: String,
    vararg args: Any?,
    ƒ: CodeBlock.Builder.() -> Unit
): CodeBlock.Builder {
    beginControlFlow(controlFlow, *args)
    ƒ()
    endControlFlow()
    return this
}

private fun PromptGenerationContext.writeClazzInner(spec: FileSpec, classType: ClassName, root: File) {
    val basePath = classType.packageName.removePrefix(packageName).trim('.').replace(".", "/")
    val targetFile = root.resolve(basePath).resolve("${classType.simpleName}.kt")
    targetFile.parentFile.mkdirs()
    targetFile.writeText(spec.toString())
}

fun PromptGenerationContext.writeClazz(spec: FileSpec, classType: ClassName) {
    writeClazzInner(spec, classType, outputRoot)
}

fun PromptGenerationContext.writeTestClazz(spec: FileSpec, classType: ClassName) {
    writeClazzInner(spec, classType, testOutputRoot)
}
