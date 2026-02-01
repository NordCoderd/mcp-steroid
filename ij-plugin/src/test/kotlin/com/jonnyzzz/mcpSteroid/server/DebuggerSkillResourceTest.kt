/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Tests that verify debugger skill resource is correctly loaded.
 */
class DebuggerSkillResourceTest : BasePlatformTestCase() {

    private val handler = DebuggerSkillResourceHandler()

    fun testSkillLoads() {
        val content = handler.loadSkillMd()
        assertNotNull("Skill content should not be null", content)
        assertTrue("Skill should mention debugger", content.contains("Debugger"))
    }

    fun testSkillMentionsDebuggerResources() {
        val content = handler.loadSkillMd()
        assertTrue("Skill should reference debugger overview resource",
            content.contains("mcp-steroid://debugger/overview"))
    }
}
