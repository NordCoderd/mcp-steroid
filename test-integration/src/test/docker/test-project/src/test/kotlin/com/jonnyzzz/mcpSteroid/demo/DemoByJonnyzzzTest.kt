/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.demo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Unit tests for [leaderboard] function.
 *
 * These tests are INTENTIONALLY FAILING due to a bug in leaderboard().
 * The function calls players.sortedByDescending { it.score } but ignores
 * the return value, so the original (unsorted) list is returned.
 *
 * Use the IntelliJ debugger to discover why the assertion fails:
 * - Set a breakpoint inside leaderboard()
 * - Run this test via "Debug" in the IDE test runner
 * - Observe that sortedByDescending() produces a sorted list but it is never assigned back
 */
class DemoByJonnyzzzTest {

    @Test
    fun `leaderboard returns players sorted by score descending`() {
        val players = mutableListOf(
            Player("Ada", 120),
            Player("Linus", 450),
            Player("Grace", 300),
        )

        val result = leaderboard(players)

        // Linus (450) should be first, Grace (300) second, Ada (120) third
        assertEquals("Linus", result[0].name,
            "Expected Linus first (score=450), got ${result[0].name} (score=${result[0].score})")
        assertEquals("Grace", result[1].name,
            "Expected Grace second (score=300), got ${result[1].name} (score=${result[1].score})")
        assertEquals("Ada", result[2].name,
            "Expected Ada third (score=120), got ${result[2].name} (score=${result[2].score})")
    }

    @Test
    fun `leaderboard result scores are in descending order`() {
        val players = mutableListOf(
            Player("X", 10),
            Player("Y", 50),
            Player("Z", 30),
        )

        val result = leaderboard(players)
        val scores = result.map { it.score }

        assertEquals(50, scores[0], "Highest score should be first, got: $scores")
        assertEquals(30, scores[1], "Second score should be 30, got: $scores")
        assertEquals(10, scores[2], "Lowest score should be last, got: $scores")
    }
}
