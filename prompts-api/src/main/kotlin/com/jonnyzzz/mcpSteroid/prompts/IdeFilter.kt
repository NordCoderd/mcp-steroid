/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.prompts

/**
 * Composable predicate for filtering prompt content based on IDE product and version.
 *
 * Every content part (md segment, kt block, conditional branch) carries its own [IdeFilter].
 * Even else/else-if branches get explicit predicates (via [not] and [and]), so every part
 * is self-describing and can be reused in both generated payload code and test infrastructure.
 */
sealed interface IdeFilter {
    fun matches(context: PromptsContext): Boolean

    fun not(): IdeFilter = Not(this)
    fun and(other: IdeFilter): IdeFilter = And(this, other)
    fun or(other: IdeFilter): IdeFilter = Or(this, other)

    /** Matches everything — used for unconditional parts. */
    data object All : IdeFilter {
        override fun matches(context: PromptsContext) = true
        override fun not(): IdeFilter = error("Cannot negate All filter")
    }

    /** Matches specific product codes and/or version range. */
    data class Ide(
        val productCodes: Set<String>,
        val minVersion: Int? = null,
        val maxVersion: Int? = null,
    ) : IdeFilter {
        override fun matches(context: PromptsContext): Boolean {
            if (productCodes.isNotEmpty() && context.productCode !in productCodes) return false
            if (minVersion != null && context.baselineVersion < minVersion) return false
            if (maxVersion != null && context.baselineVersion > maxVersion) return false
            return true
        }
    }

    /** Negated filter — used for ELSE branches. */
    data class Not(val inner: IdeFilter) : IdeFilter {
        override fun matches(context: PromptsContext) = !inner.matches(context)
        override fun not(): IdeFilter = inner
    }

    /** Conjunction — used for ELSE_IF branches (not(previous) AND this). */
    data class And(val left: IdeFilter, val right: IdeFilter) : IdeFilter {
        override fun matches(context: PromptsContext) =
            left.matches(context) && right.matches(context)
    }

    /** Disjunction — used for article-level OR-union of code block filters. */
    data class Or(val left: IdeFilter, val right: IdeFilter) : IdeFilter {
        override fun matches(context: PromptsContext) =
            left.matches(context) || right.matches(context)
    }
}
