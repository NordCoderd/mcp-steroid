/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.promptgen

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExtractKotlinBlocksTest {

    @Test
    fun `no blocks returns empty list`() {
        val content = "Some markdown\nNo code here\n"
        assertEquals(emptyList<String>(), extractKotlinBlocks(content))
    }

    @Test
    fun `single block`() {
        val content = "Before\n```kotlin\nval x = 1\n```\nAfter\n"
        val blocks = extractKotlinBlocks(content)
        assertEquals(1, blocks.size)
        assertEquals("val x = 1\n", blocks[0])
    }

    @Test
    fun `multiple blocks`() {
        val content = "Text\n```kotlin\nval a = 1\n```\nMiddle\n```kotlin\nval b = 2\nval c = 3\n```\nEnd\n"
        val blocks = extractKotlinBlocks(content)
        assertEquals(2, blocks.size)
        assertEquals("val a = 1\n", blocks[0])
        assertEquals("val b = 2\nval c = 3\n", blocks[1])
    }

    @Test
    fun `indented fences`() {
        val content = "Text\n  ```kotlin\n  val x = 1\n  ```\nEnd\n"
        val blocks = extractKotlinBlocks(content)
        assertEquals(1, blocks.size)
        assertEquals("  val x = 1\n", blocks[0])
    }

    @Test
    fun `empty block`() {
        val content = "Before\n```kotlin\n```\nAfter\n"
        val blocks = extractKotlinBlocks(content)
        assertEquals(1, blocks.size)
        assertEquals("", blocks[0])
    }

    @Test
    fun `unclosed block not captured`() {
        val content = "Before\n```kotlin\nval x = 1\nNo closing fence\n"
        val blocks = extractKotlinBlocks(content)
        assertEquals(emptyList<String>(), blocks)
    }

    @Test
    fun `block with multi-line code`() {
        val content = "```kotlin\nfun main() {\n    println(\"hello\")\n}\n```\n"
        val blocks = extractKotlinBlocks(content)
        assertEquals(1, blocks.size)
        assertEquals("fun main() {\n    println(\"hello\")\n}\n", blocks[0])
    }

    @Test
    fun `non-kotlin fenced blocks ignored`() {
        val content = "```java\nSystem.out.println();\n```\n```kotlin\nval x = 1\n```\n"
        val blocks = extractKotlinBlocks(content)
        assertEquals(1, blocks.size)
        assertEquals("val x = 1\n", blocks[0])
    }

    @Test
    fun `text fenced blocks are not treated as kotlin`() {
        val content = "```text\nval x = 1\n```\n```kotlin\nval y = 2\n```\n"
        val blocks = extractKotlinBlocks(content)
        assertEquals(1, blocks.size)
        assertEquals("val y = 2\n", blocks[0])
    }

    @Test
    fun `kotlin block with metadata extracted`() {
        val content = "```kotlin[RD]\nval x = 1\n```\n"
        val blocksWithMeta = extractKotlinBlocksWithMetadata(content)
        assertEquals(1, blocksWithMeta.size)
        assertEquals("val x = 1\n", blocksWithMeta[0].code)
        assertEquals(setOf("RD"), blocksWithMeta[0].metadata.productCodes)
    }

    @Test
    fun `kotlin block with multiple product codes and version`() {
        val content = "```kotlin[IU,RD;>=253]\nval x = 1\n```\n"
        val blocksWithMeta = extractKotlinBlocksWithMetadata(content)
        assertEquals(1, blocksWithMeta.size)
        assertEquals(setOf("IU", "RD"), blocksWithMeta[0].metadata.productCodes)
        assertEquals(253, blocksWithMeta[0].metadata.minVersion)
    }

    @Test
    fun `kotlin block without metadata has default`() {
        val content = "```kotlin\nval x = 1\n```\n"
        val blocksWithMeta = extractKotlinBlocksWithMetadata(content)
        assertEquals(1, blocksWithMeta.size)
        assertTrue(blocksWithMeta[0].metadata.isDefault)
    }

    @Test
    fun `mixed annotated and plain blocks`() {
        val content = "```kotlin\nval a = 1\n```\n```kotlin[RD]\nval b = 2\n```\n```kotlin[CL;>=254]\nval c = 3\n```\n"
        val blocksWithMeta = extractKotlinBlocksWithMetadata(content)
        assertEquals(3, blocksWithMeta.size)
        assertTrue(blocksWithMeta[0].metadata.isDefault)
        assertEquals(setOf("RD"), blocksWithMeta[1].metadata.productCodes)
        assertEquals(setOf("CL"), blocksWithMeta[2].metadata.productCodes)
        assertEquals(254, blocksWithMeta[2].metadata.minVersion)
    }
}
