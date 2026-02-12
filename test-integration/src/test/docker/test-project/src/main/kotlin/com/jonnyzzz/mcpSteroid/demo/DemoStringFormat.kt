/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.demo

data class Address(val city: String, val street: String, val zip: String)

/**
 * Bug: city and street are swapped in the formatted string.
 * Expected format: "street, city ZIP"
 * Actual format:   "city, street ZIP"
 */
fun formatAddress(address: Address): String {
    // BUG: city and street are swapped in the template
    val formatted = "${address.city}, ${address.street} ${address.zip}"
    return formatted
}

fun main() {
    val address = Address(
        city = "Springfield",
        street = "742 Evergreen Terrace",
        zip = "62704"
    )

    val expected = "742 Evergreen Terrace, Springfield 62704"
    val actual = formatAddress(address)

    println("Expected: $expected")
    println("Actual  : $actual")

    check(actual == expected) { "Address format is wrong (intentionally)" }
}
