/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.demo

/**
 * Bug: off-by-one error in loop boundary.
 * The loop starts at index 1 instead of 0, skipping the first element.
 * This causes the sum to be wrong.
 */
fun sumOfScores(items: List<Int>): Int {
    var total = 0
    // BUG: starts at index 1 instead of 0, skipping the first element
    var i = 1
    while (i < items.size) {
        total += items[i]
        i++
    }
    return total
}

fun main() {
    val scores = listOf(10, 20, 30, 40)

    val expected = 100 // 10 + 20 + 30 + 40
    val actual = sumOfScores(scores)

    println("Expected sum: $expected")
    println("Actual sum  : $actual")

    check(actual == expected) { "Sum is wrong: expected $expected but got $actual (intentionally)" }
}
