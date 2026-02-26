/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import junit.framework.TestCase

class ProductConditionalsTest : TestCase() {

    fun testNoConditionals() {
        val content = "Hello world\nNo conditionals here"
        assertEquals(content, ProductConditionals.processForProduct(content, "IU"))
        assertEquals(content, ProductConditionals.processForProduct(content, "RD"))
    }

    fun testIfBlockIncludedWhenMatches() {
        val content = """
            |before
            |###_IF_RIDER_###
            |rider content
            |###_END_IF_###
            |after
        """.trimMargin()

        val result = ProductConditionals.processForProduct(content, "RD")
        assertEquals("before\nrider content\nafter", result)
    }

    fun testIfBlockExcludedWhenNotMatches() {
        val content = """
            |before
            |###_IF_RIDER_###
            |rider content
            |###_END_IF_###
            |after
        """.trimMargin()

        val result = ProductConditionals.processForProduct(content, "IU")
        assertEquals("before\nafter", result)
    }

    fun testIfElseBlockMatchesIf() {
        val content = """
            |before
            |###_IF_RIDER_###
            |rider content
            |###_ELSE_###
            |other content
            |###_END_IF_###
            |after
        """.trimMargin()

        val result = ProductConditionals.processForProduct(content, "RD")
        assertEquals("before\nrider content\nafter", result)
    }

    fun testIfElseBlockMatchesElse() {
        val content = """
            |before
            |###_IF_RIDER_###
            |rider content
            |###_ELSE_###
            |other content
            |###_END_IF_###
            |after
        """.trimMargin()

        val result = ProductConditionals.processForProduct(content, "IU")
        assertEquals("before\nother content\nafter", result)
    }

    fun testIdeaMatchesBothUltimateAndCommunity() {
        val content = """
            |###_IF_IDEA_###
            |idea content
            |###_END_IF_###
        """.trimMargin()

        assertEquals("idea content", ProductConditionals.processForProduct(content, "IU"))
        assertEquals("idea content", ProductConditionals.processForProduct(content, "IC"))
        assertEquals("", ProductConditionals.processForProduct(content, "RD"))
    }

    fun testMultipleConditionalBlocks() {
        val content = """
            |shared
            |###_IF_RIDER_###
            |rider stuff
            |###_END_IF_###
            |middle
            |###_IF_IDEA_###
            |idea stuff
            |###_END_IF_###
            |end
        """.trimMargin()

        val riderResult = ProductConditionals.processForProduct(content, "RD")
        assertEquals("shared\nrider stuff\nmiddle\nend", riderResult)

        val ideaResult = ProductConditionals.processForProduct(content, "IU")
        assertEquals("shared\nmiddle\nidea stuff\nend", ideaResult)
    }

    fun testMultilineBlocks() {
        val content = """
            |before
            |###_IF_RIDER_###
            |line 1
            |line 2
            |line 3
            |###_ELSE_###
            |alt 1
            |alt 2
            |###_END_IF_###
            |after
        """.trimMargin()

        val riderResult = ProductConditionals.processForProduct(content, "RD")
        assertEquals("before\nline 1\nline 2\nline 3\nafter", riderResult)

        val ideaResult = ProductConditionals.processForProduct(content, "IU")
        assertEquals("before\nalt 1\nalt 2\nafter", ideaResult)
    }

    fun testEmptyIfBlock() {
        val content = """
            |before
            |###_IF_RIDER_###
            |###_ELSE_###
            |fallback
            |###_END_IF_###
            |after
        """.trimMargin()

        val riderResult = ProductConditionals.processForProduct(content, "RD")
        assertEquals("before\nafter", riderResult)

        val ideaResult = ProductConditionals.processForProduct(content, "IU")
        assertEquals("before\nfallback\nafter", ideaResult)
    }

    fun testEmptyElseBlock() {
        val content = """
            |before
            |###_IF_RIDER_###
            |rider only
            |###_ELSE_###
            |###_END_IF_###
            |after
        """.trimMargin()

        val riderResult = ProductConditionals.processForProduct(content, "RD")
        assertEquals("before\nrider only\nafter", riderResult)

        val ideaResult = ProductConditionals.processForProduct(content, "IU")
        assertEquals("before\nafter", ideaResult)
    }

    fun testGolandProduct() {
        val content = """
            |###_IF_GOLAND_###
            |go content
            |###_END_IF_###
        """.trimMargin()

        assertEquals("go content", ProductConditionals.processForProduct(content, "GO"))
        assertEquals("", ProductConditionals.processForProduct(content, "IU"))
    }

    fun testHasConditionals() {
        assertTrue(ProductConditionals.hasConditionals("###_IF_RIDER_###"))
        assertTrue(ProductConditionals.hasConditionals("text ###_IF_IDEA_### more"))
        assertFalse(ProductConditionals.hasConditionals("no conditionals"))
        assertFalse(ProductConditionals.hasConditionals("###_NO_AUTO_TOC_###"))
    }

    fun testUnknownProductToken() {
        val content = """
            |before
            |###_IF_UNKNOWN_###
            |mystery content
            |###_ELSE_###
            |fallback
            |###_END_IF_###
            |after
        """.trimMargin()

        // Unknown token never matches — always falls through to ELSE
        val result = ProductConditionals.processForProduct(content, "RD")
        assertEquals("before\nfallback\nafter", result)
    }

    fun testConditionalAtStartOfContent() {
        val content = """
            |###_IF_RIDER_###
            |rider first
            |###_END_IF_###
            |rest
        """.trimMargin()

        assertEquals("rider first\nrest", ProductConditionals.processForProduct(content, "RD"))
        assertEquals("rest", ProductConditionals.processForProduct(content, "IU"))
    }

    fun testConditionalAtEndOfContent() {
        val content = """
            |start
            |###_IF_RIDER_###
            |rider last
            |###_END_IF_###
        """.trimMargin()

        assertEquals("start\nrider last", ProductConditionals.processForProduct(content, "RD"))
        assertEquals("start", ProductConditionals.processForProduct(content, "IU"))
    }
}
