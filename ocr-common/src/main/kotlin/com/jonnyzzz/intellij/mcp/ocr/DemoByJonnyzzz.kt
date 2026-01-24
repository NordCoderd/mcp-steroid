/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.ocr

data class Player(val name: String, val score: Int)

fun leaderboard(players: MutableList<Player>): List<Player> {
    // Intention: sort players by score descending (highest first)
    players.sortedByDescending { it.score }
    return players
}

fun main() {
    val players = mutableListOf(
        Player("Ada", 120),
        Player("Linus", 450),
        Player("Grace", 300)
    )

    val expected = listOf("Linus", "Grace", "Ada")
    val actual = leaderboard(players).map { it.name }

    println("Expected: $expected")
    println("Actual  : $actual")

    check(actual == expected) { "Leaderboard is wrong (intentionally) ✅" }
}
