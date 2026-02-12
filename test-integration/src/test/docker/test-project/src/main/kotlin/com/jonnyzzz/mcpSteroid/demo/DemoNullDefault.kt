/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.demo

data class UserProfile(val id: String, val displayName: String, val email: String?)

/**
 * Bug: looks up user by displayName instead of id.
 * The registry maps id -> UserProfile, but findUser searches by displayName,
 * which never matches a key, so it always returns the fallback "Guest".
 */
fun findUser(registry: Map<String, UserProfile>, userId: String): String {
    // BUG: searches by displayName field instead of using userId as the key
    val profile = registry.values.firstOrNull { it.displayName == userId }
    return profile?.displayName ?: "Guest"
}

fun main() {
    val registry = mapOf(
        "u001" to UserProfile("u001", "Alice", "alice@example.com"),
        "u002" to UserProfile("u002", "Bob", null),
        "u003" to UserProfile("u003", "Charlie", "charlie@example.com"),
    )

    val expected = "Alice"
    val actual = findUser(registry, "u001")

    println("Looking up user id: u001")
    println("Expected: $expected")
    println("Actual  : $actual")

    check(actual == expected) { "User lookup is wrong: expected '$expected' but got '$actual' (intentionally)" }
}
