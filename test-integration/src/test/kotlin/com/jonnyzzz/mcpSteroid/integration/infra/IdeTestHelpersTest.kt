/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.File

class IdeTestHelpersTest {
    @Test
    fun `parseDockerHostPathMappings returns empty for blank input`() {
        assertEquals(emptyList<Pair<String, String>>(), parseDockerHostPathMappings(null))
        assertEquals(emptyList<Pair<String, String>>(), parseDockerHostPathMappings(""))
        assertEquals(emptyList<Pair<String, String>>(), parseDockerHostPathMappings("   "))
    }

    @Test
    fun `remapPathForDockerHost remaps matched prefix`() {
        val remapped = remapPathForDockerHost(
            File("/workspace/test-integration/build/test-logs/test"),
            "/workspace=/host-workspace",
        )

        assertEquals(
            File("/host-workspace/test-integration/build/test-logs/test").absolutePath,
            remapped.absolutePath,
        )
    }

    @Test
    fun `remapPathForDockerHost keeps path when no mapping matches`() {
        val original = File("/tmp/somewhere")
        val remapped = remapPathForDockerHost(original, "/workspace=/host-workspace")

        assertEquals(original.absolutePath, remapped.absolutePath)
    }

    @Test
    fun `remapPathForDockerHost prefers longer source prefix`() {
        val remapped = remapPathForDockerHost(
            File("/workspace/test-integration/build/test-logs/test"),
            "/workspace=/host-workspace,/workspace/test-integration=/host-workspace-special",
        )

        assertEquals(
            File("/host-workspace-special/build/test-logs/test").absolutePath,
            remapped.absolutePath,
        )
    }

    @Test
    fun `parseDockerHostPathMappings rejects invalid entries`() {
        assertThrows(IllegalArgumentException::class.java) {
            parseDockerHostPathMappings("/workspace")
        }
    }
}
