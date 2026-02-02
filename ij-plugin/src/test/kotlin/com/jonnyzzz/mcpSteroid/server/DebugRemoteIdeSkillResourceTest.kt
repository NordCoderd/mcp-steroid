/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Tests that verify debug remote IDE skill resource is correctly loaded.
 */
class DebugRemoteIdeSkillResourceTest : BasePlatformTestCase() {

    private val handler = DebugRemoteIdeSkillResourceHandler()

    fun testSkillLoads() {
        val content = handler.loadSkillMd()
        assertNotNull("Skill content should not be null", content)
        assertTrue("Skill should mention debug", content.contains("Debug"))
    }

    fun testSkillMentionsRunManager() {
        val content = handler.loadSkillMd()
        assertTrue("Skill should mention RunManager for launching IDEs",
            content.contains("RunManager"))
    }

    fun testSkillMentionsBreakpoints() {
        val content = handler.loadSkillMd()
        assertTrue("Skill should mention XDebuggerManager for breakpoints",
            content.contains("XDebuggerManager"))
    }
}
