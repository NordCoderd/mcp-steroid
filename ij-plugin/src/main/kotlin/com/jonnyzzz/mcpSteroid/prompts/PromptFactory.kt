/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.prompts

import kotlin.sequences.SequenceScope

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

    /**
     * Returns a [PromptBase] whose content is the body of the [index]-th ` ```kotlin ``` `
     * block found in this prompt's content. Extraction happens at call time from [readPrompt].
     *
     * Used by article classes so that `ktBlock*` properties are derived from [payload] at
     * runtime rather than storing duplicate encoded content.
     */
    fun ktBlock(index: Int): PromptBase {
        val parent = this
        return object : PromptBase() {
            override val mimeType = "text/x-kotlin"
            override fun readPromptInternal() = extractKotlinBlock(parent.readPrompt(), index)
        }
    }

    protected inline fun decodePromptChunks(crossinline chunks: suspend SequenceScope<Sequence<String>>.() -> Unit): String =
        sequence { chunks() }
            .flatMap {
                val els = it.iterator()
                val seed = els.next().toInt()
                els.asSequence().flatMap {
                    it.splitToSequence("|").map { it.toInt() / seed }
                }
            }
            .joinToString("") { it.toChar().toString() }
}

private fun extractKotlinBlock(content: String, index: Int): String {
    val lines = content.lines()
    var blockIndex = 0
    var inBlock = false
    val current = StringBuilder()
    for (line in lines) {
        when {
            !inBlock && line.trimStart().startsWith("```kotlin") -> {
                inBlock = true
                current.clear()
            }
            inBlock && line.trimStart() == "```" -> {
                if (blockIndex == index) return current.toString()
                blockIndex++
                inBlock = false
            }
            inBlock -> current.appendLine(line)
        }
    }
    error("Kotlin block #$index not found in prompt content (found $blockIndex blocks)")
}

abstract class PromptIndexBase {
    abstract val articles: Map<String, ArticleBase>
}
