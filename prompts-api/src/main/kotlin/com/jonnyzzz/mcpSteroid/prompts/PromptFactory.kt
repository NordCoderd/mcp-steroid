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
    abstract val ownFilter: IdeFilter
    abstract val description: PromptBase
    abstract val parts: List<ArticlePart>
    abstract val seeAlsoItems: List<SeeAlsoItem>

    val filter: IdeFilter get() {
        val ktFilters = parts.filterIsInstance<ArticlePart.KotlinCode>().map { it.filter }
        if (ktFilters.isEmpty()) return ownFilter
        val orUnion = if (ktFilters.size == 1) ktFilters[0] else IdeFilter.Or(ktFilters)
        return ownFilter.and(orUnion)
    }

    /** Renders full article: title, description, body, see-also — all filtered by context. */
    fun readPayload(context: PromptsContext): String = buildString {
        appendLine(title.readPrompt())
        appendLine()
        appendLine(description.readPrompt())
        appendLine()
        for (part in parts) {
            if (!part.filter.matches(context)) continue
            when (part) {
                is ArticlePart.KotlinCode -> {
                    appendLine("```kotlin")
                    append(part.readPrompt())
                    appendLine("```")
                }
                is ArticlePart.Markdown -> appendLine(part.readPrompt())
            }
        }
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
