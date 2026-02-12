/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.gradle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HostArchitectureTest {
    @Test
    fun resolvesArmAliases() {
        assertEquals(HostArchitecture.ARM64, resolveHostArchitecture("aarch64"))
        assertEquals(HostArchitecture.ARM64, resolveHostArchitecture("arm64"))
    }

    @Test
    fun resolvesX86Aliases() {
        assertEquals(HostArchitecture.X86_64, resolveHostArchitecture("x86_64"))
        assertEquals(HostArchitecture.X86_64, resolveHostArchitecture("amd64"))
    }

    @Test
    fun rejectsUnknownArchitecture() {
        assertFailsWith<IllegalArgumentException> {
            resolveHostArchitecture("i386")
        }
    }
}
