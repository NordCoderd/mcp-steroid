/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.prompts

import com.squareup.kotlinpoet.FileSpec

abstract class ArticleBase {
    abstract val header: PromptBase
    abstract val seeAlso: PromptBase
    abstract val kts: PromptBase

    val path = kts.path

    val mimeType = "application/kotlin"

    val name get() = header.readPrompt().trim().lineSequence().first().trim()
    val description get() = header.readPrompt().trim().lineSequence().drop(1).joinToString("\n").trim()

    fun provideMergedContent() = buildString {
        //TODO: generate code to allow this made automatically, avoid KotlinPoet at runtime.
        val headerComment = FileSpec.scriptBuilder(name)
            .addFileComment(header.readPrompt()).build().writeTo(this)

        appendLine()
        appendLine(kts.readPrompt())
        appendLine()

        val footerComment = FileSpec.scriptBuilder(name)
            .addFileComment(seeAlso.readPrompt()).build().writeTo(this)
    }
}

abstract class PromptRootBase {
    abstract val roots: Map<String, PromptIndexBase>
}

abstract class PromptBase {
    fun readPrompt(): String {
        return readPromptInternal()
    }

    abstract val fileType: String
    abstract val folder: String
    abstract val path: String

    protected abstract fun readPromptInternal(): String
}

abstract class PromptIndexBase {
    abstract val files: Map<String, PromptBase>
    abstract val articles: Map<String, ArticleBase>
}

