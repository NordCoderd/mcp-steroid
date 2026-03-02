/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.promptgen

import com.jonnyzzz.mcpSteroid.prompts.IdeFilter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ParseNewFormatArticlePartsTest {

    @Test
    fun `minimal article with empty body`() {
        val content = "Title\n\nDescription\n\n"
        val parts = parseNewFormatArticleParts(content)
        assertEquals("Title", parts.title)
        assertEquals(IdeFilter.All, parts.rootFilter)
        assertEquals("Description", parts.description)
        assertEquals(listOf(""), parts.mdBodyParts)
        assertEquals(emptyList<KtBlockWithMeta>(), parts.ktBodyParts)
        assertNull(parts.seeAlsoManual)
    }

    @Test
    fun `article with filter on line 2`() {
        val content = "Title\n[RD]\nDescription\n\nBody text\n"
        val parts = parseNewFormatArticleParts(content)
        assertEquals("Title", parts.title)
        assertEquals(IdeFilter.Ide(setOf("RD"), null, null), parts.rootFilter)
        assertEquals("Description", parts.description)
        assertEquals(listOf("Body text\n"), parts.mdBodyParts)
    }

    @Test
    fun `article with multi-code filter on line 2`() {
        val content = "Title\n[IU,RD]\nDescription\n\nBody\n"
        val parts = parseNewFormatArticleParts(content)
        assertEquals(IdeFilter.Ide(setOf("IU", "RD"), null, null), parts.rootFilter)
    }

    @Test
    fun `article with version filter on line 2`() {
        val content = "Title\n[RD;>=254]\nDescription\n\nBody\n"
        val parts = parseNewFormatArticleParts(content)
        assertEquals(IdeFilter.Ide(setOf("RD"), 254, null), parts.rootFilter)
    }

    @Test
    fun `round-trip article with filter on line 2`() {
        val content = "Title\n[RD]\nDescription\n\nBody text\n"
        val parts = parseNewFormatArticleParts(content)
        assertEquals(content, assembleFullContent(parts))
    }

    @Test
    fun `round-trip article with multi-code filter`() {
        val content = "Title\n[IU,RD]\nDescription\n\nBody text\n"
        val parts = parseNewFormatArticleParts(content)
        assertEquals(content, assembleFullContent(parts))
    }

    @Test
    fun `multi-line description`() {
        val content = "Title\n\nFirst line of desc\nSecond line of desc\n\nBody text\n"
        val parts = parseNewFormatArticleParts(content)
        assertEquals("Title", parts.title)
        assertEquals("First line of desc\nSecond line of desc", parts.description)
        assertEquals(listOf("Body text\n"), parts.mdBodyParts)
    }

    @Test
    fun `round-trip multi-line description`() {
        val content = "Title\n\nFirst line of desc\nSecond line of desc\n\nBody text\n"
        val parts = parseNewFormatArticleParts(content)
        assertEquals(content, assembleFullContent(parts))
    }

    @Test
    fun `multi-line description with filter`() {
        val content = "Title\n[RD]\nFirst desc\nSecond desc\n\nBody\n"
        val parts = parseNewFormatArticleParts(content)
        assertEquals(IdeFilter.Ide(setOf("RD"), null, null), parts.rootFilter)
        assertEquals("First desc\nSecond desc", parts.description)
        assertEquals(listOf("Body\n"), parts.mdBodyParts)
    }

    @Test
    fun `round-trip multi-line description with filter`() {
        val content = "Title\n[RD]\nFirst desc\nSecond desc\n\nBody\n"
        val parts = parseNewFormatArticleParts(content)
        assertEquals(content, assembleFullContent(parts))
    }

    @Test
    fun `article with body text no kotlin blocks`() {
        val content = "Title\n\nDescription\n\nSome body text\nMore text\n"
        val parts = parseNewFormatArticleParts(content)
        assertEquals("Title", parts.title)
        assertEquals("Description", parts.description)
        assertEquals(listOf("Some body text\nMore text\n"), parts.mdBodyParts)
        assertEquals(emptyList<KtBlockWithMeta>(), parts.ktBodyParts)
        assertNull(parts.seeAlsoManual)
    }

    @Test
    fun `article with one kotlin block`() {
        val content = "Title\n\nDescription\n\nBefore code\n```kotlin\nval x = 1\n```\nAfter code\n"
        val parts = parseNewFormatArticleParts(content)
        assertEquals("Title", parts.title)
        assertEquals("Description", parts.description)
        assertEquals(2, parts.mdBodyParts.size)
        assertEquals("Before code\n", parts.mdBodyParts[0])
        assertEquals("\nAfter code\n", parts.mdBodyParts[1])
        assertEquals(listOf("val x = 1\n"), parts.ktBodyParts.map { it.code })
        assertNull(parts.seeAlsoManual)
    }

    @Test
    fun `article with multiple kotlin blocks interleaved with markdown`() {
        val content = "Title\n\nDescription\n\nFirst md\n```kotlin\nval a = 1\n```\nMiddle md\n```kotlin\nval b = 2\n```\nEnd md\n"
        val parts = parseNewFormatArticleParts(content)
        assertEquals(3, parts.mdBodyParts.size)
        assertEquals("First md\n", parts.mdBodyParts[0])
        assertEquals("\nMiddle md\n", parts.mdBodyParts[1])
        assertEquals("\nEnd md\n", parts.mdBodyParts[2])
        assertEquals(2, parts.ktBodyParts.size)
        assertEquals("val a = 1\n", parts.ktBodyParts[0].code)
        assertEquals("val b = 2\n", parts.ktBodyParts[1].code)
    }

    @Test
    fun `article with see also section`() {
        val content = "Title\n\nDescription\n\nBody text\n\n# See also\n\n- [Link](uri)\n"
        val parts = parseNewFormatArticleParts(content)
        assertEquals("Body text", parts.mdBodyParts[0])
        assertEquals("- [Link](uri)\n", parts.seeAlsoManual)
    }

    @Test
    fun `article with kotlin blocks and see also`() {
        val content = "Title\n\nDescription\n\nBefore\n```kotlin\nval x = 1\n```\nAfter\n\n# See also\n\n- [Link](uri)\n"
        val parts = parseNewFormatArticleParts(content)
        assertEquals(2, parts.mdBodyParts.size)
        assertEquals("Before\n", parts.mdBodyParts[0])
        assertEquals("\nAfter", parts.mdBodyParts[1])
        assertEquals(listOf("val x = 1\n"), parts.ktBodyParts.map { it.code })
        assertEquals("- [Link](uri)\n", parts.seeAlsoManual)
    }

    @Test
    fun `round-trip minimal article`() {
        val content = "Title\n\nDescription\n\n"
        val parts = parseNewFormatArticleParts(content)
        assertEquals(content, assembleFullContent(parts))
    }

    @Test
    fun `round-trip article with body`() {
        val content = "Title\n\nDescription\n\nSome body text\nMore text\n"
        val parts = parseNewFormatArticleParts(content)
        assertEquals(content, assembleFullContent(parts))
    }

    @Test
    fun `round-trip article with kotlin block`() {
        val content = "Title\n\nDescription\n\nBefore\n```kotlin\nval x = 1\n```\nAfter\n"
        val parts = parseNewFormatArticleParts(content)
        assertEquals(content, assembleFullContent(parts))
    }

    @Test
    fun `round-trip article with see also`() {
        val content = "Title\n\nDescription\n\nBody\n\n# See also\n\n- [Link](uri)\n"
        val parts = parseNewFormatArticleParts(content)
        assertEquals(content, assembleFullContent(parts))
    }

    @Test
    fun `round-trip article with kotlin blocks and see also`() {
        val content = "Title\n\nDescription\n\nBefore\n```kotlin\nval a = 1\n```\nMiddle\n```kotlin\nval b = 2\n```\nEnd\n\n# See also\n\n- [Link1](uri1)\n- [Link2](uri2)\n"
        val parts = parseNewFormatArticleParts(content)
        assertEquals(content, assembleFullContent(parts))
    }

    @Test
    fun `error too few lines`() {
        assertThrows<IllegalArgumentException> {
            parseNewFormatArticleParts("Title\n\nDescription")
        }
    }

    @Test
    fun `error non-blank non-filter line 2`() {
        assertThrows<IllegalArgumentException> {
            parseNewFormatArticleParts("Title\nNot blank\nDescription\n\n")
        }
    }

    @Test
    fun `non-blank line 4 is multi-line description`() {
        val content = "Title\n\nDescription\nSecond line\n\n"
        val parts = parseNewFormatArticleParts(content)
        assertEquals("Description\nSecond line", parts.description)
        assertEquals(listOf(""), parts.mdBodyParts)
    }

    @Test
    fun `error description without blank separator`() {
        // 4 lines but no blank separator after description
        assertThrows<IllegalArgumentException> {
            parseNewFormatArticleParts("Title\n\nDescription\nMore")
        }
    }

    @Test
    fun `error unclosed kotlin block`() {
        assertThrows<IllegalArgumentException> {
            parseNewFormatArticleParts("Title\n\nDescription\n\n```kotlin\nval x = 1\n")
        }
    }

    @Test
    fun `article with annotated kotlin block`() {
        val content = "Title\n\nDescription\n\nBefore\n```kotlin[RD]\nval x = 1\n```\nAfter\n"
        val parts = parseNewFormatArticleParts(content)
        assertEquals(1, parts.ktBodyParts.size)
        assertEquals("val x = 1\n", parts.ktBodyParts[0].code)
        assertEquals(setOf("RD"), parts.ktBodyParts[0].metadata.productCodes)
    }

    @Test
    fun `round-trip article with annotated kotlin block`() {
        val content = "Title\n\nDescription\n\nBefore\n```kotlin[RD]\nval x = 1\n```\nAfter\n"
        val parts = parseNewFormatArticleParts(content)
        assertEquals(content, assembleFullContent(parts))
    }

    @Test
    fun `round-trip article with multiple annotated blocks`() {
        val content = "Title\n\nDescription\n\nFirst\n```kotlin\nval a = 1\n```\nMiddle\n```kotlin[RD;>=254]\nval b = 2\n```\nEnd\n"
        val parts = parseNewFormatArticleParts(content)
        assertEquals(content, assembleFullContent(parts))
    }

    @Test
    fun `text fenced blocks treated as markdown, not kotlin`() {
        val content = "Title\n\nDescription\n\nSome text\n```text\nval x = 1\n```\nAfter\n"
        val parts = parseNewFormatArticleParts(content)
        assertEquals(0, parts.ktBodyParts.size, "text blocks should not be extracted as kotlin")
        assertEquals(1, parts.mdBodyParts.size)
    }

    @Test
    fun `round-trip article with filter and see also`() {
        val content = "Title\n[RD]\nDescription\n\nBody\n\n# See also\n\n- [Link](uri)\n"
        val parts = parseNewFormatArticleParts(content)
        assertEquals(content, assembleFullContent(parts))
    }
}
