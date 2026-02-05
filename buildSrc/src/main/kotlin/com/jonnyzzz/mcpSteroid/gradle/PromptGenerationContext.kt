/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.gradle

import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.asClassName
import java.io.File

class PromptGenerationContext(
    val inputRoot: File,
    val outputRoot: File,
    val testOutputRoot: File,
) {
    val packageName = "com.jonnyzzz.mcpSteroid.prompts.generated"
    val serviceAnnotation = ClassName("com.intellij.openapi.components", "Service")

    // Generate AllPrompts aggregator class
    val sequenceOfAny = Sequence::class.asClassName().parameterizedBy(ANY)
    val serviceMember = MemberName("com.intellij.openapi.components", "service")

    val promptBaseClass = ClassName("com.jonnyzzz.mcpSteroid.prompts", "PromptBase")
    val promptIndexBaseClass = ClassName("com.jonnyzzz.mcpSteroid.prompts", "PromptIndexBase")
    val promptReaderClass = ClassName("com.jonnyzzz.mcpSteroid.prompts", "PromptReader")
    val promptArticleClass = ClassName("com.jonnyzzz.mcpSteroid.prompts", "ArticleBase")
}

