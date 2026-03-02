/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.promptgen

import com.jonnyzzz.mcpSteroid.prompts.PromptsContext
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.buildCodeBlock

private val junitTestAnnotation = ClassName("org.junit.jupiter.api", "Test")
private val junitAssertions = ClassName("org.junit.jupiter.api", "Assertions")

/**
 * Generates a test class that validates all `mcp-steroid://` URI references
 * found in prompt content actually resolve to existing articles.
 *
 * The test:
 * 1. Collects all known article URIs from [ResourcesIndex]
 * 2. Reads all article content (readPayload for each IDE context)
 * 3. Extracts `mcp-steroid://` references via regex
 * 4. Fails if any reference does not match a known URI
 */
fun PromptGenerationContext.generateResourceUriValidationTest(
    allStandalonePrompts: List<GeneratedPromptClazz>,
) {
    val classType = ClassName(packageName, "ResourceUriValidationTest")
    val resourcesIndexClass = ClassName(packageName, "ResourcesIndex")
    val promptsContextClass = PromptsContext::class.asClassName()

    val testFunc = FunSpec.builder("testAllMcpSteroidUriReferencesAreValid")
        .addAnnotation(junitTestAnnotation)
        .returns(Unit::class)
        .addCode(buildCodeBlock {
            // Collect all known URIs from ResourcesIndex
            addStatement("val knownUris = mutableSetOf<String>()")
            addStatement("val index = %T()", resourcesIndexClass)
            controlFlow("for ((_, folderIndex) in index.roots)") {
                controlFlow("for ((_, article) in folderIndex.articles)") {
                    addStatement("knownUris.add(article.uri)")
                }
            }
            addStatement("")

            // URI pattern and error collector
            add("val uriPattern = %T(%S)\n", Regex::class.asClassName(), """mcp-steroid://[\w-]+(?:/[\w-]+)*""")
            addStatement("val errors = mutableListOf<String>()")
            addStatement("val ideaContext = %T(%S, 253)", promptsContextClass, "IU")
            addStatement("val riderContext = %T(%S, 253)", promptsContextClass, "RD")
            addStatement("")

            // Check all article content via readPayload with both contexts
            controlFlow("for ((_, folderIndex) in index.roots)") {
                controlFlow("for ((articleKey, article) in folderIndex.articles)") {
                    addStatement(
                        "val contents = listOf(article.readPayload(ideaContext), article.readPayload(riderContext), article.description.readPrompt())"
                    )
                    controlFlow("for (content in contents)") {
                        controlFlow("for (match in uriPattern.findAll(content))") {
                            addStatement("val ref = match.value")
                            controlFlow("if (ref !in knownUris)") {
                                add("""errors.add("Article '" + article.uri + "' (" + articleKey + "): invalid reference '" + ref + "'")""")
                                add("\n")
                            }
                        }
                    }
                }
            }
            addStatement("")

            // Check standalone prompts (not part of any article)
            if (allStandalonePrompts.isNotEmpty()) {
                addStatement("// Check standalone prompts")
                add("val standaloneContents = listOf(\n")
                for ((i, prompt) in allStandalonePrompts.withIndex()) {
                    val separator = if (i < allStandalonePrompts.size - 1) "," else ""
                    add("  %S to %T().readPrompt()$separator\n", prompt.path, prompt.clazzName)
                }
                add(")\n")
                controlFlow("for ((source, content) in standaloneContents)") {
                    controlFlow("for (match in uriPattern.findAll(content))") {
                        addStatement("val ref = match.value")
                        controlFlow("if (ref !in knownUris)") {
                            add("""errors.add("Standalone '" + source + "': invalid reference '" + ref + "'")""")
                            add("\n")
                        }
                    }
                }
                addStatement("")
            }

            // Assertion
            controlFlow("if (errors.isNotEmpty())") {
                add("""%T.fail<Unit>("Found " + errors.size + " invalid mcp-steroid:// references:\n" + errors.joinToString("\n"))""", junitAssertions)
                add("\n")
            }
        })
        .build()

    val typeSpec = TypeSpec.classBuilder(classType)
        .addFunction(testFunc)
        .build()

    val fileSpec = FileSpec.builder(classType)
        .addFileComment("GENERATED FILE - DO NOT EDIT")
        .addType(typeSpec)
        .build()

    writeTestClazz(fileSpec, classType)
}
