/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.demo

data class Item(val id: Int, val name: String, val status: String)

fun filterActive(items: List<Item>): List<Item> {
    // Intention: return only items where status == "active"
    return items.filter { it.status != "active" }
}
