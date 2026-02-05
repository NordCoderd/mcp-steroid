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

sealed class PromptBase {
    fun readPrompt(): String {
        return readPromptInternal()
    }

    protected abstract fun readPromptInternal(): String
}
