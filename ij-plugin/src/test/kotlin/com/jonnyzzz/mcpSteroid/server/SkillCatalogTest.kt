/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.mcpSteroid.prompts.generated.skill.SkillIndex

class SkillCatalogTest : BasePlatformTestCase() {

    private val context get() = ResourceRegistrar.buildPromptsContext()

    fun testSkillFrontmatterParsed() {
        val index = SkillIndex()
        val skillArticle = index.articles.values.first { article ->
            val content = article.readPayload(context)
            val parsed = parseSkillFrontmatter(content)
            parsed.frontmatter?.name == "mcp-steroid"
        }

        val content = skillArticle.readPayload(context)
        val parsed = parseSkillFrontmatter(content)
        assertNotNull("Main skill frontmatter should be parsed", parsed.frontmatter)
        assertTrue(
            "Main skill description should be parsed",
            parsed.frontmatter?.description?.contains("Execute Kotlin code") == true
        )
        assertFalse(
            "Prompt content should not start with frontmatter",
            parsed.body.trimStart().startsWith("---")
        )
    }

    fun testDebuggerSkillPromptName() {
        val index = SkillIndex()
        val debuggerArticle = index.articles.values.firstOrNull { article ->
            val content = article.readPayload(context)
            val parsed = parseSkillFrontmatter(content)
            parsed.frontmatter?.name == "mcp-steroid-debugger"
        }
        assertNotNull("Debugger skill should have frontmatter with prompt name", debuggerArticle)
    }
}
