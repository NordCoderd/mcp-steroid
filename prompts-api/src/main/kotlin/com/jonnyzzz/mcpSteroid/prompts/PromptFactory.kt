/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.prompts

import kotlin.sequences.SequenceScope

abstract class PromptBase {
    abstract fun readPrompt(): String

    abstract val mimeType: String

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

sealed class ArticlePart : PromptBase() {
    abstract val filter: IdeFilter
    abstract class Markdown : ArticlePart()
    abstract class KotlinCode : ArticlePart()
}

data class SeeAlsoItem(
    val filter: IdeFilter,
    val text: String,
) {
    fun matchesContext(context: PromptsContext): Boolean = filter.matches(context)
}

abstract class ArticleBase {
    abstract val uri: String
    abstract val title: PromptBase
    abstract val filter: IdeFilter
    abstract val description: PromptBase
    abstract val parts: List<ArticlePart>
    abstract val seeAlsoItems: List<SeeAlsoItem>

    /** Renders the body: content parts filtered by context, without title/description/see-also. */
    fun readBody(context: PromptsContext): String = buildString {
        for (part in parts) {
            if (!part.filter.matches(context)) continue
            when (part) {
                is ArticlePart.KotlinCode -> {
                    append("```kotlin\n")
                    append(part.readPrompt())
                    append("```")
                }
                is ArticlePart.Markdown -> append(part.readPrompt())
            }
        }
    }

    /** Renders full article: title, description, body, see-also — all filtered by context. */
    fun readPayload(context: PromptsContext): String = buildString {
        appendLine(title.readPrompt())
        appendLine()
        appendLine(description.readPrompt())
        appendLine()
        append(readBody(context))
        val visibleItems = seeAlsoItems.filter { it.matchesContext(context) }
        if (visibleItems.isNotEmpty()) {
            append("\n\n# See also\n\n")
            visibleItems.forEach { appendLine(it.text) }
        }
    }

    fun asSeeAlsoItem(): SeeAlsoItem = SeeAlsoItem(
        filter = this.filter,
        text = "- [${title.readPrompt()}]($uri) - ${description.readPrompt()}"
    )
}

abstract class PromptRootBase {
    abstract val roots: Map<String, PromptIndexBase>
}

abstract class PromptIndexBase {
    abstract val articles: Map<String, ArticleBase>
}
