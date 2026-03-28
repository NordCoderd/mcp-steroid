/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpBuildersTest {

    @Test
    fun `build merges all text items into single content item`() {
        val result = ToolCallResult.builder()
            .addTextContent("first")
            .addTextContent("second")
            .addTextContent("third")
            .build()

        val textItems = result.content.filterIsInstance<ContentItem.Text>()
        assertEquals("Should have exactly 1 text item", 1, textItems.size)
        assertEquals("first\nsecond\nthird", textItems.single().text)
        assertFalse(result.isError)
    }

    @Test
    fun `build preserves non-text items separately`() {
        val result = ToolCallResult.builder()
            .addTextContent("text before")
            .addContent(ContentItem.Image(data = "base64data", mimeType = "image/png"))
            .addTextContent("text after")
            .build()

        assertEquals("Should have 2 content items (1 merged text + 1 image)", 2, result.content.size)

        val text = result.content.filterIsInstance<ContentItem.Text>().single()
        assertEquals("text before\ntext after", text.text)

        val image = result.content.filterIsInstance<ContentItem.Image>().single()
        assertEquals("base64data", image.data)
    }

    @Test
    fun `build with error flag and multiple texts produces single merged text`() {
        val result = ToolCallResult.builder()
            .addTextContent("execution_id: test-exec-1")
            .addTextContent("Compiler Errors/Warnings:\nerror: type mismatch")
            .addTextContent("HINT: check your types")
            .addTextContent("FAILED: kotlinc exited with code: 1")
            .markAsError()
            .build()

        assertTrue(result.isError)

        val textItems = result.content.filterIsInstance<ContentItem.Text>()
        assertEquals("Should have exactly 1 text item even with isError", 1, textItems.size)

        val text = textItems.single().text
        assertTrue("Should contain execution_id", text.contains("execution_id: test-exec-1"))
        assertTrue("Should contain type mismatch error", text.contains("type mismatch"))
        assertTrue("Should contain FAILED marker", text.contains("FAILED:"))
    }

    @Test
    fun `build with no content produces empty list`() {
        val result = ToolCallResult.builder().build()
        assertTrue(result.content.isEmpty())
        assertFalse(result.isError)
    }

    @Test
    fun `build with only image produces single image item`() {
        val result = ToolCallResult.builder()
            .addContent(ContentItem.Image(data = "img", mimeType = "image/png"))
            .build()

        assertEquals(1, result.content.size)
        assertTrue(result.content.single() is ContentItem.Image)
    }

    @Test
    fun `text item comes before non-text items in output`() {
        val result = ToolCallResult.builder()
            .addContent(ContentItem.Image(data = "img1", mimeType = "image/png"))
            .addTextContent("some text")
            .addContent(ContentItem.Image(data = "img2", mimeType = "image/png"))
            .build()

        assertEquals(3, result.content.size)
        assertTrue("First item should be merged text", result.content[0] is ContentItem.Text)
        assertTrue("Second item should be image", result.content[1] is ContentItem.Image)
        assertTrue("Third item should be image", result.content[2] is ContentItem.Image)
    }
}
