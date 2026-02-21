/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.demo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Unit tests for [filterActive] function.
 *
 * These tests are INTENTIONALLY FAILING due to a bug in filterActive().
 * The function uses `!=` (not equal) instead of `==` (equal) when checking
 * the status field, so it returns inactive items instead of active ones.
 *
 * Use the IntelliJ debugger to discover why the assertion fails:
 * - Set a breakpoint inside filterActive()
 * - Run this test via "Debug" in the IDE test runner
 * - Observe that the filter condition `it.status != "active"` is inverted
 */
class JonnyzzzDebugTest {

    @Test
    fun `filterActive returns only active items`() {
        val items = listOf(
            Item(1, "Alpha", "active"),
            Item(2, "Beta", "inactive"),
            Item(3, "Gamma", "active"),
        )

        val result = filterActive(items)

        assertEquals(2, result.size,
            "Expected 2 active items, got ${result.size}: ${result.map { it.name }}")
        assertEquals("Alpha", result[0].name,
            "Expected Alpha first (active), got ${result[0].name} (${result[0].status})")
        assertEquals("Gamma", result[1].name,
            "Expected Gamma second (active), got ${result[1].name} (${result[1].status})")
    }

    @Test
    fun `filterActive excludes inactive items`() {
        val items = listOf(
            Item(1, "Alpha", "active"),
            Item(2, "Beta", "inactive"),
        )

        val result = filterActive(items)

        assertEquals(1, result.size,
            "Expected 1 active item, got ${result.size}: ${result.map { "${it.name}/${it.status}" }}")
        assertEquals("active", result[0].status,
            "Expected result item to have status 'active', got: ${result[0].status}")
    }
}
