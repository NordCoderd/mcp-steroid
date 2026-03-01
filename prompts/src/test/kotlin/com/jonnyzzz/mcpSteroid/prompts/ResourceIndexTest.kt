/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.prompts

import com.jonnyzzz.mcpSteroid.prompts.generated.ResourcesIndex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ResourceIndexTest {
    @Test
    fun testUniqueUris() {
        val allUris = ResourcesIndex()
            .roots
            .flatMap { it.value.articles.values }
            .map { it.uri }

        assertEquals(allUris, allUris.distinct())
    }
}
