/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.docker

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DockerDriverPortParsingTest {
    @Test
    fun `parseMappedPortOutput parses IPv4 mapping`() {
        val mapped = DockerDriver.parseMappedPortOutput("0.0.0.0:52134")
        assertEquals(52134, mapped)
    }

    @Test
    fun `parseMappedPortOutput parses IPv6 mapping`() {
        val mapped = DockerDriver.parseMappedPortOutput("[::]:40211")
        assertEquals(40211, mapped)
    }

    @Test
    fun `parseMappedPortOutput skips blank lines`() {
        val mapped = DockerDriver.parseMappedPortOutput("\n\n0.0.0.0:31000\n")
        assertEquals(31000, mapped)
    }

    @Test
    fun `parseMappedPortOutput returns null for empty output`() {
        val mapped = DockerDriver.parseMappedPortOutput("")
        assertNull(mapped)
    }

    @Test
    fun `parseContainerRunningState parses true`() {
        val running = DockerDriver.parseContainerRunningState("true\n")
        assertTrue(running == true)
    }

    @Test
    fun `parseContainerRunningState parses false`() {
        val running = DockerDriver.parseContainerRunningState(" false ")
        assertFalse(running == true)
    }

    @Test
    fun `parseContainerRunningState returns null for unknown value`() {
        val running = DockerDriver.parseContainerRunningState("<no value>")
        assertNull(running)
    }
}
