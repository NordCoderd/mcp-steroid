/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.gradle

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

        val allPromptClasses = mutableListOf<String>()
        inputRoot
            .walkTopDown()
            .filter { it.isFile }
            .forEach { src ->
                val dest = outputRoot.resolve(src.nameWithoutExtension + ".kt")
                val content = src.readText()

                val factor = Random.nextInt(10000) + 11234
                val packedContent = content
                    .map { it.code * factor }
                    .joinToString("") { "\n                       yield($it)" }

                val className = "Prompt" + src.nameWithoutExtension.toPromptClassName()
                allPromptClasses += className

                dest.writeText(
                    """
                    package com.jonnyzzz.mcpSteroid.prompts

                    import com.intellij.openapi.components.Service

                    //GENERATED FILE - DO NOT EDIT

                    @Service(Service.Level.APP)
                    class $className {
                      fun readResource() : String {
                         return sequence {$packedContent
                         }.map { it / $factor }.toList().reversed().joinToString("")
                      }
                    }

                    """.trimIndent()
                )
            }

        val servicesList = allPromptClasses.joinToString("") {
            "\n                    yield(service<$it>())"
        }

        outputRoot.resolve("AllPrompts.kt").writeText(
            """
            package com.jonnyzzz.mcpSteroid.prompts

            import com.intellij.openapi.components.Service
            import com.intellij.openapi.components.service

            //GENERATED FILE - DO NOT EDIT

            @Service(Service.Level.APP)
            class AllPrompts {
              val all get () = sequence {$servicesList
              }
            }

            """.trimIndent()
        )
    }
}

private fun String.toPromptClassName(): String = replaceFirstChar {
    if (it.isLowerCase()) it.titlecase() else it.toString()
}
