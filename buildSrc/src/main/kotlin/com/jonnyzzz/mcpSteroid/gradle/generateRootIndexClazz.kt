/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.gradle

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.buildCodeBlock


fun PromptGenerationContext.generateIndexClazz(
    indexes: List<GeneratedIndexClazz>,
) {
    val classType = run {
        ClassName(packageName,  "ResourcesIndex")
    }

    val rootsProp = PropertySpec
        .builder("roots", Map::class.asClassName().parameterizedBy(String::class.asClassName(), promptIndexBaseClass))
        .addModifiers(KModifier.OVERRIDE)
        .getter(FunSpec.getterBuilder().addCode(
            buildCodeBlock {
                controlFlow("return buildMap") {
                    indexes
                        .sortedWith(compareBy<GeneratedIndexClazz>({ it.folder }))
                        .forEach { r ->
                            addStatement("put(%S, %T())", r.folder.trim('/'), r.clazzName)
                        }
                }
        }).build())
        .build()

    val typeSpec = TypeSpec.classBuilder(classType)
        .superclass(promptRootBaseClass)
        .addProperty(rootsProp)
        .build()

    val fileSpec = FileSpec.builder(classType.packageName, classType.simpleName + ".kt")
        .addFileComment("GENERATED FILE - DO NOT EDIT")
        .addType(typeSpec)
        .build()

    writeClazz(fileSpec, classType)
}
