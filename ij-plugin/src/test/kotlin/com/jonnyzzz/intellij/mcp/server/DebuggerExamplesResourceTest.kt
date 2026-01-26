/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.server

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Tests that verify debugger example resources are correctly loaded and contain valid content.
 */
class DebuggerExamplesResourceTest : BasePlatformTestCase() {

    private val handler = DebuggerExamplesResourceHandler()

    fun testOverviewResourceLoads() {
        val overview = handler.loadOverview()
        assertNotNull("Overview should not be null", overview)
        assertTrue("Overview should contain title", overview.contains("Debugger Examples"))
        assertTrue("Overview should mention breakpoints", overview.contains("breakpoint"))
    }

    fun testAllExamplesAreDefined() {
        val examples = handler.examples
        assertEquals("Should have 6 debugger examples", 6, examples.size)

        val expectedIds = listOf(
            "set-line-breakpoint",
            "debug-run-configuration",
            "debug-session-control",
            "debug-list-threads",
            "debug-thread-dump",
            "demo-debug-test",
        )

        expectedIds.forEach { id ->
            assertTrue("Should have example: $id", examples.any { it.id == id })
        }
    }

    fun testSetLineBreakpointLoads() {
        val content = handler.loadExample("/debugger-examples/set-line-breakpoint.kts")
        assertNotNull("Content should not be null", content)
        assertTrue("Should have execute block", content.contains("execute {"))
        assertTrue("Should use XDebuggerUtil", content.contains("XDebuggerUtil"))
        assertTrue("Should add line breakpoint", content.contains("addLineBreakpoint"))
    }

    fun testThreadDumpLoads() {
        val content = handler.loadExample("/debugger-examples/debug-thread-dump.kts")
        assertNotNull("Content should not be null", content)
        assertTrue("Should have execute block", content.contains("execute {"))
        assertTrue("Should compute stack frames", content.contains("computeStackFrames"))
    }

    fun testAllExamplesHaveRequiredStructure() {
        handler.examples.forEach { example ->
            val content = handler.loadExample(example.resourcePath)
            assertNotNull("${example.id} should load", content)

            assertTrue("${example.id} should have execute block",
                content.contains("execute {"))

            assertTrue("${example.id} should call waitForSmartMode",
                content.contains("waitForSmartMode()"))
        }
    }

    fun testExampleDescriptionsAreUseful() {
        handler.examples.forEach { example ->
            assertTrue("${example.id} description should not be empty",
                example.description.isNotBlank())

            assertTrue("${example.id} description should have meaningful content (>50 chars)",
                example.description.length > 50)

            assertTrue("${example.id} description should mention debugger APIs",
                example.description.contains("IntelliJ") ||
                    example.description.contains("XDebugger") ||
                    example.description.contains("Debugger"))
        }
    }
}
