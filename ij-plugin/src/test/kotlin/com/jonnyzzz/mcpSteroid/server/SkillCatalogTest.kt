/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class SkillCatalogTest : BasePlatformTestCase() {

    fun testSkillFrontmatterParsed() {
        val catalog = service<SkillCatalog>()
        val mainSkill = catalog.findByPromptName("intellij-mcp-steroid")
        assertNotNull("Main skill should be registered", mainSkill)
        assertEquals("IntelliJ API Power User Guide", mainSkill!!.descriptor.resourceName)
        assertTrue(
            "Main skill description should be parsed",
            mainSkill.description?.contains("Execute Kotlin code") == true
        )
        assertFalse(
            "Prompt content should not start with frontmatter",
            mainSkill.contentWithoutFrontmatter.trimStart().startsWith("---")
        )
    }

    fun testDebuggerSkillPromptName() {
        val catalog = service<SkillCatalog>()
        val debuggerSkill = catalog.findByPromptName("intellij-mcp-steroid-debugger")
        assertNotNull("Debugger skill should be registered by prompt name", debuggerSkill)
    }
}
