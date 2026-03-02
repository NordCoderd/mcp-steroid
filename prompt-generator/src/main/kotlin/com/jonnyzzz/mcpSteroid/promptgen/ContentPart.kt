/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.promptgen

import com.jonnyzzz.mcpSteroid.prompts.IdeFilter

/**
 * A content part with its IDE filter — the build-time representation used by code generation.
 *
 * Produced by [buildContentParts] from parsed [NewFormatParts].
 */
data class ContentPart(
    val content: String,
    val filter: IdeFilter,
    val isKotlinBlock: Boolean,
    val fenceMetadata: FenceMetadata? = null,
)

private val IF_IDE_PATTERN = Regex("""^###_IF_IDE\[([^\]]*)]_###$""")
private val ELSE_IF_IDE_PATTERN = Regex("""^###_ELSE_IF_IDE\[([^\]]*)]_###$""")
private const val ELSE_MARKER = "###_ELSE_###"
private const val END_IF_MARKER = "###_END_IF_###"

// Also match the old-style ###_IF_RIDER_### etc for forward compatibility during migration
private val OLD_IF_PATTERN = Regex("""^###_IF_(\w+)_###$""")

private val OLD_PRODUCT_TOKEN_TO_CODES = mapOf(
    "RIDER" to setOf("RD"),
    "IDEA" to setOf("IU"),
    "GOLAND" to setOf("GO"),
    "CLION" to setOf("CL"),
    "PYCHARM" to setOf("PY"),
    "WEBSTORM" to setOf("WS"),
    "RUBYMINE" to setOf("RM"),
    "DATAGRIP" to setOf("DB"),
)

/**
 * Tracks conditional directive state across segments, so that conditionals
 * can span across code block boundaries.
 */
private class ConditionalState {
    var currentFilter: IdeFilter = IdeFilter.All
        private set

    private data class Frame(
        var previousFilters: IdeFilter,
        val outerFilter: IdeFilter,
    )

    private val stack = mutableListOf<Frame>()

    val isInsideConditional: Boolean get() = stack.isNotEmpty()

    fun enterIf(ifFilter: IdeFilter) {
        stack.add(Frame(previousFilters = ifFilter, outerFilter = currentFilter))
        currentFilter = composeWithOuter(ifFilter)
    }

    fun enterElseIf(branchFilter: IdeFilter) {
        require(stack.isNotEmpty()) { "###_ELSE_IF_IDE without matching ###_IF_IDE" }
        val frame = stack.last()
        val elseIfFilter = frame.previousFilters.not().and(branchFilter)
        frame.previousFilters = branchFilter
        currentFilter = composeWithOuter(elseIfFilter)
    }

    fun enterElse() {
        require(stack.isNotEmpty()) { "###_ELSE without matching ###_IF_IDE" }
        val frame = stack.last()
        val elseFilter = frame.previousFilters.not()
        currentFilter = composeWithOuter(elseFilter)
    }

    fun exitIf() {
        require(stack.isNotEmpty()) { "###_END_IF without matching ###_IF_IDE" }
        val frame = stack.removeLast()
        currentFilter = frame.outerFilter
    }

    private fun composeWithOuter(filter: IdeFilter): IdeFilter {
        val outer = if (stack.isNotEmpty()) stack.last().outerFilter else IdeFilter.All
        return if (outer is IdeFilter.All) filter else outer.and(filter)
    }
}

/**
 * Converts parsed [NewFormatParts] into a flat list of [ContentPart] items,
 * each carrying its own [IdeFilter] predicate.
 *
 * Processing:
 * 1. Iterates the interleaved md and kt segments from [parseNewFormatArticleParts]
 *    (which preserves exact character boundaries from regex-based splitting)
 * 2. Each md segment is split at conditional directive lines, with state carried
 *    across segments — so conditionals can span code block boundaries
 * 3. Each kt block gets a filter composed from its fence annotation AND the
 *    enclosing conditional (if any)
 */
