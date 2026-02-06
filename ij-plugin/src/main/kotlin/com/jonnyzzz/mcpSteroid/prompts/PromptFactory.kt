/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.prompts

abstract class ArticleBase {
    abstract val payload: PromptBase

    abstract val uri: String
    abstract val name: String
    abstract val description: PromptBase?
    abstract val seeAlso: PromptBase?
}

abstract class PromptRootBase {
    abstract val roots: Map<String, PromptIndexBase>
}

abstract class PromptBase {
    fun readPrompt(): String {
        return readPromptInternal()
    }

    protected abstract fun readPromptInternal(): String

    open val mimeType: String = "text/plain"
}

abstract class PromptIndexBase {
    abstract val articles: Map<String, ArticleBase>
}
