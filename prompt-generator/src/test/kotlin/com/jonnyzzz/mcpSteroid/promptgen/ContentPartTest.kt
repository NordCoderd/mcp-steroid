/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.promptgen

import com.jonnyzzz.mcpSteroid.prompts.IdeFilter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContentPartTest {

    /** Helper: wraps body in a valid article format, parses, and builds content parts. */
    private fun partsFromBody(body: String): List<ContentPart> {
        val content = "Title\n\nDescription\n\n$body"
        val nfParts = parseNewFormatArticleParts(content)
        return buildContentParts(nfParts)
    }

    @Test
    fun `plain markdown produces single All part`() {
        val parts = partsFromBody("Some text\nMore text\n")
        assertEquals(1, parts.size)
        assertEquals(IdeFilter.All, parts[0].filter)
        assertEquals(false, parts[0].isKotlinBlock)
    }

    @Test
    fun `kotlin block produces separate part`() {
        val parts = partsFromBody("Before\n```kotlin\nval x = 1\n```\nAfter\n")
        assertEquals(3, parts.size)
        assertEquals("Before\n", parts[0].content)
        assertEquals(false, parts[0].isKotlinBlock)
        assertEquals("val x = 1\n", parts[1].content)
        assertEquals(true, parts[1].isKotlinBlock)
        assertEquals(IdeFilter.All, parts[1].filter)
        assertEquals("\nAfter\n", parts[2].content)
    }

    @Test
    fun `annotated kotlin block gets fence filter`() {
        val parts = partsFromBody("Before\n```kotlin[RD]\nval x = 1\n```\nAfter\n")
        assertEquals(3, parts.size)
        val ktPart = parts[1]
        assertEquals(true, ktPart.isKotlinBlock)
        assertEquals(IdeFilter.Ide(setOf("RD")), ktPart.filter)
        assertEquals(setOf("RD"), ktPart.fenceMetadata?.productCodes)
    }

    @Test
    fun `conditional section splits into filtered parts`() {
        val parts = partsFromBody("###_IF_IDE[RD]_###\nRider text\n###_ELSE_###\nOther text\n###_END_IF_###\n")
        assertEquals(2, parts.size)
        assertEquals("Rider text", parts[0].content)
        assertEquals(IdeFilter.Ide(setOf("RD")), parts[0].filter)
        assertEquals("Other text", parts[1].content)
        assertTrue(parts[1].filter is IdeFilter.Not)
    }

    @Test
    fun `kotlin block inside conditional inherits conditional filter`() {
        val parts = partsFromBody("###_IF_IDE[RD]_###\nRider text\n```kotlin\nval x = 1\n```\nMore\n###_END_IF_###\n")
        // Should have: md(RD "Rider text\n"), kt(RD), md(RD "\nMore")
        val mdParts = parts.filter { !it.isKotlinBlock }
        val ktParts = parts.filter { it.isKotlinBlock }
        assertEquals(1, ktParts.size)
        assertEquals("val x = 1\n", ktParts[0].content)
        assertEquals(IdeFilter.Ide(setOf("RD")), ktParts[0].filter)
        // md parts should also have RD filter
        assertTrue(mdParts.all { it.filter == IdeFilter.Ide(setOf("RD")) })
    }

    @Test
    fun `annotated kotlin block inside conditional gets composed filter`() {
        val parts = partsFromBody("###_IF_IDE[RD]_###\nText\n```kotlin[RD;>=254]\nval x = 1\n```\n###_END_IF_###\n")
        val ktPart = parts.single { it.isKotlinBlock }
        // Filter should be Ide(RD) AND Ide(RD,>=254)
        assertTrue(ktPart.filter is IdeFilter.And)
    }

    @Test
    fun `unconditional content before and after conditional`() {
        val parts = partsFromBody("Intro\n###_IF_IDE[RD]_###\nRider\n###_END_IF_###\nOutro\n")
        val mdParts = parts.filter { !it.isKotlinBlock }
        assertEquals(3, mdParts.size)
        assertEquals(IdeFilter.All, mdParts[0].filter)
        assertTrue(mdParts[0].content.contains("Intro"))
        assertEquals(IdeFilter.Ide(setOf("RD")), mdParts[1].filter)
        assertEquals(IdeFilter.All, mdParts[2].filter)
        assertTrue(mdParts[2].content.contains("Outro"))
    }

    @Test
    fun `old-style IF_RIDER parsed correctly`() {
        val parts = partsFromBody("###_IF_RIDER_###\nRider text\n###_ELSE_###\nOther\n###_END_IF_###\n")
        assertEquals(2, parts.size)
        assertEquals(IdeFilter.Ide(setOf("RD")), parts[0].filter)
    }

    @Test
    fun `else-if chain produces correct filters`() {
        val parts = partsFromBody("###_IF_IDE[RD]_###\nRider\n###_ELSE_IF_IDE[CL]_###\nCLion\n###_ELSE_###\nOther\n###_END_IF_###\n")
        assertEquals(3, parts.size)
        // IF: Ide(RD)
        assertEquals(IdeFilter.Ide(setOf("RD")), parts[0].filter)
        // ELSE_IF: not(Ide(RD)) AND Ide(CL)
        assertTrue(parts[1].filter is IdeFilter.And)
        // ELSE: not(Ide(CL))
        assertTrue(parts[2].filter is IdeFilter.Not)
    }

    @Test
    fun `text fenced blocks stay in markdown, not extracted as kotlin`() {
        val parts = partsFromBody("Before\n```text\nval x = 1\n```\nAfter\n")
        // text blocks are not kotlin — should be part of markdown
        val ktParts = parts.filter { it.isKotlinBlock }
        assertEquals(0, ktParts.size)
    }

    @Test
    fun `multiple kotlin blocks with mixed annotations`() {
        val parts = partsFromBody("A\n```kotlin\nval a = 1\n```\nB\n```kotlin[RD]\nval b = 2\n```\nC\n")
        val ktParts = parts.filter { it.isKotlinBlock }
        assertEquals(2, ktParts.size)
        // Block 1: no annotation → All
        assertEquals("val a = 1\n", ktParts[0].content)
        assertEquals(IdeFilter.All, ktParts[0].filter)
        // Block 2: [RD]
        assertEquals("val b = 2\n", ktParts[1].content)
        assertEquals(IdeFilter.Ide(setOf("RD")), ktParts[1].filter)
    }

    @Test
    fun `conditional spanning code block boundary`() {
        // Conditional opens before a kt block and closes after it
        val parts = partsFromBody(
            "###_IF_IDE[RD]_###\nBefore code\n```kotlin\nval x = 1\n```\nAfter code\n###_END_IF_###\nUnconditional\n"
        )
        val ktParts = parts.filter { it.isKotlinBlock }
        assertEquals(1, ktParts.size)
        assertEquals(IdeFilter.Ide(setOf("RD")), ktParts[0].filter)

        val mdParts = parts.filter { !it.isKotlinBlock }
        // "Before code" → RD, "After code" → RD, "Unconditional" → All
        val rdMdParts = mdParts.filter { it.filter == IdeFilter.Ide(setOf("RD")) }
        val allMdParts = mdParts.filter { it.filter == IdeFilter.All }
        assertTrue(rdMdParts.isNotEmpty(), "Should have RD-filtered md parts")
        assertTrue(allMdParts.isNotEmpty(), "Should have unconditional md parts")
    }

    @Test
    fun `conditional with else spanning code block boundary`() {
        // IF opens, kt block, IF closes in next md segment
        val parts = partsFromBody(
            "###_IF_IDE[RD]_###\nRider intro\n```kotlin\nval x = 1\n```\nRider outro\n###_ELSE_###\nOther content\n###_END_IF_###\n"
        )
        val ktParts = parts.filter { it.isKotlinBlock }
        assertEquals(1, ktParts.size)
        assertEquals(IdeFilter.Ide(setOf("RD")), ktParts[0].filter)

        // Rider md parts
        val riderMd = parts.filter { !it.isKotlinBlock && it.filter == IdeFilter.Ide(setOf("RD")) }
        assertTrue(riderMd.isNotEmpty())

        // ELSE part
        val elseParts = parts.filter { !it.isKotlinBlock && it.filter is IdeFilter.Not }
        assertTrue(elseParts.isNotEmpty())
        assertTrue(elseParts.any { it.content.contains("Other content") })
    }
}
