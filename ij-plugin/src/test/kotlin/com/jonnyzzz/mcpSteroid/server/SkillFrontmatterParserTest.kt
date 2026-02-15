/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillFrontmatterParserTest {

    @Test
    fun `test YAML frontmatter with dotted closing`() {
        val content = listOf(
            "---",
            "name: yaml-skill",
            "description: |",
            "  Line one",
            "  Line two",
            "...",
            "Body starts here",
        ).joinToString("\n")

        val parsed = parseSkillFrontmatter(content)
        assertNotNull(parsed.frontmatter)
        assertEquals("yaml-skill", parsed.frontmatter!!.name)
        assertTrue(parsed.frontmatter.description?.contains("Line one") == true)
        assertTrue(parsed.body.startsWith("Body starts here"))
    }

    @Test
    fun `test TOML frontmatter`() {
        val content = listOf(
            "+++",
            "name = \"toml-skill\"",
            "description = 'Toml description'",
            "+++",
            "Body text",
        ).joinToString("\n")

        val parsed = parseSkillFrontmatter(content)
        assertNotNull(parsed.frontmatter)
        assertEquals("toml-skill", parsed.frontmatter!!.name)
        assertEquals("Toml description", parsed.frontmatter.description)
        assertTrue(parsed.body.startsWith("Body text"))
    }
}