/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.prompts

abstract class ArticleBase {
    abstract val payload: PromptBase

    abstract val uri: String
    abstract val name: String
    abstract val description: String
    abstract val seeAlsoContent: String

    val path get() = payload.path

    val mimeType: String
        get() = when (payload.fileType) {
            "kts" -> "text/x-kotlin"
            "md" -> "text/markdown"
            else -> "text/plain"
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
    abstract val tocUri: String
    abstract val tocName: String
    abstract val tocContent: String
}
