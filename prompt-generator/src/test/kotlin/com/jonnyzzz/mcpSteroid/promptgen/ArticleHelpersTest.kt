/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.promptgen

import com.squareup.kotlinpoet.ClassName
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class ArticleHelpersTest {

    @Test
    fun `folderToDisplayName known mappings`() {
        assertEquals("LSP", folderToDisplayName("lsp"))
        assertEquals("IDE", folderToDisplayName("ide"))
        assertEquals("Debugger", folderToDisplayName("debugger"))
        assertEquals("Test", folderToDisplayName("test"))
        assertEquals("VCS", folderToDisplayName("vcs"))
        assertEquals("Open Project", folderToDisplayName("open-project"))
        assertEquals("Skill", folderToDisplayName("skill"))
    }

    @Test
    fun `folderToDisplayName empty folder`() {
        assertEquals("", folderToDisplayName(""))
    }

    @Test
    fun `folderToDisplayName unknown folder uses titleCase`() {
        assertEquals("Custom", folderToDisplayName("custom"))
        assertEquals("Something", folderToDisplayName("something"))
    }

    @Test
    fun `payloadFileStem removes md suffix`() {
        assertEquals("my-file", payloadFileStem("folder/my-file.md"))
    }

    @Test
    fun `payloadFileStem removes kt suffix`() {
        assertEquals("my-file", payloadFileStem("folder/my-file.kt"))
    }

    @Test
    fun `payloadFileStem removes kts suffix`() {
        assertEquals("my-file", payloadFileStem("folder/my-file.kts"))
    }

    @Test
    fun `payloadFileStem replaces underscores with hyphens and lowercases`() {
        assertEquals("my-file-name", payloadFileStem("folder/My_File_Name.md"))
    }

    @Test
    fun `payloadFileStem extracts filename from path`() {
        assertEquals("file", payloadFileStem("deep/nested/path/file.md"))
    }

    @Test
    fun `buildArticleUri with folder`() {
        assertEquals("mcp-steroid://skill/my-file", buildArticleUri("skill", "skill/my-file.md"))
    }

    @Test
    fun `buildArticleUri with empty folder`() {
        assertEquals("mcp-steroid://my-file", buildArticleUri("", "my-file.md"))
    }

    @Test
    fun `buildArticleUri normalizes path`() {
        assertEquals("mcp-steroid://ide/my-tool", buildArticleUri("ide", "ide/My_Tool.md"))
    }

    @Test
    fun `groupByArticle filters non-md files`() {
        val ktClazz = makeGeneratedPromptClazz("kt", "folder/file.kt", "folder")
        val result = groupByArticle(listOf(ktClazz))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `groupByArticle filters root-level files`() {
        val rootClazz = makeGeneratedPromptClazz("md", "file.md", "")
        val result = groupByArticle(listOf(rootClazz))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `groupByArticle keeps subfolder md files`() {
        val clazz = makeGeneratedPromptClazz("md", "skill/file.md", "skill")
        val result = groupByArticle(listOf(clazz))
        assertEquals(1, result.size)
        assertEquals(clazz, result[0].payload)
    }

    @Test
    fun `groupByArticle mixed inputs`() {
        val mdSubfolder = makeGeneratedPromptClazz("md", "ide/tool.md", "ide")
        val mdRoot = makeGeneratedPromptClazz("md", "readme.md", "")
        val ktSubfolder = makeGeneratedPromptClazz("kt", "ide/tool.kt", "ide")
        val result = groupByArticle(listOf(mdSubfolder, mdRoot, ktSubfolder))
        assertEquals(1, result.size)
        assertEquals("ide/tool.md", result[0].canonicalPayloadPath)
    }

    @Test
    fun `noAutoToc false when no markers`() {
        val clazz = makeGeneratedPromptClazz("md", "skill/file.md", "skill", content = "Title\n\nDesc\n\nBody\n")
        val article = PromptArticle(payload = clazz)
        assertFalse(article.noAutoToc)
    }

    @Test
    fun `noAutoToc true with NO_AUTO_TOC marker`() {
        val clazz = makeGeneratedPromptClazz("md", "skill/file.md", "skill", content = "Title\n\nDesc\n\n$NO_AUTO_TOC_MARKER\nBody\n")
        val article = PromptArticle(payload = clazz)
        assertTrue(article.noAutoToc)
    }

    @Test
    fun `noAutoToc true with EXCLUDE_FROM_AUTO_TOC marker`() {
        val clazz = makeGeneratedPromptClazz("md", "skill/file.md", "skill", content = "Title\n\nDesc\n\n$EXCLUDE_FROM_AUTO_TOC_MARKER\nBody\n")
        val article = PromptArticle(payload = clazz)
        assertTrue(article.noAutoToc)
    }

    private fun makeGeneratedPromptClazz(
        fileType: String,
        path: String,
        folder: String,
        content: String = "dummy content",
    ): GeneratedPromptClazz {
        val tmpFile = File.createTempFile("test-", ".$fileType")
        tmpFile.deleteOnExit()
        tmpFile.writeText(content)
        return GeneratedPromptClazz(
            fileType = fileType,
            folder = folder,
            path = path,
            clazzName = ClassName("com.test", path.substringAfterLast("/").removeSuffix(".$fileType").toPromptClassName() + "Prompt"),
            src = tmpFile,
        )
    }
}
