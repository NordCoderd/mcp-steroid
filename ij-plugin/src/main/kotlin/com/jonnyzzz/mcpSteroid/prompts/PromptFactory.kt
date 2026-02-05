/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.prompts

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service

@Service(Service.Level.APP)
class PromptFactory {
    inline fun <reified T:PromptBase> renderPrompt() = renderPrompt(T::class.java.constructors.single().newInstance() as T)

    fun renderPrompt(prompt: PromptBase): String {
        return prompt.readPrompt()
    }
}

inline val promptFactory get() = service<PromptFactory>()

abstract class ArticleBase {

}

abstract class PromptBase : PromptReader {
    fun readPrompt(): String {
        return readPromptInternal()
    }

    abstract val fileType: String
    abstract val folder: String
    abstract val path: String

    protected abstract fun readPromptInternal(): String

    override fun <T> readPrompt(action: (PromptBase) -> T): T = action(this)
}

interface PromptReader {
    fun <T> readPrompt(action: (PromptBase) -> T): T
}

abstract class PromptIndexBase {
    protected abstract val files: Map<String, PromptReader>
    protected abstract val articles: Map<String, ArticleBase>

}