fun buildContentParts(parts: NewFormatParts): List<ContentPart> {
    val result = mutableListOf<ContentPart>()
    val state = ConditionalState()

    for (i in parts.mdBodyParts.indices) {
        val md = parts.mdBodyParts[i]
        if (md.isNotEmpty()) {
            result.addAll(splitConditionals(md, state))
        }
        if (i < parts.ktBodyParts.size) {
            val block = parts.ktBodyParts[i]
            val fenceFilter = if (block.metadata.isDefault) {
                IdeFilter.All
            } else {
                IdeFilter.Ide(
                    productCodes = block.metadata.productCodes,
                    minVersion = block.metadata.minVersion,
                    maxVersion = block.metadata.maxVersion,
                )
            }
            // Compose fence annotation with enclosing conditional filter
            val composedFilter = when {
                state.currentFilter is IdeFilter.All -> fenceFilter
                fenceFilter is IdeFilter.All -> state.currentFilter
                else -> state.currentFilter.and(fenceFilter)
            }
            result.add(ContentPart(block.code, composedFilter, isKotlinBlock = true, fenceMetadata = block.metadata))
        }
    }

    return result
}

/**
 * Splits a markdown segment at conditional directive boundaries.
 *
 * The [state] tracks conditional nesting across segments, so an `###_IF_IDE[...]_###`
 * in one md segment can continue through kotlin blocks into the next md segment.
 *
 * Returns a list of [ContentPart] items with appropriate [IdeFilter] predicates.
 */
private fun splitConditionals(md: String, state: ConditionalState): List<ContentPart> {
    val lines = md.lines()

    // Quick check: if no conditionals and not inside one, return as-is
    if (!state.isInsideConditional && lines.none { lineIsDirective(it.trim()) }) {
        return listOf(ContentPart(md, state.currentFilter, isKotlinBlock = false))
    }

    val result = mutableListOf<ContentPart>()
    val currentLines = mutableListOf<String>()

    fun flushLines() {
        if (currentLines.isEmpty()) return
        val text = currentLines.joinToString("\n")
        currentLines.clear()
        if (text.isNotEmpty()) {
            result.add(ContentPart(text, state.currentFilter, isKotlinBlock = false))
        }
    }

    for (line in lines) {
        val trimmed = line.trim()

        // Check for new-style ###_IF_IDE[...]_###
        val newIfMatch = IF_IDE_PATTERN.matchEntire(trimmed)
        val oldIfMatch = if (newIfMatch == null) OLD_IF_PATTERN.matchEntire(trimmed) else null

        if (newIfMatch != null || oldIfMatch != null) {
            flushLines()
            val ifFilter: IdeFilter = if (newIfMatch != null) {
                val bracket = newIfMatch.groupValues[1]
                val meta = FenceMetadata.parse(bracket)
                IdeFilter.Ide(meta.productCodes, meta.minVersion, meta.maxVersion)
            } else {
                val token = oldIfMatch!!.groupValues[1]
                val codes = OLD_PRODUCT_TOKEN_TO_CODES[token]
                    ?: error("Unknown product token '$token' in old-style conditional")
                IdeFilter.Ide(codes)
            }
            state.enterIf(ifFilter)
            continue
        }

        val elseIfMatch = ELSE_IF_IDE_PATTERN.matchEntire(trimmed)
        if (elseIfMatch != null) {
            flushLines()
            val bracket = elseIfMatch.groupValues[1]
            val meta = FenceMetadata.parse(bracket)
            state.enterElseIf(IdeFilter.Ide(meta.productCodes, meta.minVersion, meta.maxVersion))
            continue
        }

        if (trimmed == ELSE_MARKER && state.isInsideConditional) {
            flushLines()
            state.enterElse()
            continue
        }

        if (trimmed == END_IF_MARKER && state.isInsideConditional) {
            flushLines()
            state.exitIf()
            continue
        }

        // Strip build-time-only TOC directives from output content
        if (trimmed == NO_AUTO_TOC_MARKER || trimmed == EXCLUDE_FROM_AUTO_TOC_MARKER) {
            continue
        }

        currentLines.add(line)
    }

    // Flush remaining — don't close unclosed conditionals (they continue into next segment)
    flushLines()

    return result
}

private fun lineIsDirective(trimmed: String): Boolean =
    trimmed.startsWith("###_IF_") ||
            trimmed == ELSE_MARKER ||
            trimmed == END_IF_MARKER ||
            trimmed == NO_AUTO_TOC_MARKER ||
            trimmed == EXCLUDE_FROM_AUTO_TOC_MARKER ||
            ELSE_IF_IDE_PATTERN.matchEntire(trimmed) != null
